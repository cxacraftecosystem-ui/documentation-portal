import type { ToolDocumentation } from "@/lib/types";

type ToolNames = Pick<ToolDocumentation, "artisanId" | "artisanName" | "artisanLinks">;

export function toolArtisanNames(tool: ToolNames): string | null {
  const links = tool.artisanLinks ?? [];
  const linked = new Set(links.flatMap((link) => (link.artisan?.id ? [link.artisan.id] : [])));
  const names: string[] = [];
  const primary = (tool.artisanName ?? "").trim();
  if (primary && (!tool.artisanId || !linked.has(tool.artisanId))) names.push(primary);
  for (const link of links) {
    const name = (link.artisan?.name ?? "").trim();
    if (name && !names.includes(name)) names.push(name);
  }
  return names.length ? names.join(", ") : null;
}

export function toolRowLabel(tool: ToolNames & Pick<ToolDocumentation, "toolkitName">): string {
  const names = toolArtisanNames(tool);
  return names ? `${tool.toolkitName} · ${names}` : tool.toolkitName;
}
