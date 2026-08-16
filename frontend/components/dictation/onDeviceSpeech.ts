/**
 * The browser's own speech recogniser, and nothing else.
 *
 * WHY THIS FILE EXISTS AT ALL, AND WHY IT IS SEPARATE FROM THE BUTTON. The recogniser lifecycle is
 * fifty lines in which four separate defects have already been found and fixed once (interim versus
 * final results, `resultIndex`, Safari's double-tap throw, stop-versus-abort — each is documented at
 * the line that fixes it below). Every one of those is invisible when it is wrong: the field just
 * fills up with slightly wrong text. Keeping the lifecycle in one module means the second surface
 * that grows a microphone inherits the fixes instead of re-earning them, and it means the spec can
 * DRIVE the lifecycle against a fake recogniser without mounting a component — which matters here,
 * because this repository has no React renderer in its devDependencies.
 *
 * THE POLICY THIS MODULE ENFORCES BY OMISSION: dictation on a record form is ON-DEVICE ONLY. The
 * audio is consumed by the browser's own recogniser and discarded; it never becomes a file, never
 * becomes a request, and never reaches this application's server or the third-party provider behind
 * it.
 *
 * DO NOT ADD A NETWORK CALL TO THIS FILE, AND THE REASON IS SPECIFIC RATHER THAN GENERAL. This
 * backend already has an audio-transcription door: `POST /media/transcribe`
 * (`backend/app/api/routes/media.py:228`) takes an uploaded clip, hands it to `transcribe_audio`,
 * and gates on nothing but `get_current_user` — any signed-in researcher, any file, no consent
 * column consulted anywhere. It exists for the questionnaire's section-audio workflow, where the
 * interview subject has been told a recording is being made and the clip is a stored, listed,
 * deletable `MediaFile` the subject can be shown. A microphone on the artisan form has none of that
 * apparatus around it: the artisan is not told, nothing is stored to point at, and there is nothing
 * to delete. Wiring this module to that route would silently turn "the browser is listening" into
 * "this person's voice was uploaded", which is precisely the trade the on-device-only decision
 * refused. If a future Firefox fallback is genuinely wanted, it needs a consent record first — not
 * an import added here.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The Web Speech API, typed
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `lib.dom` has no `SpeechRecognition` — it is not in any published standard, only in a W3C
 * community-group note — so the shape is declared here rather than reached for through `any`.
 *
 * Narrow on purpose: only the members this repository touches. A wider transcription of the note
 * would be a second, unverifiable specification sitting in the tree, and the compiler cannot check
 * any of it against the browser that will actually run it.
 */
export type SpeechAlternative = { transcript: string; confidence: number };
export type SpeechResult = { isFinal: boolean; length: number; 0: SpeechAlternative };
export type SpeechResultList = { length: number; [index: number]: SpeechResult };
export type SpeechRecognitionEventLike = { resultIndex: number; results: SpeechResultList };
export type SpeechRecognitionErrorLike = { error: string; message?: string };

export type SpeechRecognitionLike = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  maxAlternatives: number;
  start: () => void;
  stop: () => void;
  abort: () => void;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onerror: ((event: SpeechRecognitionErrorLike) => void) | null;
  onend: (() => void) | null;
  onaudiostart: (() => void) | null;
};

export type SpeechRecognitionConstructor = new () => SpeechRecognitionLike;

