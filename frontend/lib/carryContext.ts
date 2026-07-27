/**
 * The records a researcher is currently working with — artisan, craft, workshop, product, tool,
 * process — remembered across page loads and offered to the next form they open.
 *
 * WHAT PROBLEM THIS SOLVES. `components/CarryForwardCards` already carries context into the next
 * record — but only through the query string, and only if the researcher clicks one of its cards
 * straight after saving. Go back via the dashboard, or open a product form an hour later, and the
 * context is gone and every field is typed again. In Bagru that is the same four fields (craft,
 * artisan, place, workshop) re-entered for a product, then a tool, then another tool — and then the
 * product re-picked from a dropdown for the process that documents how it was made.
 *
 * WHERE IT LIVES, AND WHY NOT THE ALTERNATIVES.
 *   - **localStorage** (chosen). Synchronous, so the banner and the prefilled fields resolve in the
 *     same commit as hydration instead of a frame later; survives a browser restart; and works with
 *     no connection, which is the normal state in the field.
 *   - *My Activity* (app/(protected)/activity) is server-backed — it fetches /artisans, /products
 *     and friends. It is the wrong shelf for anything a researcher on a dead 2G link must still
 *     get: offline it returns nothing at all.
 *   - The IndexedDB outbox (lib/offline) is for queued saves and their file bytes. It is async and
 *     drains itself, so a value read on mount would arrive after the form had already rendered
 *     empty, and would vanish the moment the queue synced.
 *
 * PER USER, ALWAYS. Field laptops are shared: a researcher hands the machine to a colleague between
 * sittings. The key is namespaced by user id AND the stored row repeats it, so a row written under
 * one account can never surface under another even if the key were reached directly.
 *
 * WHAT IT MAY HOLD. One id and one display name per record type, plus the artisan's place — the
 * things that describe the SITTING. `sanitizeCarryContext` is an allowlist rather than a blocklist
 * so this stays true by construction: Aadhaar and Pehchan numbers are regulated PII and must never
 * be copied between records, let alone parked in local storage on a shared machine, and there is
 * deliberately no field here they could occupy.
 *
 * ── PRECEDENCE ─────────────────────────────────────────────────────────────────────────────────
 * The bag accumulates, so it can hold a product AND a tool AND the artisan both belong to, and the
 * whole point is that they stay mutually consistent. Records are not a flat set: a product belongs
 * to an artisan, who belongs to a craft; a process belongs to a product. [CARRY_PARENT] states that
 * ladder once, and [mergeCarryContext] enforces three rules over it — see the comments there.
 * Getting this wrong does not degrade a convenience, it attaches a product to an artisan who never
 * made it, which is a falsified row in a research corpus.
 */

/**
 * Every record type that can be in context, ANCESTOR-FIRST.
 *
 * The order is load-bearing: [mergeCarryContext] walks it top-down so a contradicted parent has
 * already dropped its children by the time those children are considered.
 */
export const CARRY_NODES = ["workshop", "craft", "artisan", "product", "tool", "process"] as const;

export type CarryNode = (typeof CARRY_NODES)[number];

/**
 * Who owns whom. A product belongs to an artisan, an artisan practises a craft, a process documents
 * a product, a tool is used by an artisan.
 *
 * The workshop is deliberately a root rather than anybody's parent: it is the sitting, not a link in
 * the chain, and it outlives an artisan switch because the researcher is still standing in the same
 * courtyard.
 */
export const CARRY_PARENT: Record<CarryNode, CarryNode | null> = {
  workshop: null,
  craft: null,
  artisan: "craft",
  product: "artisan",
  tool: "artisan",
  process: "product"
};

/** Everything that may be carried. Add a field here only if it describes the SITTING, not a record. */
export type CarryContext = {
  workshopId: string | null;
  workshopName: string | null;
  craftId: string | null;
  craftName: string | null;
  artisanId: string | null;
  artisanName: string | null;
  place: string | null;
  productId: string | null;
  productName: string | null;
  toolId: string | null;
  toolName: string | null;
  processId: string | null;
  processName: string | null;
};

