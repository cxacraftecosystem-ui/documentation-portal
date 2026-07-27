/**
 * The buti — the carved motif this page prints with.
 *
 * A block print IS a tiling pattern, and this is the one form the landing page repeats. It is
 * derived from the house mark's GEOMETRY, deliberately not from the mark itself: an eight-point
 * rosette with alternating long (axial) and short (diagonal) points, the logo's centre disc
 * REMOVED, and four small dots punched at the diagonals. That keeps the masthead and the cloth in
 * one family while making it impossible to mistake this for the logo — recolouring the real
 * FieldRepoLogo into wallpaper would be a design-system violation. FieldRepoLogo keeps its
 * terracotta star, its near-black disc and its cream tile, and stays in the masthead and footer.
 *
 * WHY THE PROPORTIONS ARE WHAT THEY ARE. The first cut of this motif used thin spikes (valley
 * radius 4.3 against a point radius of 11) and four floating satellite dots, and on screen it read
 * as a snowflake — a sparkle, the most generic decorative mark there is. Fattening the petals until
 * the valley sits at 6.6 and pulling the diagonal points out to 9.6 turns the same construction
 * into a rosette, which is what a carved buti actually looks like. The four dots became PUNCHED
 * HOLES near the centre rather than glints floating outside it, which is both how a block is
 * carved and the clearest possible separation from the logo's one solid disc.
 *
 * The motif carries no colour of its own: callers pass a fill. It is white at low alpha on the
 * dark bands, never terracotta and never an action colour.
 *
 * Geometry is a plain 0 0 24 24 box centred on (12, 12):
 *   long points   r = 11.0  at N / E / S / W
 *   short points  r =  9.6  at the diagonals
 *   valleys       r =  6.6  between every pair
 *   punched holes r =  0.9  at r = 3.1 on each diagonal
 */

export const BUTI_VIEWBOX = "0 0 24 24";

/**
 * One path: sixteen vertices walked clockwise from due north, then four counter-drawn circles.
 *
 * The holes are subpaths of the same `d` rather than separate <circle> elements because that is
 * what lets `fill-rule: evenodd` cut them out of the petal body. Written as a literal rather than
 * generated at runtime so the identical string reaches the server HTML and the client, and so it
 * can be embedded in a data URI with no build step.
 */
export const BUTI_PATH =
  "M12 1L14.53 5.9L18.79 5.21L18.1 9.47L23 12L18.1 14.53L18.79 18.79L14.53 18.1" +
  "L12 23L9.47 18.1L5.21 18.79L5.9 14.53L1 12L5.9 9.47L5.21 5.21L9.47 5.9Z" +
  "M13.29 9.81a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0-1.8 0Z" +
  "M13.29 14.19a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0-1.8 0Z" +
  "M8.91 14.19a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0-1.8 0Z" +
  "M8.91 9.81a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0-1.8 0Z";

/** Required for the four dots to read as holes rather than as overlapping fill. */
export const BUTI_FILL_RULE = "evenodd" as const;

/**
 * The four impressions inside one repeat tile, each set down at its own angle and weight.
 *
 * This is what stops the CSS-tiled states from reading as wallpaper. A single-motif
 * `background-repeat` is a machine-perfect grid — the exact thing a hand block print is not — so
 * the misregistration is baked into the tile itself: four impressions, four rotations, four
 * scales. The repeat period is 96px carrying four marks rather than 48px carrying one, which is
 * long enough that the eye reads texture instead of a lattice.
 */
const TILE_MARKS: ReadonlyArray<{ cx: number; cy: number; rotate: number; scale: number }> = [
  { cx: 24, cy: 24, rotate: 2.6, scale: 0.94 },
  { cx: 72, cy: 24, rotate: -3.4, scale: 0.88 },
  { cx: 24, cy: 72, rotate: -1.8, scale: 0.91 },
  { cx: 72, cy: 72, rotate: 4.1, scale: 0.96 }
];

function butiGroup(mark: (typeof TILE_MARKS)[number]): string {
  // 24-unit motif into a 48px cell = scale 2, times this impression's own weight.
  const s = (mark.scale * 2).toFixed(3);
  return (
    `<g transform="translate(${mark.cx} ${mark.cy}) rotate(${mark.rotate}) scale(${s}) translate(-12 -12)">` +
    `<path d="${BUTI_PATH}"/></g>`
  );
}

/**
 * The repeat tile as a CSS `url()` value: a 96px square carrying four misregistered impressions.
 *
 * Used for the two STATIC states of the cloth — the hero's bare ground and the closing band's
 * finished length. The middle state, the printing bed, does not use this: it stamps 72 individual
 * impressions so each can be misregistered on its own and lit in sequence. Opacity is set by the
 * element rather than by the fill, so one tile serves both densities.
 */
export function butiTileUrl(fill = "#ffffff"): string {
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 96 96">` +
    `<g fill="${fill}" fill-rule="${BUTI_FILL_RULE}">${TILE_MARKS.map(butiGroup).join("")}</g></svg>`;
  return `url("data:image/svg+xml,${encodeURIComponent(svg)}")`;
}
