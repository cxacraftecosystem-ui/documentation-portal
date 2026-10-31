"use client";

import { useEffect, useState } from "react";
import { Check, Square, Volume2, X } from "lucide-react";

import { speakerSuggestions } from "@/lib/transcript/api";
import type { SpeakerCandidate, SpeakerMapping, SpeakerRole, Transcript, TranscriptSpeaker } from "@/lib/transcript/contract";
import { ts } from "@/lib/transcript/strings";

import { ICON_BUTTON, PRIMARY } from "./parts";

/**
 * Speaker 1, 2… → the session's people (design §4.1). The roster's suggestions are one-tap pills;
 * "Someone else" takes a typed name. A fix applies to every turn and every copy (the server
 * relabels them).
 */
export function SpeakerNames({
  transcript,
  onSave,
  onClose,
  onPlaySample,
  playingKey = null
}: {
  transcript: Transcript;
  onSave: (mapping: SpeakerMapping) => Promise<boolean>;
  onClose: () => void;
  /** Plays a few seconds of this speaker (their `sample`), so a voice can be told apart by ear. */
  onPlaySample?: (speaker: TranscriptSpeaker) => void;
  /** The speaker whose sample is playing now. */
  playingKey?: string | null;
}) {
  const [candidates, setCandidates] = useState<Record<string, SpeakerCandidate[]>>({});
  useEffect(() => {
    let cancelled = false;
    speakerSuggestions(transcript.mediaId)
      .then((answer) => {
        if (!cancelled) setCandidates(Object.fromEntries(answer.items.map((item) => [item.speakerKey, item.candidates])));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [transcript.mediaId]);

  return (
    <div className="grid gap-3 rounded-lg border border-line-200 bg-surface-50 p-3 sm:p-4" data-speaker-names>
      <div className="flex items-center justify-between gap-2">
        <h3 className="font-display text-sm font-bold text-ink-900">{ts("speakers")}</h3>
        <button type="button" className={ICON_BUTTON} onClick={onClose} aria-label={ts("close")}>
          <X className="h-4 w-4" aria-hidden />
        </button>
      </div>
      <ul className="grid gap-4">
        {transcript.speakers.map((speaker) => (
          <SpeakerRow
            key={speaker.speakerKey}
            speaker={speaker}
            candidates={candidates[speaker.speakerKey] ?? []}
            onSave={onSave}
            onPlaySample={onPlaySample}
            playing={playingKey === speaker.speakerKey}
          />
        ))}
      </ul>
      <p className="text-xs text-ink-500">{ts("applyToAll")}</p>
    </div>
  );
}

const roleWord = (role: SpeakerRole | null) => (role === "RESEARCHER" ? "researcher" : null);

function SpeakerRow({
  speaker,
  candidates,
  onSave,
  onPlaySample,
  playing
}: {
  speaker: TranscriptSpeaker;
  candidates: SpeakerCandidate[];
  onSave: (mapping: SpeakerMapping) => Promise<boolean>;
  onPlaySample?: (speaker: TranscriptSpeaker) => void;
  playing: boolean;
}) {
  const [typing, setTyping] = useState(false);
  const [name, setName] = useState("");
  const [saving, setSaving] = useState(false);

  async function pick(mapping: Omit<SpeakerMapping, "speakerKey">) {
    setSaving(true);
    const ok = await onSave({ speakerKey: speaker.speakerKey, ...mapping });
    setSaving(false);
    if (ok) {
      setTyping(false);
      setName("");
    }
  }

  return (
    <li className="grid gap-2" data-speaker-row={speaker.speakerKey}>
      <p className="flex flex-wrap items-center gap-2 text-sm">
        <span className="font-semibold text-ink-900">{speaker.name ? speaker.label : ts("mapSpeaker")}</span>
        {speaker.suggested ? <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">{ts("suggested")}</span> : null}
        {onPlaySample && speaker.sample ? (
          <button
            type="button"
            className="ml-auto inline-flex min-h-9 items-center gap-1.5 rounded-full border border-line-200 bg-card px-3 text-sm text-ink-900 transition hover:border-purple-300 hover:bg-purple-50 max-sm:min-h-11 dark:hover:bg-purple-950/40"
            aria-pressed={playing}
            data-play-sample={speaker.speakerKey}
            onClick={() => onPlaySample(speaker)}
          >
            {playing ? <Square className="h-3.5 w-3.5" aria-hidden /> : <Volume2 className="h-4 w-4" aria-hidden />}
            {playing ? ts("stopSample") : ts("playSample")}
          </button>
        ) : null}
      </p>
      <div className="flex flex-wrap gap-2" role="group" aria-label={speaker.label}>
        {candidates.map((candidate) => {
          const current = !speaker.suggested && speaker.name === candidate.name && (speaker.personId ?? null) === (candidate.personId ?? null);
          const word = roleWord(candidate.role);
          return (
            <button
              key={candidate.personId ?? candidate.name}
              type="button"
              disabled={saving}
              aria-pressed={current}
              data-candidate={candidate.name}
              onClick={() => void pick({ name: candidate.name, role: candidate.role, personId: candidate.personId })}
              className={`inline-flex min-h-9 items-center gap-1.5 rounded-full border px-3 text-sm transition max-sm:min-h-11 ${
                current
                  ? "border-purple-700 bg-purple-700 text-white"
                  : "border-line-200 bg-card text-ink-900 hover:border-purple-300 hover:bg-purple-50 dark:hover:bg-purple-950/40"
              }`}
            >
              {current ? <Check className="h-3.5 w-3.5" aria-hidden /> : null}
              {word ? ts("speakerResearcherSuffix", { name: candidate.name }) : candidate.name}
            </button>
          );
        })}
        {typing ? null : (
          <button
            type="button"
            onClick={() => setTyping(true)}
            className="inline-flex min-h-9 items-center rounded-full border border-dashed border-line-200 px-3 text-sm text-ink-700 hover:border-purple-300 max-sm:min-h-11"
          >
            {ts("speakerOther")}
          </button>
        )}
      </div>
      {typing ? (
        <form
          className="flex gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            if (name.trim()) void pick({ name: name.trim().slice(0, 80), role: "OTHER", personId: null });
          }}
        >
          <input
            className="min-w-0 flex-1 rounded-md border border-line-200 bg-card px-3 py-2 text-sm text-ink-900 focus:border-purple-600 focus:outline-hidden focus:ring-4 focus:ring-purple-600/15"
            value={name}
            maxLength={80}
            placeholder={ts("speakerNamePlaceholder")}
            aria-label={speaker.label}
            onChange={(event) => setName(event.target.value)}
          />
          <button type="submit" className={PRIMARY} disabled={saving || !name.trim()}>
            {ts("save")}
          </button>
        </form>
      ) : null}
    </li>
  );
}
