/**
 * The outline of India, as recognised by the Government of India.
 *
 * ════════ DO NOT REPLACE THIS PATH WITHOUT REPEATING THE VERIFICATION BELOW ════════
 *
 * Depicting India's boundary any other way is a legal and reputational problem in India, not a
 * stylistic choice. The official depiction shows as Indian territory the WHOLE of the former state
 * of Jammu & Kashmir — including the Pakistan-administered areas and Gilgit-Baltistan — together
 * with Ladakh and AKSAI CHIN, and the whole of ARUNACHAL PRADESH.
 *
 * Almost every convenient boundary dataset gets this wrong. Natural Earth, `world-atlas`, most
 * OpenStreetMap extracts and the majority of npm map packages draw the Line of Control as a
 * boundary, put Aksai Chin in China, or mark Arunachal Pradesh disputed. Reaching for the nearest
 * TopoJSON country file is the single likeliest way this page ends up wrong. A hand-traced outline
 * is no safer: the J&K lobe is exactly the part a forty-point tracing loses.
 *
 * SOURCE
 *   DataMeet India community — "maps", `Country/india-composite.geojson`
 *   https://github.com/datameet/maps/blob/master/Country/india-composite.geojson
 *   Described by its own README as the land area of India including disputed territories "in
 *   accordance with the Official boundary of India as per the Survey of India", assembled from
 *   US Department of State LSIB (India, Aksai Chin and the disputed areas with China), the
 *   Alhasan Systems Pakistan admin boundaries (Pakistan-occupied Kashmir) and Natural Earth's
 *   disputed-areas layer (the Shaksgam Valley), dissolved into one polygon set.
 *
 * LICENCE
 *   The dataset's own README states CC-0 for this file; the repository README states CC BY 4.0
 *   for anything not explicitly licensed. Attribution costs nothing and satisfies both readings,
 *   so it is rendered on the page — see ATTRIBUTION below, which is displayed as visible text
 *   beside the map and must not be removed.
 *
 * VERIFICATION — measured, not assumed
 *   Checks were run twice: once on the 252,604-point source, and again on the 1321-point
 *   simplification below, because simplification is itself a way to lose Aksai Chin. A third pass
 *   re-ran them on the rounded, projected coordinates that actually ship. Ray-cast
 *   point-in-polygon, holes respected.
 *
 *     bounding box    lon [68.1644, 97.3954]   lat [6.7529, 37.0976]
 *     northern extent 37.0976 N — Gilgit-Baltistan. An LoC-drawn dataset stops near 35.5 N,
 *                     so this number alone distinguishes the two.
 *     Aksai Chin      35.1 N, 79.4 E   INSIDE
 *     Arunachal       28.2 N, 94.7 E   INSIDE
 *     also inside     Gilgit 35.9/74.3 · Muzaffarabad 34.36/73.47 · Siachen 35.4/76.9 ·
 *                     Tawang 27.59/91.87 · Kanyakumari 8.15/77.54
 *     correctly out   Lahore · Kathmandu · Dhaka · Lhasa · Colombo · Kabul
 *
 * WHAT WAS SIMPLIFIED AWAY
 *   Douglas-Peucker at 0.05° for the mainland, but the tolerance is scaled to each polygon's own
 *   size — a single global tolerance would delete Lakshadweep and the smaller Nicobars outright,
 *   and territory is not allowed to vanish because of a knob. All 80 polygons in the source
 *   survive. The smallest atolls are under a pixel at any size this page renders; they are in the
 *   path, not dropped from it.
 *
 * PROJECTION
 *   Web Mercator, normalised so the bounding box above fills `0 0 100 113.68`. Site marks are
 *   projected with the identical transform, so a mark can never drift from the coastline it sits on.
 *
 * SIZE
 *   11,181 characters of path data, 1321 points. Inline, so there is no runtime map
 *   dependency, no tiles, and no network request for the page's signature graphic.
 */

/** Visible credit. Rendered beside the map; removing it breaks the CC BY reading of the licence. */
export const INDIA_OUTLINE_ATTRIBUTION = {
  text: "Boundary: DataMeet India community",
  href: "https://github.com/datameet/maps/blob/master/Country/india-composite.geojson",
  licence: "CC BY 4.0",
  licenceHref: "https://creativecommons.org/licenses/by/4.0/"
} as const;