export function speechRecognitionConstructor(): SpeechRecognitionConstructor | null {
  if (typeof window === "undefined") return null;
  const scope = window as unknown as {
    SpeechRecognition?: SpeechRecognitionConstructor;
    webkitSpeechRecognition?: SpeechRecognitionConstructor;
  };
  // The unprefixed name first: Chromium has been shipping it alongside the prefix, and a build that
  // eventually drops the prefix must not lose dictation on the day it does.
  return scope.SpeechRecognition ?? scope.webkitSpeechRecognition ?? null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Languages
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The languages this repository's fieldwork is actually conducted in.
 *
 * NOT the recogniser's whole list, which runs to a hundred-odd locales and would make the picker a
 * scrolling exercise on a phone. These eleven are the languages this repository's clusters work in,
 * and the tags are the `BCP 47` forms the Web Speech API wants — `hi-IN` and not `hi`, because the
 * bare subtag falls back to a generic model that mangles Indian place names.
 *
 * English is first and is the default because it is what a researcher's field notes are usually in
 * even where the interview is not. The rest are in the order a speaker of each would expect to find
 * their own: by speaker population, which is the only ordering that is not somebody's ranking.
 *
 * IT LIVES IN THIS MODULE AND NOT IN A FORM. Every record form dictates in the same languages, and
 * a list copied per form is a list that goes stale per form — the one that is missing Odia is
 * discovered by an Odia speaker in a village, not by a reviewer.
 */
export const DICTATION_LANGUAGES: Array<{ value: string; label: string }> = [
  { value: "en-IN", label: "English (India)" },
  { value: "hi-IN", label: "हिन्दी — Hindi" },
  { value: "bn-IN", label: "বাংলা — Bengali" },
  { value: "mr-IN", label: "मराठी — Marathi" },
  { value: "te-IN", label: "తెలుగు — Telugu" },
  { value: "ta-IN", label: "தமிழ் — Tamil" },
  { value: "gu-IN", label: "ગુજરાતી — Gujarati" },
  { value: "kn-IN", label: "ಕನ್ನಡ — Kannada" },
  { value: "ml-IN", label: "മലയാളം — Malayalam" },
  { value: "or-IN", label: "ଓଡ଼ିଆ — Odia" },
  { value: "pa-IN", label: "ਪੰਜਾਬੀ — Punjabi" }
];

/**
 * The chosen language, remembered — under ONE key for the whole application.
 *
 * A cluster is one language for a week. The artisan being recorded on the artisan form is the same
 * artisan whose product and tools are recorded on the next two forms ten minutes later, and making
 * the researcher re-pick Odia on each of them is the kind of friction that ends with everything
 * dictated in English-India and the Odia words transliterated wrong. One key, every surface — which
 * is also why the key is named for the application rather than for a form.
 *
 * `localStorage` THROWS rather than returning null when storage is blocked (Safari's private mode,
 * a locked-down kiosk profile), so both halves are wrapped — an exception here would take the whole
 * field down over a preference.
 */
export const DICTATION_LANGUAGE_STORAGE_KEY = "field_repo_dictation_language";

export function readStoredLanguage(): string {
  try {
    const stored = window.localStorage.getItem(DICTATION_LANGUAGE_STORAGE_KEY);
    return DICTATION_LANGUAGES.some((entry) => entry.value === stored) ? (stored as string) : "en-IN";
  } catch {
    return "en-IN";
  }
}

export function storeLanguage(value: string): void {
  try {
    window.localStorage.setItem(DICTATION_LANGUAGE_STORAGE_KEY, value);
  } catch {
    /* A preference that cannot be saved is still a preference that works for this session. */
  }
}

export function dictationLanguageLabel(value: string): string {
  return DICTATION_LANGUAGES.find((entry) => entry.value === value)?.label ?? value;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Failure wording
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One sentence per way this goes wrong, naming the NEXT MOVE rather than the error code.
 *
 * `not-allowed` and `service-not-allowed` are genuinely different — the first is the site being
 * refused the microphone, the second is the speech service being refused by policy — but the person
 * standing there does the same thing about both, so they share a sentence that covers both doors.
 *
 * `aborted` returns the empty string, and callers MUST treat that as "say nothing". It is what the
 * API reports when the page stopped the recogniser deliberately, which is the end of every normal
 * dictation; narrating it back as a failure would train researchers to ignore the one line that
 * matters when something is genuinely wrong.
 */
export function describeSpeechError(code: string): string {
  switch (code) {
    case "not-allowed":
    case "service-not-allowed":
      return "The browser refused access to the microphone. Allow it for this site in the address-bar permissions, then press the microphone again — or type the answer in.";
    case "no-speech":
      return "Nothing was heard. Hold the handset closer, or check that the right microphone is selected, and try again.";
    case "audio-capture":
      return "No microphone was found on this device. Plug one in, or type the answer in.";
    case "network":
      return "This browser sends dictation to a speech service over the internet and could not reach it. Dictation needs a connection even though the rest of this form does not.";
    case "aborted":
      return "";
    default:
      return `Dictation stopped unexpectedly (${code}). Press the microphone to try again, or type the answer in.`;
  }
}

/**
 * The sentence shown where the browser has no recogniser at all.
 *
 * SAID, NOT HIDDEN, and that is the whole point of exporting it. There is no second rung to fall
 * through to here — on-device is the only transport this feature has — so the choice is between a
 * sentence and a silence. A microphone that is present on the reviewer's Chrome and absent on a
 * researcher's Firefox with no explanation reads as a broken build: they retry, reload, ask a
 * colleague, and file a bug against the form. One sentence turns "broken" into "not here, and here
 * is what does work", which is a decision the person can act on in ten seconds.
 *
 * It is also deliberately not a disabled microphone. A control that is visible and does nothing is
 * this repository's most-repeated defect; a paragraph is not that.
 */
export const NO_RECOGNISER_SENTENCE =
  "Dictation is not available in this browser. Chrome, Edge and Safari have built-in speech recognition; Firefox does not. Type the answer in, or open this page in one of those.";

/* ────────────────────────────────────────────────────────────────────────────
 * The lifecycle — written once, driven by the button and by the spec
 * ──────────────────────────────────────────────────────────────────────────── */

export type BrowserRecognitionHooks = {
  /** BCP 47, from {@link DICTATION_LANGUAGES}. Read once, at construction — see `start`. */
  language: string;
  /** A finished phrase. Callers APPEND it; see the note on `onStopped` for why never replace. */
  onPhrase: (text: string) => void;
  /** The running guess, revised as the sentence lands. Empty string clears it. */
  onInterim: (text: string) => void;
  /** A sentence from {@link describeSpeechError}. Never called with the empty string. */
  onProblem: (sentence: string) => void;
  /**
   * The recogniser has stopped listening.
   *
   * `reason` is "error" when it stopped because something went wrong and "end" when the session
   * closed normally. They are told apart because the caller disposes of its handle at only one of
   * those two moments: `onend` reliably follows `onerror` in every implementation this feature has
   * been run against, so releasing the reference on "error" as well would drop a live recogniser's
   * handle while it is still delivering, and the phrase it is holding would land nowhere.
   */
  onStopped: (reason: "error" | "end") => void;
};

/**
 * A configured recogniser, or null where the browser has none.
 *
 * EVERY LINE HERE IS A DEFECT THAT WAS FIXED ONCE ALREADY, which is why it is written here once
 * rather than retyped at each microphone:
 *
 *  - `continuous` keeps the session open across the pauses in a spoken paragraph. Without it the
 *    recogniser stops after the first sentence and a researcher describing a five-step process has
 *    to press the button five times.
 *  - `interimResults` is what makes the button look alive in the first three seconds. Hiding the
 *    running guess makes a working microphone look dead.
 *  - `resultIndex` is the start of the NEW results, not zero. Iterating from zero re-commits every
 *    phrase already spoken, so a paragraph comes out with each sentence repeated n times.
 *  - Only `isFinal` results are committed. Committing an interim writes the recogniser's first guess
 *    ("the wharf is") and then its correction ("the warp is sized") as two separate phrases.
 *
 * The caller keeps the returned object and calls `stop()` on it — see {@link stopRecognition}.
 */
export function createBrowserRecognition(hooks: BrowserRecognitionHooks): SpeechRecognitionLike | null {
  const Recognition = speechRecognitionConstructor();
  if (!Recognition) return null;
  const recognition = new Recognition();
  recognition.lang = hooks.language;
  recognition.continuous = true;
  recognition.interimResults = true;
  recognition.maxAlternatives = 1;

  recognition.onresult = (event) => {
    let pending = "";
    for (let index = event.resultIndex; index < event.results.length; index += 1) {
      const result = event.results[index];
      const text = result[0]?.transcript ?? "";
      if (result.isFinal) {
        const finished = text.trim();
        if (finished) hooks.onPhrase(finished);
      } else {
        pending += text;
      }
    }
    hooks.onInterim(pending.trim());
  };

  recognition.onerror = (event) => {
    const sentence = describeSpeechError(event.error);
    if (sentence) hooks.onProblem(sentence);
    hooks.onInterim("");
    hooks.onStopped("error");
  };

  recognition.onend = () => {
    hooks.onInterim("");
    hooks.onStopped("end");
  };

  return recognition;
}

/**
 * Start it, and say whether it started.
 *
 * Safari throws `InvalidStateError` when `start()` is called on an instance that is already running,
 * which happens on a double tap. Unguarded that leaves the button stuck reading "Stop" with nothing
 * listening behind it — a dead control that claims to be live, which is the exact failure mode this
 * whole feature is written around. `false` means the caller must put the button back.
 */
export function startRecognition(recognition: SpeechRecognitionLike): boolean {
  try {
    recognition.start();
    return true;
  } catch {
    return false;
  }
}

/**
 * `stop()` and NOT `abort()`.
 *
 * Stop lets the recogniser deliver the phrase it is still holding; abort throws it away, which loses
 * the last sentence of every dictation. `abort()` is correct in exactly one place — tearing down on
 * unmount, where there is no longer a field for the phrase to land in.
 */
export function stopRecognition(recognition: SpeechRecognitionLike | null): void {
  recognition?.stop();
}
