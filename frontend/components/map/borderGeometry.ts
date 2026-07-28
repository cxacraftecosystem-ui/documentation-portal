/**
 * State and district BORDERS, fetched on demand and decoded with the outline's own scheme.
 *
 * WHY THESE ARE BORDERS AND NOT POLYGONS. The map draws pins, not a choropleth, so what it needs is
 * the LINES a reader recognises as borders. Storing them that way is also the smaller and more correct
 * choice: as polygons, the Rajasthan/Gujarat border would appear twice — once in each state's ring —
 * and 86.4% of the source's edges are such pairs, so polygons would ship the interior of India twice.
 * See `scripts/build_boundaries.py`, which derives these from a topologically clean district source by
 * classifying every shared edge:
 *
 *     shared by two districts of DIFFERENT states -> a state border
 *     shared by two districts of the SAME state   -> a district border
 *     appearing once                             -> the coast or the international frontier, DROPPED
 *
 * WHY THE FRONTIER IS DROPPED, which is the whole answer to "is the national boundary the same at
 * every level". It is not derived from these files at all. `indiaGeometry` already holds it, verified
 * point-in-polygon against the official Government of India depiction, and both apps draw THAT, once,
 * at every detail level. So the national boundary is identical across the three levels by
 * construction rather than by hoping two published datasets agree — and they do not: the district
 * source's outer extent differs from the outline's by up to ~0.02 degrees (~2 km). Which is why every
 * border layer is CLIPPED to the national outline when drawn; see `IndiaMap`.
 *
 * WHY FETCHED RATHER THAN INLINED, unlike the national outline. The outline is 18.3 KiB and is needed
 * before anything can be drawn at all, so it is part of the module and there is no loading state to
 * get wrong. These are 21 KiB and 68 KiB and are needed only once a reader asks for that level of
 * detail — the district file in particular is the largest asset on the page and the NATION level never
 * wants it. A fetch keeps the map's first paint exactly as fast as it was.
 */

/** Must match `ALPHABET` in `scripts/build_boundaries.py` and `indiaGeometry`'s own decoder. */
const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
const VALUES = new Map<string, number>([...ALPHABET].map((character, index) => [character, index]));

/** Three decimal places, the same quantisation the national outline uses. */
const SCALE = 1000;

/** One border run as [longitude, latitude] pairs. Open — never close these. */
export type BorderLine = Array<[number, number]>;

export type BorderLevel = "state" | "district";

const ASSET: Record<BorderLevel, string> = {
  state: "/boundaries/state-borders.txt",
  district: "/boundaries/district-borders.txt"
};

/**
 * Decode the payload into open polylines.
 *
 * The stream is the outline's, minus the hole flag it has no use for here: per record a zig-zag varint
 * point count, then that many delta-encoded coordinate pairs, with x and y carried across records so a
 * neighbouring border costs a few characters rather than a full coordinate.
 */
export function decodeBorders(payload: string): BorderLine[] {
  const lines: BorderLine[] = [];
  let index = 0;
  let x = 0;
  let y = 0;

  const readInt = (): number => {
    let shift = 0;
    let result = 0;
    for (;;) {
      const value = VALUES.get(payload[index++]) ?? 0;
      result |= (value & 0x1f) << shift;
      shift += 5;
      if (value < 0x20) break;
    }
    // Zig-zag: the low bit is the sign, so a small negative delta stays one character wide.
    return result & 1 ? ~(result >> 1) : result >> 1;
  };

  while (index < payload.length) {
    const count = readInt();
    // A zero or negative count would mean a corrupt payload; bail rather than loop forever on it.
    if (count <= 0) break;
    const line: BorderLine = [];
    for (let n = 0; n < count; n += 1) {
      x += readInt();
      y += readInt();
      line.push([x / SCALE, y / SCALE]);
    }
    lines.push(line);
  }
  return lines;
}

/**
 * Fetch and decode one border level, at most once per page.
 *
 * The promise itself is cached, not the result, so two components asking during the same tick share
 * one request instead of racing two. A FAILED fetch is evicted, so a map opened on a flaky rural
 * connection can retry by re-selecting the level rather than being stuck without borders for the rest
 * of the session — which is the same "coordinates first, names later" discipline the location card
 * uses.
 */
const pending = new Map<BorderLevel, Promise<BorderLine[]>>();

export function loadBorders(level: BorderLevel): Promise<BorderLine[]> {
  const existing = pending.get(level);
  if (existing) return existing;
  const request = fetch(ASSET[level], { cache: "force-cache" })
    .then((response) => {
      if (!response.ok) throw new Error(`${ASSET[level]} returned ${response.status}`);
      return response.text();
    })
    .then(decodeBorders)
    .catch((cause: unknown) => {
      pending.delete(level);
      throw cause;
    });
  pending.set(level, request);
  return request;
}