export const INDIA_VIEWBOX = "0 0 100 113.68";
export const INDIA_WIDTH = 100;
export const INDIA_HEIGHT = 113.68;

export const INDIA_PATH =
  "M32 6.8L32.6 7L33 6.7L33.3 6.9L33.4 6.5L34.2 6.6L34.5 5.8L36.4 5.2L36.6 4.8L37.2 5L38.2 4.7L38.6" +
  " 4.8L38.7 5.3L39 5.1L39.5 6.2L40.5 6.4L40.7 7.1L41.2 6.5L41.9 6.9L41.5 7.4L41.2 9.3L40.7 10.1L39" +
  ".8 10.4L39.8 11L38.8 11.2L39.1 12L38.7 12.2L38.4 13L37.1 12.9L36.7 13.1L37.4 14.5L36.8 14.6L36.9" +
  " 15.6L37.4 16.3L38.5 16.4L38.2 17.1L39 18.1L38.9 18.4L38.5 19.1L38 18.9L37 19.8L36.3 19.2L36.2 1" +
  "8.4L35 19L35.3 20.1L36.3 21.2L36.1 22L36.5 22.8L36.1 23.2L36.4 23.4L36.3 24L36.7 24L37.4 23.4L38" +
  ".5 25.1L39.1 25.4L40 25.3L41.3 26.1L41.2 26.9L44 28.2L43.9 28.4L43.6 28.3L43 29.1L41.7 30.2L41.9" +
  " 30.8L41.3 31.4L41.5 32.3L41 32.7L40.7 33.8L42.3 34.8L42.2 34.4L42.4 34.3L44.6 35.6L45 36.5L45.4" +
  " 36.4L46.9 37.5L47.6 37.3L48.9 38.2L49.8 38.1L49.8 38.9L51.4 39.1L51.8 39.6L52.1 39L52.9 39L53.7" +
  " 39.5L53.7 39.1L54.3 39.2L54.7 38.9L55.2 39.4L56.3 39.5L56.5 40L56.4 40.7L57.5 41L57.7 41.4L58.2" +
  " 41.3L58.3 41.8L59.7 41.3L60.5 42.5L61.1 42.1L62.2 42.3L63.5 43.1L64.7 42.4L64.8 43L65.6 43.3L66" +
  " 43L66.5 43.2L67.5 42.8L67.9 43.3L68.5 41.9L68.3 40.9L67.8 40.4L68 38.9L68.5 37.8L68.3 37.3L70 3" +
  "6.5L70.7 36.9L70.9 37.5L70.5 38.7L71 39.7L70.4 40.3L70.8 40.4L70.9 41.1L71 40.9L71.7 41.6L72.6 4" +
  "1.4L73.5 41.7L73.4 41.9L74.2 42L75.4 41.7L75.9 41.2L77.2 41.7L80.5 41.6L81.2 41.2L81.7 41.4L82 4" +
  "1L81.6 40.5L81.7 39.9L82 39.7L81.6 39L80.4 39L80 38.4L80.3 37.9L81.3 38.1L82.4 37.4L82.6 37.8L83" +
  ".1 37.8L84 37.1L83.9 36.4L84.7 36.2L86.4 34.4L87.4 34.4L89.3 33.4L89.6 33L89.4 32.5L90.5 31.9L90" +
  ".8 31.9L91.1 32.5L92.7 32.8L93.3 32.3L93.5 32.6L93.9 32.2L94.2 32.3L94.6 31.7L95.4 31.6L96.3 32." +
  "3L95.9 33L96.4 32.7L97.4 34.1L96.6 35L96.9 35.3L96.9 34.9L97.7 34.6L98.4 35.6L99.1 35.6L99.9 36." +
  "2L99.7 36.8L100 36.9L99.9 37.5L99.5 37.4L98.3 38.5L98.3 39.1L99.1 40.5L98.2 40.1L98.3 39.8L97.6 " +
  "39.4L96 39.8L93.3 42L92.3 42.3L92 43L92.3 43.2L92.2 44.3L92.4 44.4L91.8 45.1L92 45.6L91.5 46.3L9" +
  "0.6 47L90.4 47.7L90.9 48L90.8 48.7L89.7 50.4L88.9 52.8L87.7 52.5L87.5 52.2L86.7 52.4L86.1 51.9L8" +
  "6.5 53.4L86.2 54.6L86.3 55.5L86 55.9L85.4 55.8L85.3 57.7L85.7 58.7L85.5 59L85.1 58.9L85 59.7L84." +
  "8 59.5L84.6 59.9L84.5 59.4L83.9 59.1L83.9 59.6L83.6 59.7L83.3 57L82.8 56.2L82.7 55.1L82.9 55.1L8" +
  "2.5 53.3L82.3 53.5L82 53.2L81.7 53.5L81.4 53.2L81.4 54.2L80.7 54.8L81 55.6L80.2 56.2L79.5 54.9L7" +
  "9.5 55.7L79.3 55.6L79.2 54.6L78.7 53.7L78.9 52.5L79.4 52.3L79.4 51.8L80.1 52L80.4 51.3L80.7 51.7" +
  "L80.7 51.3L81.2 51.7L81.3 51L82.1 50.7L82.6 49.5L82.3 48.8L83.2 48.9L83 48.3L81.8 47.8L80.3 48L7" +
  "9 47.7L76.2 47.9L74.1 47.4L74.3 46.1L74.1 45.4L74.3 44.9L74.1 44.9L74.3 44.7L73.6 43.8L73.3 44.1" +
  "L73.5 44.4L73.3 44.8L72.7 44.5L72.5 44.6L71.8 44.1L71.6 43.2L71.1 42.9L71 43.1L71.5 43.8L70.9 43" +
  ".6L70.7 43.8L70.6 43.5L70.1 43.7L70.4 43.3L69.2 42.3L69 42.8L69.5 42.9L69.7 43.3L68.5 44.1L68.2 " +
  "45.4L68.8 45.4L69.7 46.5L70.6 46.5L70.7 47.1L71.3 47.5L71 47.8L69.4 47.7L69.2 48.7L69 49L68.6 48" +
  ".6L68.3 48.7L68.5 49L67.9 49.7L69 50.8L70.4 51.2L70.2 51.8L70.5 52.3L69.8 52.7L69.8 53.5L70.6 54" +
  ".1L70.3 55L71.3 55.2L70.7 55.9L71.2 56.5L71.1 57.6L71.6 59.1L71.5 59.9L71.3 59.7L71.2 60L71.6 61" +
  "L71 61L70.8 60.5L70.8 61.1L70.3 60.8L70.3 60.2L70.5 59.6L70 59.4L69.8 60.3L69.6 59.8L69.4 60L69." +
  "4 61.1L69.3 60.7L69.2 61.2L69.2 60.7L68.8 60.6L68.9 61.2L68.7 61.3L68.4 59.8L68.6 59.1L67.9 58.8" +
  "L68.5 59.3L67.2 60.8L64.8 61.4L64.1 62.1L63.8 62.8L64.4 64L64 64.2L64.4 64.2L64.3 64.5L64.7 64.4" +
  "L63.5 65L63.4 65.5L63.7 65.6L63.3 65.9L63.7 65.7L62.8 66.3L62.5 67L62.1 67L62.3 67.2L59.4 68.1L5" +
  "9.6 68.1L57.7 69.2L54.6 73.1L52.7 74.1L51.5 75.7L49.4 76.8L48.4 77.7L48.2 78.3L48.6 78.4L48.5 77" +
  ".8L48.6 78.1L48.5 78.8L48.3 78.9L48.6 78.8L48.4 79.2L48.2 78.9L48.4 79.4L46.4 80.3L46.4 80.1L46." +
  "3 80.3L45.8 80.1L44.8 80.3L44.4 81.5L43.7 82.4L43.3 82.4L43.2 81.9L42.8 81.8L41.4 82.5L40.7 84.7" +
  "L41.1 86.4L41 86.5L41.2 86.4L40.9 88.2L41.7 91L41 93.9L40.1 95.3L39.7 96.6L39.9 97.7L39.7 97.7L3" +
  "9.9 97.7L40 98.3L40.1 101.4L39.8 101.5L39.3 101.2L38.1 101.5L38 102.3L36.7 104.2L36.9 104.7L37.7" +
  " 105L36.6 105.1L34.6 105.9L34.1 106.7L34.3 106.7L33.9 108.1L32.1 109.1L31.3 109L30.3 108.2L28.7 " +
  "106.3L29.1 105.9L28.6 106.2L28.5 105.3L28 104.7L27.7 102.3L27.6 102.5L26.7 99.6L26.5 99.7L26.4 9" +
  "8.5L25.2 96.5L24.1 95.5L22.8 92.5L22.3 90.8L22.2 89.8L22.4 89.7L22.2 89.6L21.4 87.5L21.7 87.6L21" +
  ".4 87.5L20.9 85.9L20.4 85.7L20.5 85.4L19.7 84.6L19.6 83.8L19.2 83.5L19.6 83.4L19.1 82.8L19.2 82." +
  "6L19.1 82.7L18.1 81.2L18 80.1L17.8 80L18.1 79.9L17.8 80L17.6 79.6L17.7 79.4L17.8 79.7L18.1 79.6L" +
  "17.6 79.2L17.9 79.2L17.7 79.2L17.6 78.8L17.7 77.9L17.4 77.6L17.7 77.7L17.2 76.7L17.6 76.8L17.1 7" +
  "6.4L17 75.8L17.3 75.7L17 75.6L17 74.8L16.6 74.4L16.9 74.3L16.6 74.3L16.3 73.4L16.4 73.2L16.9 73." +
  "7L16.8 73.1L16.6 73.3L16.3 73L16.2 72.3L16.6 72.5L16.1 71.7L16.3 71.2L16.6 71.6L16.5 71.1L16.2 7" +
  "1L16.8 70.5L16.5 70.6L16.5 69.9L16.4 70.5L15.9 71L16 70L15.8 70.1L16 69.7L15.8 69.9L15.8 69.5L16" +
  ".2 69.6L15.8 69.4L15.7 68.9L16.2 68.7L15.6 68.7L15.4 67.6L15.4 67.2L15.7 67.2L15.7 65.9L16.2 65." +
  "1L16 64.3L16.3 64.2L15.9 64.1L15.8 63.6L16 63.4L15.7 63.6L15.6 63.3L15.6 63.1L16 63.2L15.5 63L15" +
  ".8 62.7L15.3 63L15.3 62.6L15.7 62.6L15.2 62.2L15.5 61.6L15.7 61.7L15.3 61.7L15.4 61.5L16.3 60.9L" +
  "15 60.9L15.2 60L15.7 59.8L15 60L14.9 59.7L15.1 58.9L15.7 59L16.2 58.7L15.7 58.8L15 58.5L14.6 58." +
  "9L14.4 58.4L14.2 58.5L14.1 59.3L13.7 59.6L14 59.9L13.7 59.8L14 60L14 60.4L13.7 60.4L14.2 61L13.4" +
  " 62.2L13.5 62.6L11.2 63.8L9.1 64.5L6.8 63.1L2.6 58.5L3.1 57.9L3 58.2L3.5 58.1L3.4 58.5L3.6 58.7L" +
  "4.5 58.4L4.6 58.1L4.8 58.5L5.1 58.5L5.4 57.9L5.6 58.2L6.2 57.6L6.9 57.7L7.8 56.1L7.3 56.2L7 55.7" +
  "L7 56.1L6.7 56L6.6 56.3L5.9 56.3L5.3 56.9L4.4 56.8L4.1 56.4L4.1 56.6L3.5 56.6L1.6 55.3L1.9 55.5L" +
  "1.4 55.1L1.8 54.8L1 54.2L1 53.9L0.9 54.1L1.3 53.3L2.2 52.7L1.2 53.2L0.9 53.2L0.9 52.9L0.6 53.8L0" +
  " 53.7L0.6 53.2L0.3 53.5L0.1 53.2L0.7 52.3L2 52.3L2.2 51L2.4 51.4L2.7 51.1L2.9 51.4L4.9 51.1L5.4 " +
  "51.6L6.4 51.6L6.7 51.1L8.2 50.6L8.2 51.3L8.7 51.4L10.1 50.7L9.7 50.6L9.7 50L10 49.6L9.3 47.9L8.6" +
  " 47L8.5 45.8L7.2 45.8L6.6 44.9L6.9 42.6L5.7 42.4L4.6 41.8L4.9 40.1L6.4 38.7L6.7 37.7L7.6 36.9L8." +
  "3 37L8.8 38L9.3 38.1L12.8 37.1L12.9 36.5L13.8 35.5L14.5 34L16.4 33L17.5 30.9L17.9 29.4L19.3 28.9" +
  "L19.9 28.4L19.6 27.7L19.9 27.4L19.7 27.2L20.2 27.1L21.4 25.4L22.3 24.9L21.7 24.7L21.9 23.7L22.2 " +
  "23.5L21.6 22.3L22 21.6L22.8 21.4L23 21L24.2 20.8L24.7 20.3L24.5 19.8L23.7 19.3L22.3 19.2L22.1 18" +
  ".7L22.4 17.8L22.1 18.1L21.2 18.1L19.7 17L18.7 16.7L18.6 13.5L17.9 11.5L18.1 10.7L18.8 10.7L19 9." +
  "9L20.1 9.3L20.4 8.4L19.1 8L18.9 7.4L19.2 6.7L17.9 6.7L17.7 6.1L17 5.9L17.2 5.3L15.1 5.3L14.9 5.1" +
  "L15 3.7L16.4 2.7L16.8 1.7L17.9 1.5L19.4 1.7L19.4 1.3L18.8 0.8L20.1 1.1L21.4 0.4L21.9 0.5L22.4 0L" +
  "22.5 0.3L22.8 0.1L23 0.7L23.9 0.3L24.8 0.6L25 1.6L25.9 1.5L26.6 2.1L26.9 2.8L29.3 4L29.7 5.3L31." +
  "4 5.9L32 6.4L32 6.8ZM84.7 92.3L84.9 93.8L84.7 94.1L84.3 93.9L84.6 94.4L84.1 94.4L83.9 93.3L84.3 " +
  "93L84.1 93.1L84 92.6L84.7 92.3ZM85.1 90.1L85 90.5L85.3 90.8L84.8 90.8L85.2 91.1L85.1 91.8L84.9 9" +
  "2L84.6 91.7L84.8 92.1L84.3 92.3L84.5 90.6L84.7 90.1L85.1 90.1ZM84 94.7L84.2 94.9L84 95.3L84.3 95" +
  ".3L84 95.8L84.3 95.8L84.2 96.5L83.8 96.7L84.2 96.7L84 97.3L83.3 96L83.5 95.7L83.6 96L84 94.7ZM87" +
  ".9 112L88.3 112.8L87.8 113.7L87.2 112.4L87.9 112ZM83.4 99.4L83.7 99.8L83.5 100.5L82.9 100.6L82.9" +
  " 99.7L83.4 99.4ZM68.4 60.4L68.4 61L68 61L68.3 60.1L68.4 60.4ZM84.4 94.4L84.6 94.5L84.6 94.8L84.5" +
  " 94.8L84.2 95.3L84.1 95.2L84.2 94.7L84.1 94.6L84.2 94.4L84.4 94.4ZM86.3 109.3L86.5 109.6L86.5 10" +
  "9.8L86.4 109.7L86.1 109.8L86.2 109.6L86.1 109.6L86.1 109.4L86.2 109.3L86.3 109.3ZM87.5 111.4L87." +
  "6 111.6L87.2 112L87.2 111.5L87.4 111.5L87.4 111.3L87.5 111.4ZM86.8 108.6L86.8 108.8L86.7 108.9L8" +
  "6.9 109.3L86.7 109.4L86.7 109.2L86.8 109.2L86.8 109.1L86.7 109.2L86.5 108.8L86.8 108.6ZM84.2 105" +
  ".1L84.3 105.2L84.4 105.4L84.1 105.5L84 105.2L84.2 105.1ZM83.8 97.3L83.9 97.5L83.8 97.6L83.9 97.7" +
  "L83.6 97.8L83.6 97.6L83.7 97.6L83.7 97.3L83.8 97.2L83.8 97.3ZM84 92L84.1 92.4L83.9 92.8L83.8 92." +
  "5L83.9 92.1L84 92ZM84.9 95.4L85 95.4L85.1 95.9L85 95.7L85.1 95.7L85 95.6L84.9 95.7L84.7 95.5L84." +
  "9 95.4ZM85.4 108.2L85.5 108.2L85.4 108.4L85.7 108.6L85.7 108.7L85.3 108.5L85.3 108.2L85.4 108.2Z" +
  "M69.8 60.7L69.9 60.8L69.9 61.1L69.7 60.9L69.6 60.7L69.8 60.6L69.8 60.7ZM70.3 60.9L70.5 60.9L70.6" +
  " 61.2L70.3 61.2L70.3 61L70.2 60.8L70.2 60.7L70.3 60.9ZM70.1 60L70.1 60.1L70 60.4L69.9 60.2L70.1 " +
  "60ZM85.2 94.8L85.3 95L85.2 95.2L85.1 95L85.2 94.7L85.2 94.8ZM68.1 59.9L68 60L68.1 59.6L68.3 59.6" +
  "L68.1 59.9ZM69.7 60.3L69.8 60.4L69.8 60.6L69.6 60.7L69.6 60.5L69.7 60.3ZM86.9 109.3L87 109.4L87 " +
  "109.6L86.7 109.4L86.8 109.3L86.9 109.4L86.9 109.3ZM70.1 59.6L70.3 59.7L70.2 59.9L70 59.8L70 59.7" +
  "L70.1 59.6ZM82.3 96.9L82.5 97L82.5 97.2L82.3 97.1L82.3 96.9ZM69.7 61L69.9 61.1L69.7 61.2L69.6 61" +
  ".1L69.7 60.9L69.7 61ZM68.9 60.7L69 60.8L69.1 60.9L68.9 61.1L68.9 60.7ZM68.6 60.9L68.7 61L68.6 61" +
  ".1L68.5 60.8L68.6 60.7L68.6 60.8L68.5 60.8L68.6 60.9ZM85.1 95L85 95L85.2 95.2L85.1 95.3L85 95.2L" +
  "85 95L85.1 94.9L85.1 95ZM70.7 61.1L70.9 61.2L70.9 61.3L70.7 61.3L70.7 61.1ZM69.7 61.3L69.6 61.3L" +
  "69.5 61.3L69.5 61.1L69.7 61.2L69.7 61.3ZM84.1 94.8L84.1 94.7L84.2 94.7L84.2 94.8L84 94.8L84.1 94" +
  ".6L84.1 94.8ZM85 95.1L85 95.3L84.9 95.3L84.9 95.2L84.9 95.1L85 95.1ZM87.1 107.6L87.2 107.6L87.2 " +
  "107.8L87.1 107.8L87.1 107.9L87.1 107.4L87.1 107.5L87.1 107.6ZM87 109L87 109.1L87.1 109.2L87 109." +
  "3L87 109.2L87 109.1L86.9 109L87 108.9L87 109ZM69.9 60.2L70 60.3L69.9 60.3L70 60.4L69.9 60.4L69.8" +
  " 60.3L69.9 60.2ZM84.9 94L84.8 94.1L84.8 94.2L84.7 94.2L84.8 94L84.9 94ZM84 92.6L84 92.8L84.1 92." +
  "8L84.1 92.9L84 92.9L84 93L84 92.9L84 92.7L84 92.6ZM85 96L85.2 96.1L85 96.1L85 96ZM85 92L84.9 92." +
  "1L84.9 92.2L84.8 92.2L84.9 92.2L84.8 92.1L84.9 92.1L84.8 92.1L84.9 92L84.9 92.1L84.9 92L85 92ZM8" +
  "5.1 89.6L85.1 89.8L85 89.7L84.9 89.7L85 89.7L85.1 89.6ZM85.4 94.7L85.3 94.8L85.3 94.7L85.2 94.7L" +
  "85.3 94.6L85.3 94.7L85.3 94.6L85.4 94.6L85.4 94.7ZM84.9 95L85 95L84.9 95.1L84.8 95L84.9 95ZM84.8" +
  " 94.5L84.7 94.7L84.7 94.6L84.6 94.6L84.7 94.5L84.8 94.5L84.8 94.4L84.8 94.5ZM83.4 96.9L83.5 96.9" +
  "L83.5 97L83.4 97.1L83.4 96.9ZM84.5 94.1L84.6 94.1L84.6 94.2L84.6 94.1L84.5 94.2L84.5 94.1ZM84 91" +
  ".6L84.1 91.7L84 91.8L83.9 91.7L83.9 91.6L84 91.6ZM84 94.4L84.1 94.5L84 94.7L84 94.6L84 94.4ZM85." +
  "8 108.6L85.7 108.6L85.7 108.5L85.8 108.5L85.8 108.6ZM84.7 94.6L84.7 94.7L84.6 94.7L84.6 94.8L84." +
  "5 94.7L84.6 94.6L84.7 94.6ZM87.9 94.5L88 94.6L87.9 94.6L87.9 94.5ZM15.8 98.3L15.8 98.1L15.9 98.1" +
  "L15.8 98.3ZM0 53.2L0 53.1L0.1 53L0 53.2ZM13.8 99.4L13.8 99.5L13.7 99.5L13.7 99.4L13.8 99.4ZM89.4" +
  " 90.4L89.4 90.5L89.3 90.5L89.4 90.4ZM84.7 91.9L84.8 91.9L84.8 92L84.7 92L84.7 91.9ZM84.2 95.6L84" +
  ".2 95.7L84.1 95.7L84.1 95.6L84.2 95.6ZM16.8 108.4L16.7 108.5L16.6 108.4L16.7 108.4L16.8 108.3L16" +
  ".8 108.4ZM83.9 98.2L83.9 98.1L83.9 98L84 98L84 98.1L83.9 98.2ZM85.2 107.9L85.2 107.8L85.2 107.9Z" +
  "M18.9 99.6L18.8 99.7L18.8 99.6L18.9 99.6ZM84.1 92.5L84 92.6L84 92.5L84.1 92.5ZM68.5 61.2L68.4 61" +
  ".2L68.5 61.1L68.5 61.2ZM83.6 97.1L83.6 97L83.6 97.1ZM15.2 100.5L15.1 100.6L15.2 100.4L15.2 100.5" +
  "ZM84.8 94.8L84.7 94.8L84.8 94.7L84.8 94.8ZM83.7 96.9L83.7 97L83.6 97L83.6 96.9L83.7 96.9ZM83.7 9" +
  "7L83.7 97.1L83.6 97.1L83.7 97ZM84 97.9L84 97.8L84.1 97.9L84 97.9ZM83.5 97.1L83.5 97.2L83.4 97.1L" +
  "83.5 97.1ZM18.8 102.2L18.8 102.3L18.7 102.3L18.7 102.2L18.8 102.2L18.8 102.1L18.8 102.2ZM15.6 98" +
  ".6L15.6 98.5L15.6 98.6ZM13.8 96.9ZM16.6 97.2L16.6 97.3L16.5 97.3L16.5 97.2L16.6 97.2ZM15.5 96.6L" +
  "15.5 96.5L15.5 96.6ZM14.1 102.3ZM14.9 99.7L14.9 99.8L14.9 99.7ZM0.6 52.5L0.5 52.5L0.5 52.4L0.6 5" +
  "2.5ZM18.8 102L18.8 101.9L18.8 102ZM14.1 99.2Z";

