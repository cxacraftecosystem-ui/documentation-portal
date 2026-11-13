import { ApiError, ApiUnconfiguredError, describeApiBody } from "@/lib/api";
import type { MeasurementAnalysisResponse } from "@/lib/media";

export type MeasurementRemedy =
  | "an-administrator"
  | "a-connection"
  | "trying-again"
  | "a-different-request";

export type MeasurementFailureKind =
  | "provider-unconfigured"
  | "provider-failed"
  | "app-unconfigured"
  | "offline"
  | "timed-out"
  | "refused"
  | "server-failed";

export type MeasurementFailure = {
  kind: MeasurementFailureKind;
  sentence: string;
  remedy: MeasurementRemedy;
  serverSaidIt: boolean;
  status: number | null;
};

export const MEASUREMENT_FALLBACKS: Readonly<Record<MeasurementFailureKind, string>> = {
  "provider-unconfigured":
    "Photo measuring is off — an administrator can turn it on. Enter the value manually.",
  "provider-failed":
    "Couldn’t measure this photo. Try again, or enter the value manually.",
  "app-unconfigured":
    "Photo measuring is unavailable. Enter the value manually.",
  offline:
    "No connection. Enter the value manually, or retry with signal.",
  "timed-out":
    "Timed out. Retry on a steadier connection, or enter the value manually.",
  refused:
    "Couldn’t read this photo. Try a smaller JPEG or PNG, or enter the value manually.",
  "server-failed":
    "Service busy. Try again in a minute, or enter the value manually."
};

const REMEDY: Readonly<Record<MeasurementFailureKind, MeasurementRemedy>> = {
  "provider-unconfigured": "an-administrator",
  "app-unconfigured": "an-administrator",
  offline: "a-connection",
  "timed-out": "a-connection",
  "provider-failed": "trying-again",
  "server-failed": "trying-again",
  refused: "a-different-request"
};

function failure(kind: MeasurementFailureKind, said: string | null, status: number | null): MeasurementFailure {
  return { kind, sentence: said ?? MEASUREMENT_FALLBACKS[kind], remedy: REMEDY[kind], serverSaidIt: said !== null, status };
}

function localFailure(kind: MeasurementFailureKind, sentence: string | null, status: number | null): MeasurementFailure {
  return { kind, sentence: sentence ?? MEASUREMENT_FALLBACKS[kind], remedy: REMEDY[kind], serverSaidIt: false, status };
}

function words(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

export function serverSentence(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null;
  const body = error.payload;
  if (!body || typeof body !== "object") return null;
  const sentence = describeApiBody(body, "").trim();
  return sentence ? sentence : null;
}

export function measurementBodyFailure(response: MeasurementAnalysisResponse): MeasurementFailure | null {
  const said = words(response.message);
  if (response.available === false) return failure("provider-unconfigured", said, 200);
  if (response.status === "FAILED") return failure("provider-failed", said, 200);
  return null;
}

export function classifyMeasurementFailure(error: unknown): MeasurementFailure {
  if (error instanceof ApiUnconfiguredError) return localFailure("app-unconfigured", words(error.message), null);

  if (error instanceof ApiError) {
    const said = serverSentence(error);
    if (error.status === 503) return failure("provider-unconfigured", said, error.status);
    if (error.status === 408) return failure("timed-out", said, error.status);
    if (error.status === 429 || error.status >= 500) return failure("server-failed", said, error.status);
    return failure("refused", said, error.status);
  }

  return failure("offline", null, null);
}
