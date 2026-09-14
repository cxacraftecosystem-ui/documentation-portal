/**
 * A zero-delay `setTimeout` that a hidden tab cannot throttle.
 *
 * ## The bug this exists to prevent
 *
 * `engine/pipeline.ts` yields a macrotask between every stage, and it does so with
 * `setTimeout(resolve, 0)` — correctly, because a microtask would not drain the worker's message queue
 * and a cancel could never be observed. But a chain of zero-delay timeouts is exactly the pattern
 * browsers throttle hardest:
 *
 *  - the HTML spec itself clamps a timeout to **4 ms** once the nesting level passes five, and every
 *    stage boundary of one trace is one more level of nesting;
 *  - Chromium clamps timers in a **hidden** page to one per second, and after five minutes hidden
 *    applies *intensive throttling* — one wake-up **per minute**;
 *  - that policy is inherited by the page's **dedicated workers**. A worker is not exempt. The whole
 *    reason the engine is in a worker — that it keeps computing while the user is elsewhere — is
 *    quietly cancelled by the one primitive the pipeline uses to stay cancellable.
 *
 * The visible symptom is not a hang and not an error. It is a trace that takes eleven seconds with the
 * tab in front and eleven *minutes* with the tab in the background, for eleven stages that each did
 * their work in milliseconds and then sat waiting for a timer the browser had decided not to fire.
 *
 * ## Why a `MessageChannel`
 *
 * A message posted to a `MessagePort` is dispatched from a task queue that is **not** the timer task
 * queue, so none of the clamping above applies to it — throttling message delivery would break message
 * passing itself. It is still a genuine macrotask, so it keeps the property the pipeline actually
 * needs: the event loop turns, the worker's `message` queue is drained, and a `cancel` that arrived
 * mid-stage takes effect at the next stage boundary exactly as before.
 *
 * This is the same reason React's own scheduler posts to a `MessageChannel` rather than calling
 * `setTimeout(0)`.
 *
 * ## Why it is done by replacing the global rather than by changing the engine
 *
 * The engine is shared with the Kotlin client and is held to bit-for-bit parity against
 * `docs/fixtures/*.json`. *When* a stage boundary yields is a property of the host, not of the
 * algorithm — nothing here changes a number the pipeline computes, and the fixtures cannot move. So
 * the scheduling primitive is replaced in the worker that hosts the engine, once, at start-up, and the
 * engine keeps its one honest `setTimeout(resolve, 0)`.
 *
 * Only **zero-delay** calls are rerouted. A call with a real delay is asking to be scheduled in time
 * and is handed to the platform untouched, throttling and all — reimplementing a clock here would be a
 * second bug wearing the first one's clothes.
 */

/** The two globals this module replaces, narrowed to the shape it uses. */
export interface TimerScope {
  setTimeout(handler: (...args: never[]) => void, delay?: number, ...args: unknown[]): number;
  clearTimeout(id?: number): void;
}

/**
 * First id handed out for a rerouted timer.
 *
 * High enough that it cannot collide with a platform id: browsers hand those out from a counter that
 * starts at 1 per global, and a page that legitimately reached a billion timers has other problems.
 * Collisions matter because `clearTimeout` has to decide, from the number alone, which of the two
 * schedulers owns it.
 */
const FIRST_ID = 0x4000_0000;

/** Scopes already patched, so a second call is a no-op rather than a shim wrapping a shim. */
const installedIn = new WeakSet<object>();

/**
 * Replaces `scope.setTimeout`/`scope.clearTimeout` with versions that route zero-delay callbacks
 * through a `MessagePort`.
 *
 * @param scope defaults to the worker global. Injectable so the behaviour is testable without a worker.
 * @returns true when the reroute is in place; false when this environment has no `MessageChannel`, in
 *   which case the platform timers are left exactly as they were and a hidden tab traces slowly rather
 *   than not at all.
 */
export function installUnthrottledTimers(scope: object = globalThis): boolean {
  if (installedIn.has(scope)) return true;
  if (typeof MessageChannel !== 'function') return false;

  const target = scope as TimerScope;
  // Bound, not merely captured: `setTimeout` is a WebIDL operation on the global, and calling it with
  // the wrong receiver throws "Illegal invocation" in Chrome. The shim below is stored on the same
  // object, so an unbound call would be one of those.
  const nativeSetTimeout = target.setTimeout.bind(scope);
  const nativeClearTimeout = target.clearTimeout.bind(scope);

  const channel = new MessageChannel();
  /** Rerouted callbacks awaiting delivery. Removing an entry is what `clearTimeout` means here. */
  const pending = new Map<number, () => void>();
  let nextId = FIRST_ID;

  channel.port1.onmessage = (event: MessageEvent): void => {
    const id = event.data as number;
    const run = pending.get(id);
    // Absent when the timer was cleared between the post and the delivery — the ordinary race that
    // `clearTimeout` exists for, and the reason the map is the source of truth rather than the port.
    if (run === undefined) return;
    pending.delete(id);
    run();
  };

  target.setTimeout = (handler: unknown, delay?: number, ...args: unknown[]): number => {
    // A positive delay is a request about *time*; a string handler is the legacy eval form. Neither is
    // this module's business, and both go to the platform unchanged.
    if (typeof handler !== 'function' || (typeof delay === 'number' && delay > 0)) {
      return nativeSetTimeout(handler as (...a: never[]) => void, delay, ...args);
    }
    const id = nextId++;
    const callback = handler as (...a: unknown[]) => void;
    pending.set(id, () => {
      try {
        callback(...args);
      } catch (err) {
        // A throwing callback must not take the port with it: one bad timer would silently stop every
        // future yield and the trace would hang forever. Rethrowing from a platform timer reports it
        // to the global error handler, which is where a real `setTimeout` would have put it.
        nativeSetTimeout(() => {
          throw err;
        }, 0);
      }
    });
    channel.port2.postMessage(id);
    return id;
  };

  target.clearTimeout = (id?: number): void => {
    // `delete` returns true only for ids this shim issued, which is the whole ownership test.
    if (typeof id === 'number' && pending.delete(id)) return;
    nativeClearTimeout(id);
  };

  installedIn.add(scope);
  return true;
}
