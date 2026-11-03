import type {
  SerializedTraceResult,
  TraceParams,
  TraceParamsInput,
  TraceProgress,
  Tracer,
  TransferableImage
} from "@/lib/trace/traceClient";

export interface TraceRuntime {
  readonly Tracer: new () => Tracer;
  readonly transferableFrom: (source: {
    readonly data: Uint8ClampedArray | Uint8Array;
    readonly width: number;
    readonly height: number;
  }) => TransferableImage;
  readonly defaults: TraceParams;
  readonly sanitize: (input: TraceParamsInput) => TraceParams;
  readonly isUnavailable: (error: unknown) => boolean;
  readonly isCancelled: (error: unknown) => boolean;
}

let runtimePromise: Promise<TraceRuntime> | null = null;

const ENGINE_UNAVAILABLE = "Tracing engine didn’t load. Reload the page.";

export async function loadTraceRuntime(): Promise<TraceRuntime> {
  if (runtimePromise !== null) return await runtimePromise;
  const started = (async (): Promise<TraceRuntime> => {
    let client: typeof import("@/lib/trace/traceClient");
    try {
      client = await import("@/lib/trace/traceClient");
    } catch (error) {
      throw new Error(ENGINE_UNAVAILABLE, { cause: error });
    }
    let defaults: TraceParams;
    let sanitize: (input: TraceParamsInput) => TraceParams;
    try {
      ({ defaults, sanitize } = await client.loadTraceParams());
    } catch (error) {
      throw new client.TraceUnavailableError(ENGINE_UNAVAILABLE, { cause: error });
    }
    return {
      Tracer: client.Tracer,
      transferableFrom: client.transferableFrom,
      defaults,
      sanitize,
      isUnavailable: (error) => error instanceof client.TraceUnavailableError,
      isCancelled: (error) => error instanceof client.TraceCancelledError
    };
  })();
  runtimePromise = started;
  try {
    return await started;
  } catch (error) {
    if (runtimePromise === started) runtimePromise = null;
    throw error;
  }
}

export interface StyleChoice {
  readonly id: string;
  readonly name: string;
  readonly description: string;
  readonly group: string;
  readonly params: TraceParams;
}

export interface SubjectChoice {
  readonly id: string;
  readonly name: string;
  readonly hint: string;
  readonly adjust: (p: TraceParams) => TraceParams;
}

export interface TracePresets {
  readonly styles: readonly StyleChoice[];
  readonly styleGroups: readonly string[];
  readonly subjects: readonly SubjectChoice[];
}

let presetsPromise: Promise<TracePresets> | null = null;

export async function loadTracePresets(): Promise<TracePresets> {
  if (presetsPromise !== null) return await presetsPromise;
  const started = (async (): Promise<TracePresets> => {
    const [styles, subjects] = await Promise.all([
      import("@/lib/trace/engine/styles"),
      import("@/lib/trace/engine/subjects")
    ]);
    return {
      styles: styles.ALL.map((preset) => ({
        id: preset.id,
        name: preset.name,
        description: preset.description,
        group: preset.group,
        params: preset.params
      })),
      styleGroups: styles.groups(),
      subjects: subjects.ALL.map((preset) => ({
        id: preset.id,
        name: preset.name,
        hint: preset.hint,
        adjust: preset.adjust
      }))
    };
  })();
  presetsPromise = started;
  try {
    return await started;
  } catch (error) {
    if (presetsPromise === started) presetsPromise = null;
    throw error;
  }
}

export type { SerializedTraceResult, TraceParams, TraceParamsInput, TraceProgress, Tracer, TransferableImage };
