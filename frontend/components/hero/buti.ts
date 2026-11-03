export const BUTI_VIEWBOX = "0 0 24 24";

export const BUTI_PATH =
  "M12 1L14.53 5.9L18.79 5.21L18.1 9.47L23 12L18.1 14.53L18.79 18.79L14.53 18.1" +
  "L12 23L9.47 18.1L5.21 18.79L5.9 14.53L1 12L5.9 9.47L5.21 5.21L9.47 5.9Z" +
  "M13.29 9.81a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0-1.8 0Z" +
  "M13.29 14.19a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0-1.8 0Z" +
  "M8.91 14.19a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0-1.8 0Z" +
  "M8.91 9.81a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0-1.8 0Z";

export const BUTI_FILL_RULE = "evenodd" as const;

const TILE_MARKS: ReadonlyArray<{ cx: number; cy: number; rotate: number; scale: number }> = [
  { cx: 24, cy: 24, rotate: 2.6, scale: 0.94 },
  { cx: 72, cy: 24, rotate: -3.4, scale: 0.88 },
  { cx: 24, cy: 72, rotate: -1.8, scale: 0.91 },
  { cx: 72, cy: 72, rotate: 4.1, scale: 0.96 }
];

function butiGroup(mark: (typeof TILE_MARKS)[number]): string {
  const s = (mark.scale * 2).toFixed(3);
  return (
    `<g transform="translate(${mark.cx} ${mark.cy}) rotate(${mark.rotate}) scale(${s}) translate(-12 -12)">` +
    `<path d="${BUTI_PATH}"/></g>`
  );
}

export function butiSelvedgeUrl(): string {
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="${BUTI_VIEWBOX}">` +
    `<path fill="#000" fill-rule="${BUTI_FILL_RULE}" d="${BUTI_PATH}"/></svg>`;
  return `url("data:image/svg+xml,${encodeURIComponent(svg)}")`;
}

export function butiTileUrl(fill = "#ffffff"): string {
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 96 96">` +
    `<g fill="${fill}" fill-rule="${BUTI_FILL_RULE}">${TILE_MARKS.map(butiGroup).join("")}</g></svg>`;
  return `url("data:image/svg+xml,${encodeURIComponent(svg)}")`;
}
