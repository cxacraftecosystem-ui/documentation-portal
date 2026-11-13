export type WriteMethod = "POST" | "PATCH" | "PUT";

export const DERIVED_NAME_SOURCES: Readonly<Record<string, readonly string[]>> = {
  craftName: ["craftId", "craftIds"],
  artisanName: ["artisanId", "artisanIds"],
  place: ["artisanId"]
};

export function isBlankText(value: unknown): value is string {
  return typeof value === "string" && value.trim() === "";
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function hasLinkedId(body: Record<string, unknown>, keys: readonly string[]): boolean {
  return keys.some((key) => {
    const value = body[key];
    if (typeof value === "string") return value.trim() !== "";
    return Array.isArray(value) && value.some((item) => typeof item === "string" && item.trim() !== "");
  });
}

export function withoutBlankText<T>(value: T): T {
  if (!isPlainObject(value)) return value;
  const kept: Record<string, unknown> = {};
  for (const [key, item] of Object.entries(value)) {
    if (isBlankText(item)) continue;
    kept[key] = isPlainObject(item) ? withoutBlankText(item) : item;
  }
  return kept as T;
}

export function writeBody<T>(body: T, method: WriteMethod): T {
  if (!isPlainObject(body)) return body;
  const cleaned: Record<string, unknown> = method === "POST" ? withoutBlankText(body) : { ...body };
  for (const [name, sources] of Object.entries(DERIVED_NAME_SOURCES)) {
    const value = cleaned[name];
    if (name in cleaned && (value === null || value === undefined || isBlankText(value)) && hasLinkedId(cleaned, sources)) {
      delete cleaned[name];
    }
  }
  return cleaned as T;
}
