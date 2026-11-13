import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import type { Transcript } from "@/lib/transcript/contract";
import {
  defaultSpokenLanguage,
  languageSentence,
  languageUncertain,
  retranscribeEstimate,
  SPOKEN_AUTO,
  SPOKEN_LANGUAGE_OPTIONS,
  SPOKEN_MIXED,
  spokenChoiceOf,
  spokenLanguageLabel,
  suggestionHint,
  suggestionPath
} from "@/lib/transcript/spokenLanguage";
import { resetUploadChoicesForTests, setSpokenLanguages, spokenLanguageFor, spokenLanguagesOf, withSpokenLanguage } from "@/lib/transcript/uploadChoice";

const CONTRACT = JSON.parse(readFileSync(join(__dirname, "..", "..", "shared", "transcript-contract.json"), "utf8"));
const VIEW = CONTRACT.examples.TranscriptView as Transcript;

const file = (name: string, type: string) => ({ name, type, size: 1000, lastModified: 1 });

test.describe("spoken language choice", () => {
  test("the picker starts with automatic and mixed, then the Indian languages, then the rest", () => {
    const values = SPOKEN_LANGUAGE_OPTIONS.map((option) => option.value);
    expect(values.slice(0, 2)).toEqual([SPOKEN_AUTO, SPOKEN_MIXED]);
    expect(SPOKEN_LANGUAGE_OPTIONS[0].label).toBe("Detect automatically");
    expect(SPOKEN_LANGUAGE_OPTIONS[1].label).toBe("Mixed (several languages)");
    for (const code of ["or", "hi", "bn", "en"]) expect(values.indexOf(code)).toBeLessThan(values.indexOf("fr"));
    expect(new Set(values).size).toBe(values.length);
  });

  test("a choice is read from codes, regional codes and names, and nothing else", () => {
    expect(spokenChoiceOf("or")).toBe("or");
    expect(spokenChoiceOf("or-IN")).toBe("or");
    expect(spokenChoiceOf("Odia")).toBe("or");
    expect(spokenChoiceOf("mixed")).toBe(SPOKEN_MIXED);
    expect(spokenChoiceOf("Klingon")).toBeNull();
    expect(spokenLanguageLabel("or")).toBe("Odia");
    expect(spokenLanguageLabel(null)).toBe("Detect automatically");
  });

  test("the suggestion is the default and says where it came from, else automatic", () => {
    expect(defaultSpokenLanguage({ code: "or", label: "Odia", source: "artisan" }, "hi")).toBe("or");
    expect(defaultSpokenLanguage(null, "hi")).toBe("hi");
    expect(defaultSpokenLanguage(null, "other")).toBe(SPOKEN_AUTO);
    expect(defaultSpokenLanguage(null)).toBe(SPOKEN_AUTO);
    expect(suggestionHint({ code: "or", label: "Odia", source: "artisan" })).toBe("Last used with this artisan");
    expect(suggestionHint({ code: "or", label: "Odia", source: "researcher" })).toBe("Your last choice");
    expect(suggestionPath(["a2", "a1", "a2"])).toBe("/transcripts/spoken-language?artisanIds=a2%2Ca1");
  });

  test("the transcript states the language and flags an uncertain one", () => {
    expect(languageSentence(VIEW)).toBe("Detected: Hindi");
    expect(languageUncertain(VIEW)).toBe(true);
    const chosen = { ...VIEW, spokenLanguage: { ...VIEW.spokenLanguage!, requested: "or", requestedLabel: "Odia", uncertain: false, probability: 1 } };
    expect(languageSentence(chosen)).toBe("Transcribed as Odia");
    expect(languageUncertain(chosen)).toBe(false);
    expect(languageSentence({ spokenLanguage: null })).toBeNull();
  });

  test("the estimate names the minutes, and the cost only when the server sent one", () => {
    expect(retranscribeEstimate(VIEW)).toEqual(["About 1.1 min of audio"]);
    const priced = { ...VIEW, spokenLanguage: { ...VIEW.spokenLanguage!, estimateUsd: 0.0123 } };
    expect(retranscribeEstimate(priced)).toEqual(["About 1.1 min of audio", "Estimated cost: US$ 0.01"]);
  });

  test("an upload carries the chosen language in its metadata, and only for audio or video", () => {
    resetUploadChoicesForTests();
    const audio = file("take.m4a", "audio/mp4");
    const photo = file("loom.jpg", "image/jpeg");
    setSpokenLanguages([audio, photo], "or");
    expect(spokenLanguageFor(audio)).toBe("or");
    expect(spokenLanguageFor(photo)).toBeNull();
    expect(spokenLanguagesOf([audio, photo])).toEqual(["or", null]);
    expect(withSpokenLanguage(audio, { questionId: "q1" })).toEqual({ questionId: "q1", spokenLanguage: "or" });
    expect(withSpokenLanguage(photo, { questionId: "q1" })).toEqual({ questionId: "q1" });
    setSpokenLanguages([audio], null);
    expect(withSpokenLanguage(audio, undefined)).toBeUndefined();
  });
});
