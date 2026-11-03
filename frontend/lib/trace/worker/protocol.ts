import type { TraceParams } from '../engine/params';
import type { VecStyle } from '../engine/path';

export interface TransferableImage {
  readonly width: number;
  readonly height: number;
  readonly data: ArrayBuffer;
}

export function transferOf(image: TransferableImage): ArrayBuffer[] {
  return [image.data];
}

export type ToWorker =
  | {
      readonly type: 'trace';
      readonly requestId: number;
      readonly image: TransferableImage;
      readonly params: TraceParams;
      readonly preview: boolean;
    }
  | { readonly type: 'cancel'; readonly requestId: number };

export interface SerializedStage {
  readonly id: string;
  readonly label: string;
  readonly millis: number;
}

export interface SerializedProfile {
  readonly bimodality: number;
  readonly edgeDensity: number;
  readonly orientationEntropy: number;
  readonly saturationSpread: number;
  readonly isLineArt: boolean;
  readonly isHighTexture: boolean;
  readonly isFlatGraphic: boolean;
  readonly suggestion: string;
}

export const VERB_LINE = 0;
export const VERB_QUAD = 1;
export const VERB_CUBIC = 2;

export interface SerializedGeometry {
  readonly coords: Float32Array;
  readonly verbs: Uint8Array;
  readonly verbStarts: Uint32Array;
  readonly coordStarts: Uint32Array;
  readonly closed: Uint8Array;
  readonly styleTable: readonly VecStyle[];
  readonly styleIndex: Uint32Array;
}

export interface SerializedTraceResult {
  readonly geometry: SerializedGeometry;
  readonly background: number | null;
  readonly width: number;
  readonly height: number;
  readonly workingWidth: number;
  readonly workingHeight: number;
  readonly shapeCount: number;
  readonly nodeCount: number;
  readonly stages: readonly SerializedStage[];
  readonly totalMillis: number;
  readonly notes: readonly string[];
  readonly profile: SerializedProfile | null;
  readonly appliedParams: TraceParams;
  readonly autoSubjectId: string;
}

export function transfersOfResult(result: SerializedTraceResult): ArrayBuffer[] {
  const g = result.geometry;
  return [
    g.coords.buffer as ArrayBuffer,
    g.verbs.buffer as ArrayBuffer,
    g.verbStarts.buffer as ArrayBuffer,
    g.coordStarts.buffer as ArrayBuffer,
    g.closed.buffer as ArrayBuffer,
    g.styleIndex.buffer as ArrayBuffer,
  ];
}

export type FromWorker =
  | {
      readonly type: 'progress';
      readonly requestId: number;
      readonly stageId: string;
      readonly label: string;
      readonly fraction: number;
    }
  | {
      readonly type: 'done';
      readonly requestId: number;
      readonly result: SerializedTraceResult;
      readonly preview: boolean;
    }
  | { readonly type: 'error'; readonly requestId: number; readonly message: string };