/**
 * The field sites, at their true coordinates in the projection above.
 *
 * `status` is the honest part and the reason this is data rather than four hardcoded circles.
 * There is ONE workshop in the repository today. The other three are stated as next, and the map
 * must not imply four active sites — an unqualified dot on each would do exactly that. The one
 * live site is printed; the three that are next are drawn as blocks that have not come down.
 */
export type FieldSite = {
  id: string;
  name: string;
  region: string;
  /** Projected into INDIA_VIEWBOX by the same transform as INDIA_PATH. */
  x: number;
  y: number;
  lon: number;
  lat: number;
};

export const LIVE_SITE: FieldSite =
  { id: "bagru", name: "Bagru", region: "Rajasthan", x: 25.25, y: 41.57, lon: 75.5457, lat: 26.8129 };

export const NEXT_SITES: readonly FieldSite[] = [
  { id: "dehradun", name: "Dehradun", region: "Uttarakhand", x: 33.76, y: 27.92, lon: 78.0322, lat: 30.3165 },
  { id: "jammu", name: "Jammu", region: "Jammu & Kashmir", x: 22.9, y: 18.24, lon: 74.857, lat: 32.7266 },
  { id: "akola", name: "Akola", region: "Maharashtra", x: 30.25, y: 64.43, lon: 77.0082, lat: 20.7002 }
];

/**
 * Which cells of the printing grid the cloth actually needs.
 *
 * Computed off the shipped path at build time — one character per cell, row-major — so the browser
 * never carries a point-in-polygon routine or a parsed copy of the rings just to discover that
 * two-thirds of the bounding box is sea. A cell is on if its centre OR any of its four corners
 * falls inside the outline; centre-only leaves bald patches wherever the country is narrower than
 * one cell, which is most of the west coast.
 */
export const CLOTH_COLS = 13;
export const CLOTH_ROWS = 15;
export const CLOTH_CELL = 7.6923076923076925;
export const CLOTH_MASK =
  "001111000000000111100000000011100000000001111000000011111110111111111111111111111111111111011111111111101111111110000001111110000000111110000000011110000000001111000000000011100000000001100000000";