/**
 * Which fields belong to which record, id first. `place` hangs off the artisan rather than standing
 * alone: it is where THAT artisan works, so it has to die with them.
 */
const CARRY_NODE_FIELDS: Record<CarryNode, readonly (keyof CarryContext)[]> = {
  workshop: ["workshopId", "workshopName"],
  craft: ["craftId", "craftName"],
  artisan: ["artisanId", "artisanName", "place"],
  product: ["productId", "productName"],
  tool: ["toolId", "toolName"],
  process: ["processId", "processName"]
};

/** How each record type is named to the researcher, in the banner and in the provenance sentence. */
export const CARRY_NODE_LABEL: Record<CarryNode, string> = {
  workshop: "workshop",
  craft: "craft",
  artisan: "artisan",
  product: "product",
  tool: "tool",
  process: "process"
};

const CARRY_FIELDS = CARRY_NODES.flatMap((node) => CARRY_NODE_FIELDS[node]);

function idField(node: CarryNode): keyof CarryContext {
  return `${node}Id` as keyof CarryContext;
}

/** Everything that hangs off this record, however deep — what a contradiction has to take with it. */
export function carryDescendants(node: CarryNode): CarryNode[] {
  return CARRY_NODES.filter((candidate) => {
    for (let parent = CARRY_PARENT[candidate]; parent; parent = CARRY_PARENT[parent]) {
      if (parent === node) return true;
    }
    return false;
  });
}

/** A stored context plus the bookkeeping that decides whether it is still worth offering. */
export type StoredCarryContext = CarryContext & {
  /** Epoch ms of the last write. Drives both the expiry and the "saved 20 minutes ago" line. */
  savedAt: number;
  /** Repeated inside the row so a context can never be read back under the wrong account. */
  userId: string;
  /**
   * The record the last write was ABOUT — the narrowest thing named in that patch. It is what lets
   * the banner say "from the PRODUCT you saved" rather than guessing, and what makes the implied
   * parents ("its artisan and craft came with it") nameable.
   */
  originNode: CarryNode | null;
};

/**
 * Twelve hours.
 *
 * A carry-forward context describes one sitting — a day at a workshop in Bagru, a morning in Akola.
 * Twelve hours spans the longest realistic one (arrive at eight, pack up at eight) while
 * guaranteeing that a night always ends it: nobody comes back the next morning and finds yesterday's
 * artisan waiting in today's first form. Context from three days ago is not context, it is a trap.
 */
export const CARRY_CONTEXT_TTL_MS = 12 * 60 * 60 * 1000;

/** localStorage key, alongside `field_repo_token` (lib/api) and `field_repo_preferences`. */
export function carryContextStorageKey(userId: string): string {
  return `field_repo_carry_context:${userId}`;
}

const EMPTY: CarryContext = {
  workshopId: null,
  workshopName: null,
  craftId: null,
  craftName: null,
  artisanId: null,
  artisanName: null,
  place: null,
  productId: null,
  productName: null,
  toolId: null,
  toolName: null,
  processId: null,
  processName: null
};

