"use client";

/**
 * Speak into a text field on a record form — on the device, and only on the device.
 *
 * WHAT THIS IS NOT. It is not the questionnaire's audio capture with the upload turned off. It has
 * no `MediaRecorder`, no `getUserMedia`, no `fetch`, no record id and no code path that can produce
 * a network request: what it hears never becomes a file and never leaves the handset. That is not a
 * simplification of a bigger control, it is the reason this control is allowed on a record form at
 * all, and it is asserted by a test that reads this file's source rather than trusting its types —
 * "it happens not to call fetch today" is not a property a type can carry.
 *
 * WHY A RECORD FORM GETS NO SERVER TRANSCRIPTION. The questionnaire records an interview: the
 * subject is told, the clip becomes a listed `MediaFile` with a filename and a delete button, and
 * `POST /media/transcribe` is a step in a workflow the subject can see. A microphone beside the
 * artisan's Notes box has none of that around it — nobody is told a recording is being made, there
 * is no stored object to point at afterwards, and there is nothing to delete. Uploading from here
 * would quietly convert "the browser is listening" into "this artisan's voice was sent to a
 * third-party transcription provider", on a route (`backend/app/api/routes/media.py:228`) whose only
 * gate is `get_current_user`. So the trade was refused: on-device recognition needs no consent
 * record because there is nothing to consent to. The audio is consumed by the browser and gone.
 *
 * WHAT IT COSTS, STATED HONESTLY RATHER THAN HIDDEN. Firefox implements no `SpeechRecognition` at
 * all, and neither do some locked-down Chromium builds and some Electron shells. With no server rung
 * there is nothing to fall through to, so the control SAYS SO in a sentence instead of vanishing —
 * see {@link NO_RECOGNISER_SENTENCE}. A feature that is present on one researcher's laptop and
 * absent on another's, with no explanation, gets reported as a broken build; a feature that explains
 * itself gets reported as a browser choice, which is what it is.
 *
 * THE RECOGNISER LIFECYCLE IS NOT IN THIS FILE. It is in `./onDeviceSpeech.ts`, so there is exactly
 * one implementation of the four things that go wrong (interim vs final results, `resultIndex`,
 * Safari's double-tap throw, stop-versus-abort) and so the spec can drive it without a renderer. If
 * you change how a phrase is committed, change it there.
 */

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

/**
 * "unknown" is the SSR and first-paint state and it draws nothing.
 *
 * Feature detection cannot run during render: `window` does not exist on the server, so a component
 * that decided in render would produce different markup on the two sides and React would throw a
 * hydration mismatch across the whole form. It is decided in an effect, which means one frame with
 * no control — invisible in practice, and far better than a control that flickers away.
 */
type Availability = "unknown" | "ready" | "absent";

export function OnDeviceDictationButton({
  onCommit,
  disabled,
  fieldLabel,
  /**
   * Draw the "this browser cannot dictate" sentence, or stay silent.
   *
   * Default true. Set false only where several dictation controls sit within a few centimetres of
   * each other — the process form's note rows — because eight copies of the same paragraph is not
   * honesty, it is noise, and the reader stops seeing all of them. Where it is set false, the
   * SURROUNDING control must carry the explanation once. Do not set it false to tidy up a single
   * field: that reinstates the silent-nothing this component exists to prevent.
   */
  explainWhenUnavailable = true
}: {
  /** Called with each finished phrase. The caller APPENDS — never replaces. */
  onCommit: (text: string) => void;
  disabled?: boolean;
  /** Names the field in the button's accessible label, so a screen reader hears which box. */
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

  /**
   * The commit callback, read through a ref by the recogniser's own handlers.
   *
   * Those handlers are installed once, when the recogniser is built, and close over whatever existed
   * then. A form's `onCommit` closes over the field's current value and changes identity on every
   * keystroke in a sibling box, so without this a phrase spoken thirty seconds into a session would
   * be appended to the value the box held when the microphone was pressed — silently discarding
   * everything typed in between. The failure is invisible while testing with one short phrase and
   * obvious only to the researcher who dictated three paragraphs — the worst order to find it in.
   * Do not "simplify" this into a direct call to `onCommit`.
   */
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

  /**
   * An unmount mid-sentence must release the microphone.
   *
   * `abort` and not `stop` here, and this is the one place that is right: a browser tab that keeps
   * the recording indicator lit after the form has been navigated away from is the single most
   * alarming thing this feature can do, and there is no longer a field for a held phrase to land in.
   */
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
      // Between mount and this click the recogniser vanished, which happens when an extension or a
      // policy removes it. Correct the control rather than doing nothing at all.
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
    // No button. A disabled microphone still reads as "press me, then wonder why nothing happened";
    // a sentence reads as an answer. Deliberately not `role="alert"` — nothing has gone wrong, this
    // is a standing fact about the browser, and announcing it on every field would be intolerable.
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
        // Provisional text has to LOOK provisional. Italic, dimmed, and inside a polite live region
        // so a screen-reader user hears it accumulate rather than being told at the end that a
        // paragraph appeared from nowhere.
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
