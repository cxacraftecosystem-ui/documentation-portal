export const CARRY_NODES = ["workshop", "craft", "artisan", "product", "tool", "process"] as const;

export type CarryNode = (typeof CARRY_NODES)[number];

export const CARRY_PARENT: Record<CarryNode, CarryNode | null> = {
  workshop: null,
  craft: null,
  artisan: "craft",
  product: "artisan",
  tool: "artisan",
  process: "product"
};

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

const CARRY_NODE_FIELDS: Record<CarryNode, readonly (keyof CarryContext)[]> = {
  workshop: ["workshopId", "workshopName"],
  craft: ["craftId", "craftName"],
  artisan: ["artisanId", "artisanName", "place"],
  product: ["productId", "productName"],
  tool: ["toolId", "toolName"],
  process: ["processId", "processName"]
};

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

export function carryDescendants(node: CarryNode): CarryNode[] {
  return CARRY_NODES.filter((candidate) => {
    for (let parent = CARRY_PARENT[candidate]; parent; parent = CARRY_PARENT[parent]) {
      if (parent === node) return true;
    }
    return false;
  });
}

export type StoredCarryContext = CarryContext & {
  savedAt: number;
  userId: string;
  originNode: CarryNode | null;
};

export const CARRY_CONTEXT_TTL_MS = 12 * 60 * 60 * 1000;

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

function cleanText(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

export function sanitizeCarryContext(value: unknown): CarryContext {
  const clean = { ...EMPTY };
  if (!value || typeof value !== "object") return clean;
  const raw = value as Record<string, unknown>;
  for (const field of CARRY_FIELDS) clean[field] = cleanText(raw[field]);
  return clean;
}

export function isEmptyCarryContext(context: CarryContext | null): boolean {
  if (!context) return true;
  return !CARRY_NODES.some((node) => node !== "workshop" && CARRY_NODE_FIELDS[node].some((field) => context[field]));
}

export function hasCarryNode(context: CarryContext | null, node: CarryNode): boolean {
  return Boolean(context && CARRY_NODE_FIELDS[node].some((field) => context[field]));
}

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

export function selectCarryNodes(context: CarryContext, nodes: readonly CarryNode[]): CarryContext {
  const next = sanitizeCarryContext(context);
  for (const node of CARRY_NODES) {
    if (!nodes.includes(node)) clearNode(next, node);
  }
  return next;
}

export function pruneCarryNode(context: CarryContext, node: CarryNode): CarryContext {
  const next = sanitizeCarryContext(context);
  for (const dead of [node, ...carryDescendants(node)]) clearNode(next, dead);
  return next;
}

export function mergeCarryContext(previous: CarryContext | null, patch: Partial<CarryContext>): CarryContext {
  const incoming = sanitizeCarryContext(patch);
  const merged = sanitizeCarryContext(previous);
  let contradicted = false;

  for (const node of CARRY_NODES) {
    const id = idField(node);
    const incomingId = incoming[id];
    const namedWithoutLink = !incomingId && CARRY_NODE_FIELDS[node].some((field) => field !== id && incoming[field]);
    if (incomingId ? incomingId === merged[id] : !(namedWithoutLink && merged[id])) continue;
    for (const stale of [node, ...carryDescendants(node)]) clearNode(merged, stale);
    merged[id] = incomingId;
    contradicted = true;
  }

  for (const field of CARRY_FIELDS) {
    if (incoming[field]) merged[field] = incoming[field];
  }

  if (contradicted) {
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
  if (row?.userId !== userId || !savedAt || now - savedAt > CARRY_CONTEXT_TTL_MS) {
    clearCarryContext(userId);
    return null;
  }
  const context = sanitizeCarryContext(row);
  if (isEmptyCarryContext(context)) {
    clearCarryContext(userId);
    return null;
  }
  const originNode = row.originNode && CARRY_NODES.includes(row.originNode) ? row.originNode : narrowestCarryNode(context);
  return { ...context, savedAt, userId, originNode };
}

export function clearCarryContext(userId: string | null | undefined): void {
  if (!userId || typeof window === "undefined") return;
  try {
    window.localStorage.removeItem(carryContextStorageKey(userId));
  } catch {
  }
}

function writeCarryContext(userId: string, context: CarryContext, originNode: CarryNode | null, savedAt: number): void {
  const row: StoredCarryContext = { ...context, savedAt, userId, originNode };
  try {
    window.localStorage.setItem(carryContextStorageKey(userId), JSON.stringify(row));
  } catch {
  }
}

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
  writeCarryContext(userId, pruned, origin, stored.savedAt);
}

export function carryContextFromParams(params: URLSearchParams | null): CarryContext {
  if (!params) return { ...EMPTY };
  const raw: Record<string, string | null> = {};
  for (const field of CARRY_FIELDS) raw[field] = params.get(field);
  return sanitizeCarryContext(raw);
}

export function carryContextToParams(context: Partial<CarryContext>): URLSearchParams {
  const params = new URLSearchParams();
  const clean = sanitizeCarryContext(context);
  for (const field of CARRY_FIELDS) {
    const value = clean[field];
    if (value) params.set(field, value);
  }
  return params;
}

export function describeCarryAge(savedAt: number, now = Date.now()): string {
  const minutes = Math.floor(Math.max(0, now - savedAt) / 60000);
  if (minutes < 1) return "just now";
  if (minutes === 1) return "1 minute ago";
  if (minutes < 60) return `${minutes} minutes ago`;
  const hours = Math.floor(minutes / 60);
  return hours === 1 ? "1 hour ago" : `${hours} hours ago`;
}

export type CarryChainItem = { node: CarryNode; label: string; value: string };

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

export function describeCarryDetail(context: CarryContext): string {
  return [context.place, context.craftName, context.workshopName].filter(Boolean).join(" · ");
}