/** "" and "   " are not context; they are an empty field the user never filled. */
function cleanText(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

/**
 * Coerce anything — a query string, a form's state, a row written by an older build — into a
 * CarryContext holding only the allowlisted fields. Unknown keys are dropped rather than copied,
 * which is what keeps identity numbers out of storage no matter who calls this.
 */
export function sanitizeCarryContext(value: unknown): CarryContext {
  const clean = { ...EMPTY };
  if (!value || typeof value !== "object") return clean;
  const raw = value as Record<string, unknown>;
  for (const field of CARRY_FIELDS) clean[field] = cleanText(raw[field]);
  return clean;
}

/** True when the context names no record at all. A bare workshop is not context: WorkshopSelect
 * already defaults to the most recent one, so offering it back would be a banner about nothing. */
export function isEmptyCarryContext(context: CarryContext | null): boolean {
  if (!context) return true;
  return !CARRY_NODES.some((node) => node !== "workshop" && CARRY_NODE_FIELDS[node].some((field) => context[field]));
}

/** True when this record type is named at all — by id or, for an unlinked record, by name alone. */
export function hasCarryNode(context: CarryContext | null, node: CarryNode): boolean {
  return Boolean(context && CARRY_NODE_FIELDS[node].some((field) => context[field]));
}

/**
 * The narrowest record named here — the product rather than its artisan, the process rather than
 * its product. Read in reverse ladder order, so the deepest match wins and ties are decided the
 * same way every time.
 */
export function narrowestCarryNode(context: CarryContext | null): CarryNode | null {
  if (!context) return null;
  for (let index = CARRY_NODES.length - 1; index >= 0; index -= 1) {
    const node = CARRY_NODES[index];
    if (hasCarryNode(context, node)) return node;
  }
  return null;
}

function clearNode(context: CarryContext, node: CarryNode): void {
  for (const field of CARRY_NODE_FIELDS[node]) context[field] = null;
}

/**
 * Keep only the record types a given form actually uses.
 *
 * Unlike [pruneCarryNode] this does NOT cascade: it is a view of the bag, not an edit to it. A
 * product form has no process field, so the process is not shown and not applied there, but it is
 * still in storage for the process form that does. Narrowing is what keeps the banner honest — it
 * may only name what was really put into the fields in front of the researcher.
 */
export function selectCarryNodes(context: CarryContext, nodes: readonly CarryNode[]): CarryContext {
  const next = sanitizeCarryContext(context);
  for (const node of CARRY_NODES) {
    if (!nodes.includes(node)) clearNode(next, node);
  }
  return next;
}

/** Drop this record and everything that hangs off it, leaving the rest of the bag intact. */
export function pruneCarryNode(context: CarryContext, node: CarryNode): CarryContext {
  const next = sanitizeCarryContext(context);
  for (const dead of [node, ...carryDescendants(node)]) clearNode(next, dead);
  return next;
}

/**
 * Fold a patch into the context already held.
 *
 * THREE RULES, in this order, over the [CARRY_PARENT] ladder:
 *
 *  1. **A different record replaces its own subtree.** Naming product P when the bag holds product Q
 *     drops Q and Q's process — that process documented Q, and re-pointing it at P would invent a
 *     fact. Naming a different artisan drops their products, tools and place the same way.
 *
 *  2. **A name without a link is also a contradiction.** A form that submits `artisanName: "Shyam"`
 *     with no `artisanId` has an unlinked record, so the artisan id in the bag is no longer what the
 *     name refers to. Keeping the id would silently attach the next product to the previous artisan
 *     — the exact failure this whole module exists to prevent — so the id goes and the name stays.
 *
 *  3. **A parent the patch did not name cannot vouch for a child it never had.** Once anything in
 *     the chain has been contradicted, every ancestor above the patch's own subject is walked up
 *     and dropped until one the patch actually mentions is reached. A craft left behind from the
 *     previous artisan is a stale parent, and a stale parent is how a product ends up filed under
 *     the wrong craft.
 *
 * Everything NOT under a contradicted record survives: that is what makes this a bag rather than a
 * pointer. Documenting a tool and then a product for the same artisan leaves both in context, which
 * is the whole request.
 */
export function mergeCarryContext(previous: CarryContext | null, patch: Partial<CarryContext>): CarryContext {
  const incoming = sanitizeCarryContext(patch);
  const merged = sanitizeCarryContext(previous);
  let contradicted = false;

  for (const node of CARRY_NODES) {
    const id = idField(node);
    const incomingId = incoming[id];
    // Rule 2: the patch describes this record but does not link it.
    const namedWithoutLink = !incomingId && CARRY_NODE_FIELDS[node].some((field) => field !== id && incoming[field]);
    if (incomingId ? incomingId === merged[id] : !(namedWithoutLink && merged[id])) continue;
    // Rule 1: this record, and everything hanging off it, described somebody else.
    for (const stale of [node, ...carryDescendants(node)]) clearNode(merged, stale);
    merged[id] = incomingId;
    contradicted = true;
  }

  for (const field of CARRY_FIELDS) {
    if (incoming[field]) merged[field] = incoming[field];
  }

  if (contradicted) {
    // Rule 3. "Mentions" is any field, not just the id: a patch that gives a craft NAME has told us
    // which craft it means, even where the record it came from carries no craft link.
    const origin = narrowestCarryNode(incoming);
    for (let parent = origin ? CARRY_PARENT[origin] : null; parent; parent = CARRY_PARENT[parent]) {
      if (CARRY_NODE_FIELDS[parent].some((field) => incoming[field])) break;
      clearNode(merged, parent);
    }
  }

  return merged;
}

export function readCarryContext(userId: string | null | undefined, now = Date.now()): StoredCarryContext | null {
  if (!userId || typeof window === "undefined") return null;
  let raw: string | null = null;
  try {
    raw = window.localStorage.getItem(carryContextStorageKey(userId));
  } catch {
    // Locked-down storage sandbox — the feature simply does not apply here.
    return null;
  }
  if (!raw) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    clearCarryContext(userId);
    return null;
  }
  const row = parsed as Partial<StoredCarryContext> | null;
  const savedAt = typeof row?.savedAt === "number" ? row.savedAt : 0;
  // A row belonging to somebody else, or one older than a sitting, is discarded on sight rather
  // than left to be re-checked by the next reader.
  if (row?.userId !== userId || !savedAt || now - savedAt > CARRY_CONTEXT_TTL_MS) {
    clearCarryContext(userId);
    return null;
  }
  const context = sanitizeCarryContext(row);
  if (isEmptyCarryContext(context)) {
    clearCarryContext(userId);
    return null;
  }
  // Rows written before the bag grew past the artisan have no origin; the narrowest record they do
  // name is exactly what the write was about, so nothing needs migrating.
  const originNode = row.originNode && CARRY_NODES.includes(row.originNode) ? row.originNode : narrowestCarryNode(context);
  return { ...context, savedAt, userId, originNode };
}

