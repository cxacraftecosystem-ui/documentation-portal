export function tableCell(value, limit = Infinity) {
  const text = String(value ?? "").replace(/\s+/g, " ").slice(0, limit);
  return text.replace(/\\/g, "\\\\").replace(/\|/g, "\\|").replace(/`/g, "\\`");
}
