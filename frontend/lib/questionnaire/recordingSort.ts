import { RECORDING_SORTS, type RecordingSort } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";

export const DEFAULT_RECORDING_SORT: RecordingSort = "added";

const KEY = "questionnaire.recordings.sort";

type Store = Pick<Storage, "getItem" | "setItem">;

function browserStore(): Store | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

export function recordingSortKey(viewer: string): string {
  return `${KEY}:${viewer || "anonymous"}`;
}

export function isRecordingSort(value: unknown): value is RecordingSort {
  return typeof value === "string" && (RECORDING_SORTS as readonly string[]).includes(value);
}

export function readRecordingSort(viewer: string, store: Store | null = browserStore()): RecordingSort {
  try {
    const value = store?.getItem(recordingSortKey(viewer));
    return isRecordingSort(value) ? value : DEFAULT_RECORDING_SORT;
  } catch {
    return DEFAULT_RECORDING_SORT;
  }
}

export function writeRecordingSort(viewer: string, sort: RecordingSort, store: Store | null = browserStore()): void {
  try {
    store?.setItem(recordingSortKey(viewer), sort);
  } catch {
    return;
  }
}

export function recordingSortOptions(): { value: RecordingSort; label: string }[] {
  return [
    { value: "added", label: qs("sortAdded") },
    { value: "recorded", label: qs("sortRecorded") }
  ];
}