export function clearCarryContext(userId: string | null | undefined): void {
  if (!userId || typeof window === "undefined") return;
  try {
    window.localStorage.removeItem(carryContextStorageKey(userId));
  } catch {
    /* nothing to do — the row will expire anyway */
  }
}

function writeCarryContext(userId: string, context: CarryContext, originNode: CarryNode | null, savedAt: number): void {
  const row: StoredCarryContext = { ...context, savedAt, userId, originNode };
  try {
    window.localStorage.setItem(carryContextStorageKey(userId), JSON.stringify(row));
  } catch {
    // Private mode or quota — the prefill just does not survive this page, which is the old
    // behaviour and never a reason to fail a save.
  }
}

/**
 * Record the context the researcher is working in. The patch is a claim about ONE record and its
 * chain of parents; [mergeCarryContext] decides what of the previous context can survive it.
 */
export function rememberCarryContext(
  userId: string | null | undefined,
  patch: Partial<CarryContext>,
  now = Date.now()
): void {
  if (!userId || typeof window === "undefined") return;
  const previous = readCarryContext(userId, now);
  const merged = mergeCarryContext(previous, patch);
  if (isEmptyCarryContext(merged)) return;
  const origin = narrowestCarryNode(sanitizeCarryContext(patch)) ?? previous?.originNode ?? narrowestCarryNode(merged);
  writeCarryContext(userId, merged, origin, now);
}

/**
 * Forget one record — used when the form that would have prefilled it discovers the researcher can
 * no longer reach it (deleted, or in a workshop whose access lapsed). Its children go with it: they
 * were only ever reachable through it.
 */
