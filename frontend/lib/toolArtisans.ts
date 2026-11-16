import type { ToolDocumentation } from "@/lib/types";

type ToolNames = {
  artisanId?: string | null;
  artisanName?: string | null;
  artisanLinks?: { artisan?: { id: string; name?: string | null } | null }[] | null;
};

export function toolArtisanNames(tool: ToolNames): string | null {
  const links = tool.artisanLinks ?? [];
  const head = tool.artisanId ? links.find((link) => link.artisan?.id === tool.artisanId)?.artisan : undefined;
  const names: string[] = [];
  for (const raw of [head?.name || tool.artisanName, ...links.map((link) => link.artisan?.name)]) {
    const name = (raw ?? "").trim();
    if (name && !names.includes(name)) names.push(name);
  }
  return names.length ? names.join(", ") : null;
}

export function toolRowLabel(tool: ToolNames & Pick<ToolDocumentation, "toolkitName">): string {
  const names = toolArtisanNames(tool);
  return names ? `${tool.toolkitName} · ${names}` : tool.toolkitName;
}

export function toolOwnArtisans<A extends { id: string }>(tool: { artisan?: A | null; artisanLinks?: { artisan?: A | null }[] | null } | null | undefined): A[] {
  const own: A[] = [];
  for (const artisan of [tool?.artisan, ...(tool?.artisanLinks ?? []).map((link) => link.artisan)]) {
    if (artisan?.id && !own.some((known) => known.id === artisan.id)) own.push(artisan);
  }
  return own;
}

export function withOwnArtisans<A extends { id: string }>(offered: A[], own: A[]): A[] {
  const missing = own.filter((artisan) => !offered.some((known) => known.id === artisan.id));
  return missing.length ? [...offered, ...missing] : offered;
}
