"use client";

import { useCallback, useEffect, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import type { RecordingSort } from "@/lib/questionnaire/api";
import { DEFAULT_RECORDING_SORT, readRecordingSort, writeRecordingSort } from "@/lib/questionnaire/recordingSort";

export function useRecordingSort(): { sort: RecordingSort; ready: boolean; choose: (next: RecordingSort) => void } {
  const { user, loading } = useAuth();
  const viewer = loading ? null : user?.id ? String(user.id) : "";
  const [held, setHeld] = useState<{ viewer: string; sort: RecordingSort } | null>(null);

  useEffect(() => {
    if (viewer === null) return;
    setHeld({ viewer, sort: readRecordingSort(viewer) });
  }, [viewer]);

  const choose = useCallback(
    (next: RecordingSort) => {
      if (viewer === null) return;
      writeRecordingSort(viewer, next);
      setHeld({ viewer, sort: next });
    },
    [viewer]
  );

  const ready = held !== null && held.viewer === viewer;
  return { sort: ready ? held.sort : DEFAULT_RECORDING_SORT, ready, choose };
}
