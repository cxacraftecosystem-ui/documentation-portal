export interface TimerScope {
  setTimeout(handler: (...args: never[]) => void, delay?: number, ...args: unknown[]): number;
  clearTimeout(id?: number): void;
}

const FIRST_ID = 0x4000_0000;

const installedIn = new WeakSet<object>();

export function installUnthrottledTimers(scope: object = globalThis): boolean {
  if (installedIn.has(scope)) return true;
  if (typeof MessageChannel !== 'function') return false;

  const target = scope as TimerScope;
  const nativeSetTimeout = target.setTimeout.bind(scope);
  const nativeClearTimeout = target.clearTimeout.bind(scope);

  const channel = new MessageChannel();
  const pending = new Map<number, () => void>();
  let nextId = FIRST_ID;

  channel.port1.onmessage = (event: MessageEvent): void => {
    const id = event.data as number;
    const run = pending.get(id);
    if (run === undefined) return;
    pending.delete(id);
    run();
  };

  target.setTimeout = (handler: unknown, delay?: number, ...args: unknown[]): number => {
    if (typeof handler !== 'function' || (typeof delay === 'number' && delay > 0)) {
      return nativeSetTimeout(handler as (...a: never[]) => void, delay, ...args);
    }
    const id = nextId++;
    const callback = handler as (...a: unknown[]) => void;
    pending.set(id, () => {
      try {
        callback(...args);
      } catch (err) {
        nativeSetTimeout(() => {
          throw err;
        }, 0);
      }
    });
    channel.port2.postMessage(id);
    return id;
  };

  target.clearTimeout = (id?: number): void => {
    if (typeof id === 'number' && pending.delete(id)) return;
    nativeClearTimeout(id);
  };

  installedIn.add(scope);
  return true;
}
