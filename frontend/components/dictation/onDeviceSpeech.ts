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
  return scope.SpeechRecognition ?? scope.webkitSpeechRecognition ?? null;
}

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
  }
}

export function dictationLanguageLabel(value: string): string {
  return DICTATION_LANGUAGES.find((entry) => entry.value === value)?.label ?? value;
}

export function describeSpeechError(code: string): string {
  switch (code) {
    case "not-allowed":
    case "service-not-allowed":
      return "Microphone blocked. Allow it in the address bar, or type instead.";
    case "no-speech":
      return "Nothing heard. Move closer and try again.";
    case "audio-capture":
      return "No microphone found. Type instead.";
    case "network":
      return "Dictation needs a connection here.";
    case "aborted":
      return "";
    default:
      return `Dictation stopped (${code}). Try again, or type instead.`;
  }
}

export const NO_RECOGNISER_SENTENCE =
  "Dictation isn’t available in this browser. Use Chrome, Edge or Safari, or type instead.";

export type BrowserRecognitionHooks = {
  language: string;
  onPhrase: (text: string) => void;
  onInterim: (text: string) => void;
  onProblem: (sentence: string) => void;
  onStopped: (reason: "error" | "end") => void;
};

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

export function startRecognition(recognition: SpeechRecognitionLike): boolean {
  try {
    recognition.start();
    return true;
  } catch {
    return false;
  }
}

export function stopRecognition(recognition: SpeechRecognitionLike | null): void {
  recognition?.stop();
}
