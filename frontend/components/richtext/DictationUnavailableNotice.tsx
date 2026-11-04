"use client";

import { useEffect, useState } from "react";

import { NO_RECOGNISER_SENTENCE, speechRecognitionConstructor } from "@/components/dictation/onDeviceSpeech";

export function DictationUnavailableNotice({
  className
}: {
  className?: string;
}) {
  const [absent, setAbsent] = useState<boolean | null>(null);

  useEffect(() => {
    setAbsent(!speechRecognitionConstructor());
  }, []);

  if (absent !== true) return null;

  return <p className={`text-xs leading-5 text-ink-500 ${className ?? ""}`}>{NO_RECOGNISER_SENTENCE}</p>;
}
