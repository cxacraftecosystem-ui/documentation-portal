"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Mic, Square } from "lucide-react";

import { Dropdown } from "@/components/ui/Dropdown";
import {
  createBrowserRecognition,
  dictationLanguageLabel,
  DICTATION_LANGUAGES,
  NO_RECOGNISER_SENTENCE,
  readStoredLanguage,
  speechRecognitionConstructor,
  startRecognition,
  stopRecognition,
  storeLanguage,
  type SpeechRecognitionLike
} from "@/components/dictation/onDeviceSpeech";

type Availability = "unknown" | "ready" | "absent";

export function OnDeviceDictationButton({
  onCommit,
  disabled,
  fieldLabel,
  explainWhenUnavailable = true
}: {
  onCommit: (text: string) => void;
  disabled?: boolean;
  fieldLabel: string;
  explainWhenUnavailable?: boolean;
}) {
  const [availability, setAvailability] = useState<Availability>("unknown");
  const [language, setLanguage] = useState("en-IN");
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [showLanguages, setShowLanguages] = useState(false);

  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);

  const commitRef = useRef(onCommit);
  useEffect(() => {
    commitRef.current = onCommit;
  }, [onCommit]);

  const languageRef = useRef(language);
  useEffect(() => {
    languageRef.current = language;
  }, [language]);

  useEffect(() => {
    setLanguage(readStoredLanguage());
    setAvailability(speechRecognitionConstructor() ? "ready" : "absent");
  }, []);

  const teardown = useCallback(() => {
    recognitionRef.current?.abort();
    recognitionRef.current = null;
  }, []);
  useEffect(() => teardown, [teardown]);

  function start() {
    const recognition = createBrowserRecognition({
      language: languageRef.current,
      onPhrase: (text) => commitRef.current(text),
      onInterim: setInterim,
      onProblem: setProblem,
      onStopped: (reason) => {
        setListening(false);
        if (reason === "end") recognitionRef.current = null;
      }
    });
    if (!recognition) {
      setAvailability("absent");
      return;
    }
    recognitionRef.current = recognition;
    setProblem(null);
    setInterim("");
    setListening(true);
    if (!startRecognition(recognition)) {
      setListening(false);
      recognitionRef.current = null;
    }
  }

  function stop() {
    stopRecognition(recognitionRef.current);
    setListening(false);
    setInterim("");
  }

  if (availability === "unknown") return null;

  if (availability === "absent") {
    return explainWhenUnavailable ? (
      <p className="text-xs leading-5 text-ink-500">{NO_RECOGNISER_SENTENCE}</p>
    ) : null;
  }

  const languageName = dictationLanguageLabel(language);

  return (
    <div className="grid gap-1.5">
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          className={
            listening
              ? "inline-flex min-h-8 items-center justify-center gap-1.5 rounded-md bg-purple-700 px-2.5 py-1 text-xs font-medium text-white transition"
              : "inline-flex min-h-8 items-center justify-center gap-1.5 rounded-md border border-line-200 bg-card px-2.5 py-1 text-xs font-medium text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-60"
          }
          disabled={disabled}
          aria-pressed={listening}
          aria-label={listening ? `Stop dictating ${fieldLabel}` : `Dictate ${fieldLabel} in ${languageName}`}
          data-dictation-on-device=""
          onClick={() => (listening ? stop() : start())}
        >
          {listening ? <Square className="h-3.5 w-3.5" aria-hidden /> : <Mic className="h-3.5 w-3.5" aria-hidden />}
          {listening ? "Stop" : "Dictate"}
        </button>

        <button
          type="button"
          className="text-xs font-medium text-ink-500 underline"
          aria-expanded={showLanguages}
          disabled={disabled || listening}
          onClick={() => setShowLanguages((current) => !current)}
        >
          {languageName}
        </button>
      </div>

      {showLanguages ? (
        <div className="max-w-xs">
          <Dropdown
            value={language}
            onChange={(next) => {
              setLanguage(next);
              storeLanguage(next);
              setShowLanguages(false);
            }}
            options={DICTATION_LANGUAGES}
            placeholder="Dictation language"
            ariaLabel="Dictation language"
            disabled={disabled || listening}
          />
        </div>
      ) : null}

      {listening || interim ? (
        <p
          aria-live="polite"
          className="min-h-5 rounded-md border border-dashed border-purple-300 bg-purple-50 px-2 py-1 text-xs italic leading-5 text-purple-800"
        >
          {interim || "Listening… speak now."}
        </p>
      ) : null}

      {problem ? <p className="text-xs font-medium leading-5 text-error-600">{problem}</p> : null}
    </div>
  );
}
