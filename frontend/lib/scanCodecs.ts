/**
 * The Field Repository has no DPW grammars: the top-bar dispatcher (`lib/scanDispatch.ts`) refuses
 * every DPW code by prefix. The designer portal's copy of this file exports its decoders.
 */

import type { ScanCodecs } from "@/lib/scanDispatch";

export const SCAN_CODECS: ScanCodecs | undefined = undefined;