export function pruneStoredCarryNode(userId: string | null | undefined, node: CarryNode, now = Date.now()): void {
  if (!userId || typeof window === "undefined") return;
  const stored = readCarryContext(userId, now);
  if (!stored || !hasCarryNode(stored, node)) return;
  const pruned = pruneCarryNode(stored, node);
  if (isEmptyCarryContext(pruned)) {
    clearCarryContext(userId);
    return;
  }
  const origin = stored.originNode && hasCarryNode(pruned, stored.originNode) ? stored.originNode : narrowestCarryNode(pruned);
  // The saved-at stamp is left alone: pruning is our bookkeeping, not the researcher doing work, and
  // refreshing it would quietly extend a sitting that ended hours ago.
  writeCarryContext(userId, pruned, origin, stored.savedAt);
}

/** Build a context from the query string CarryForwardCards writes. */
export function carryContextFromParams(params: URLSearchParams | null): CarryContext {
  if (!params) return { ...EMPTY };
  const raw: Record<string, string | null> = {};
  for (const field of CARRY_FIELDS) raw[field] = params.get(field);
  return sanitizeCarryContext(raw);
}

/** The other direction: the params a handoff link carries, in ladder order so the URL reads sanely. */
export function carryContextToParams(context: Partial<CarryContext>): URLSearchParams {
  const params = new URLSearchParams();
  const clean = sanitizeCarryContext(context);
  for (const field of CARRY_FIELDS) {
    const value = clean[field];
    if (value) params.set(field, value);
  }
  return params;
}

/**
 * How long ago, in the words a person would use. Kept coarse on purpose — the researcher needs to
 * know whether this is the artisan from five minutes ago or from before lunch, not the minute.
 *
 * Shared verbatim with Android's `CarryContextStore.describeCarryAge` so the same banner reads the
 * same on a phone and a laptop.
 */
export function describeCarryAge(savedAt: number, now = Date.now()): string {
  const minutes = Math.floor(Math.max(0, now - savedAt) / 60000);
  if (minutes < 1) return "just now";
  if (minutes === 1) return "1 minute ago";
  if (minutes < 60) return `${minutes} minutes ago`;
  const hours = Math.floor(minutes / 60);
  return hours === 1 ? "1 hour ago" : `${hours} hours ago`;
}

/** One row of the banner's chain: "Product · Jajam". */
export type CarryChainItem = { node: CarryNode; label: string; value: string };

/**
 * Everything the context holds, ancestor-first and each one labelled by what it IS.
 *
 * This is the visible half of the precedence rules. A prefill the researcher does not read is how a
 * product gets attached to the wrong artisan, and a bag that quietly implies parents ("this product,
 * so of course this artisan and this craft") is exactly that hazard with more moving parts. So every
 * record in the bag is named on screen with its type, implied or chosen: nothing is assumed silently.
 */
export function carryChainItems(context: CarryContext): CarryChainItem[] {
  const items: CarryChainItem[] = [];
  for (const node of CARRY_NODES) {
    const label = CARRY_NODE_LABEL[node];
    const value = context[`${node}Name` as keyof CarryContext];
    if (!value) continue;
    items.push({ node, label: label.charAt(0).toUpperCase() + label.slice(1), value });
  }
  return items;
}

/** The parents this record dragged along with it, named: "its artisan and craft". */
export function describeCarryImplied(context: CarryContext, origin: CarryNode | null): string {
  if (!origin) return "";
  const parents: string[] = [];
  for (let parent = CARRY_PARENT[origin]; parent; parent = CARRY_PARENT[parent]) {
    if (hasCarryNode(context, parent)) parents.push(CARRY_NODE_LABEL[parent]);
  }
  if (parents.length === 0) return "";
  const list = parents.length === 1 ? parents[0] : `${parents.slice(0, -1).join(", ")} and ${parents[parents.length - 1]}`;
  return `Its ${list} came with it.`;
}

/** "Bagru · Hand Block Printing · Bagru Workshop" — the supporting line under the artisan's name. */
export function describeCarryDetail(context: CarryContext): string {
  // Middle dot, matching every other record label on both clients.
  return [context.place, context.craftName, context.workshopName].filter(Boolean).join(" · ");
}
