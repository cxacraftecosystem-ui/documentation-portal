"use client";

import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { CheckSquare, Copy, Download, Loader2, MessageCircleQuestion, Pause, Play, Quote, RotateCw, Users, Zap } from "lucide-react";

import {
  editTurn,
  errorCode,
  fetchTranscript,
  fetchTranscriptFile,
  mapSpeakers,
  retranslateTurn,
  retryTranscript,
  saveBlob,
  TRANSCRIPT_ROUTES,
  transcribeNow
} from "@/lib/transcript/api";
import type { CopyTrack, DownloadFormat, SpeakerMapping, Transcript, TranscriptSpeaker, TranscriptTurn, ViewTab } from "@/lib/transcript/contract";
import { clock, lengthLabel, usd } from "@/lib/transcript/format";
import { locate } from "@/lib/transcript/karaoke";
import { copyAll, copySelected, copySingle } from "@/lib/transcript/markdown";
import { downloadWhat, nameForView } from "@/lib/transcript/names";
import { inFlight, statusAction, statusLabel, statusNote, statusTone } from "@/lib/transcript/status";
import { ts } from "@/lib/transcript/strings";

import { AskPanel } from "./AskPanel";
import { FillFromRecording } from "./FillFromRecording";
import { Badge, CopyStatus, MenuButton, PRIMARY, SECONDARY, useCopyFeedback } from "./parts";
import { QuoteCardBuilder } from "./QuoteCardBuilder";
import { SpeakerNames } from "./SpeakerNames";
import { TranscriptMarkdown } from "./TranscriptMarkdown";
import { TurnRow } from "./TurnRow";
import { useTranscriptAudio } from "./useTranscriptAudio";

const POLL_MS = 15_000;

const TABS: { id: ViewTab; key: "tabOriginal" | "tabEnglish" | "tabSideBySide" }[] = [
  { id: "original", key: "tabOriginal" },
  { id: "english", key: "tabEnglish" },
  { id: "side", key: "tabSideBySide" }
];

const TONE_CLASS = {
  neutral: "border-line-200 bg-surface-50 text-ink-700",
  progress: "border-purple-200 bg-purple-50 text-purple-800 dark:border-purple-800 dark:bg-purple-950/50 dark:text-purple-200",
  done: "border-success-600/30 bg-success-100 text-success-600",
  error: "border-error-600/30 bg-error-100 text-error-600"
} as const;

const tabTrack = (tab: ViewTab): CopyTrack => (tab === "side" ? "both" : tab);

const failure = (error: unknown, fallback: string) =>
  errorCode(error) === "STALE_REVISION" ? ts("changedElsewhere") : error instanceof Error && error.message ? error.message : fallback;

export type TranscriptViewerProps = {
  /** MediaFile.id: every recording of both portals. */
  mediaId: string;
  /** The recording, when the caller already holds its url; else the view's own `audio.url`. */
  audioUrl?: string | null;
  /** What to draw while this recording has no transcript in the pipeline (the stored text, the old states). */
  fallback?: ReactNode;
  /** A questionnaire take: offers "Fill the questionnaire"; `onReview` opens the drafts in the form. */
  questionnaire?: { onReview?: () => void };
  /** The recording's chips (what it answers, who, when, where), drawn under the header facts. */
  chips?: ReactNode;
  /** False on a dense list (questionnaire takes): the header and player show, the turns wait for a tap. */
  initiallyOpen?: boolean;
};

/**
 * THE TRANSCRIPT VIEWER, on every recording of both portals (design §3, §4, §10).
 *
 * Header facts and model badges; Original · English · Side by side (stacked pairs below `md`);
 * per-turn copy; Copy all; Select turns → Copy selected / Make a quote card; downloads; speaker
 * names; turn edits; karaoke; Ask this interview; Fill the questionnaire on a take.
 */
export function TranscriptViewer({ mediaId, audioUrl, fallback = null, questionnaire, initiallyOpen = true, chips = null }: TranscriptViewerProps) {
  const [transcript, setTranscript] = useState<Transcript | null | undefined>(undefined);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [tab, setTab] = useState<ViewTab>("original");
  const [selecting, setSelecting] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [panel, setPanel] = useState<"speakers" | "ask" | "quote" | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [open, setOpen] = useState(initiallyOpen);
  /** Voice Isolator (0.0.5): the cleaned copy plays unless the reader asks for the recording as made. */
  const [original, setOriginal] = useState(false);
  const cleanedAudio = Boolean(transcript?.audio.cleaned && transcript.audio.originalUrl);
  const audio = useTranscriptAudio(
    audioUrl ?? (original && cleanedAudio ? transcript?.audio.originalUrl : transcript?.audio.url) ?? null
  );
  const [sampleKey, setSampleKey] = useState<string | null>(null);
  const playSample = useCallback(
    (speaker: TranscriptSpeaker) => {
      if (!speaker.sample) return;
      setSampleKey(speaker.speakerKey);
      audio.playRange(speaker.sample.startMs, speaker.sample.endMs);
    },
    [audio]
  );
  const marks = useTimelineMarks(mediaId);
  const { copy, message } = useCopyFeedback();

  useEffect(() => {
    let cancelled = false;
    setLoadError(null);
    fetchTranscript(mediaId)
      .then((loaded) => {
        if (!cancelled) setTranscript(loaded);
      })
      .catch(() => {
        if (!cancelled) setLoadError(ts("loadFailed"));
      });
    return () => {
      cancelled = true;
    };
  }, [mediaId, reloadKey]);

  const moving = transcript ? inFlight(transcript.status) : false;
  useEffect(() => {
    if (!moving) return;
    const timer = window.setInterval(() => {
      fetchTranscript(mediaId)
        .then((loaded) => {
          if (loaded) setTranscript(loaded);
        })
        .catch(() => undefined);
    }, POLL_MS);
    return () => window.clearInterval(timer);
  }, [moving, mediaId]);

  const chosen = useMemo(() => (transcript ? transcript.turns.filter((turn) => selected.has(turn.id)) : []), [transcript, selected]);
  const position = useMemo(
    () => (transcript && audio.playing ? locate(transcript.turns, audio.currentMs) : null),
    [transcript, audio.playing, audio.currentMs]
  );

  const audioSeek = audio.seek;
  const seek = useCallback((ms: number) => audioSeek(ms, true), [audioSeek]);

  const copyTurn = useCallback((turn: TranscriptTurn, track: CopyTrack) => void copy(copySingle(turn, track)), [copy]);

  const toggle = useCallback((turnId: string) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(turnId)) next.delete(turnId);
      else next.add(turnId);
      return next;
    });
  }, []);

  const replaceTurn = useCallback((next: TranscriptTurn) => {
    setTranscript((current) => (current ? { ...current, turns: current.turns.map((turn) => (turn.id === next.id ? next : turn)) } : current));
  }, []);

  const save = useCallback(
    async (turn: TranscriptTurn, change: { text?: string; english?: string }) => {
      setActionError(null);
      try {
        replaceTurn(await editTurn(mediaId, turn, change));
        return true;
      } catch (error) {
        setActionError(failure(error, ts("loadFailed")));
        if (errorCode(error) === "STALE_REVISION") setReloadKey((key) => key + 1);
        return false;
      }
    },
    [mediaId, replaceTurn]
  );

  const retranslate = useCallback(
    async (turn: TranscriptTurn) => {
      setBusy(`retranslate-${turn.id}`);
      setActionError(null);
      try {
        replaceTurn(await retranslateTurn(mediaId, turn.id));
      } catch (error) {
        setActionError(failure(error, ts("translationFailed")));
      } finally {
        setBusy(null);
      }
    },
    [mediaId, replaceTurn]
  );

  async function runView(label: string, work: () => Promise<Transcript>): Promise<boolean> {
    setBusy(label);
    setActionError(null);
    try {
      setTranscript(await work());
      return true;
    } catch (error) {
      setActionError(failure(error, ts("loadFailed")));
      if (errorCode(error) === "STALE_REVISION") setReloadKey((key) => key + 1);
      return false;
    } finally {
      setBusy(null);
    }
  }

  async function download(format: DownloadFormat, timestamps = true) {
    if (!transcript) return;
    setActionError(null);
    try {
      const track = (format === "srt" || format === "vtt") && tabTrack(tab) === "both" ? "original" : tabTrack(tab);
      const file = await fetchTranscriptFile(
        TRANSCRIPT_ROUTES.download(mediaId, format, track, timestamps),
        nameForView(transcript.title, downloadWhat(format, track), format)
      );
      saveBlob(file.blob, file.fileName);
    } catch (error) {
      setActionError(failure(error, ts("downloadFailed")));
    }
  }

  if (transcript === undefined && !loadError) {
    return (
      <p className="mt-2 flex items-center gap-2 text-sm text-ink-500" data-transcript-loading>
        <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden /> {ts("loading")}
      </p>
    );
  }
  if (loadError) {
    return (
      <div className="mt-2 grid justify-items-center gap-2 rounded-md border border-red-200 bg-red-50 px-3 py-3 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200">
        <p>{loadError}</p>
        <button type="button" className={SECONDARY} onClick={() => setReloadKey((key) => key + 1)}>
          {ts("tryAgain")}
        </button>
      </div>
    );
  }
  if (!transcript) return <>{fallback}</>;

  const tone = statusTone(transcript.status);
  const action = statusAction(transcript);
  const note = statusNote(transcript);
  const ready = transcript.turns.length > 0;
  const duration = audio.durationMs || transcript.audio.durationMs || transcript.durationMs || 0;
  const filled = duration > 0 ? (100 * Math.min(audio.currentMs, duration)) / duration : 0;

  return (
    <section className="mt-2 grid gap-3" aria-label={ts("transcript")} data-transcript-viewer={transcript.mediaId}>
      <header className="grid gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <span
            className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium ${TONE_CLASS[tone]}`}
            data-transcript-status={transcript.status}
          >
            {tone === "progress" ? <Loader2 className="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" aria-hidden /> : null}
            {statusLabel(transcript.status)}
          </span>
          {action ? (
            <button
              type="button"
              className={SECONDARY}
              disabled={busy !== null}
              data-status-action={action}
              onClick={() => void runView(action, () => (action === "retry" ? retryTranscript(mediaId) : transcribeNow(mediaId)))}
            >
              {action === "retry" ? <RotateCw className="h-4 w-4" aria-hidden /> : <Zap className="h-4 w-4" aria-hidden />}
              {action === "retry" ? ts("actionRetry") : ts("actionTranscribeNow")}
            </button>
          ) : null}
          {transcript.badges.length ? (
            <span className="ml-auto inline-flex flex-wrap gap-1.5" data-model-badges>
              {transcript.badges.map((badge) => (
                <Badge key={badge.kind} tone="accent">
                  {badge.label}
                </Badge>
              ))}
            </span>
          ) : null}
        </div>
        {note ? <p className="text-sm text-ink-700">{note}</p> : null}
        {transcript.readOnly ? (
          <p className="text-sm text-ink-500" data-transcript-read-only={transcript.readOnly.code}>
            {transcript.readOnly.message}
          </p>
        ) : null}
        <dl className="flex flex-wrap gap-x-5 gap-y-1 text-xs text-ink-500" data-transcript-facts>
          {transcript.languages.length ? (
            <div className="flex gap-1">
              <dt>{ts("headerLanguages")}</dt>
              <dd className="font-medium text-ink-900">{transcript.languages.map((language) => language.label).join(", ")}</dd>
            </div>
          ) : null}
          {transcript.durationMs ? (
            <div className="flex gap-1">
              <dt>{ts("headerLength")}</dt>
              <dd className="font-medium tabular-nums text-ink-900">{lengthLabel(transcript.durationMs)}</dd>
            </div>
          ) : null}
          {transcript.speakerCount ? (
            <div className="flex gap-1">
              <dt>{ts("headerSpeakers")}</dt>
              <dd className="font-medium text-ink-900">{transcript.speakerCount}</dd>
            </div>
          ) : null}
          {transcript.cost ? (
            <div className="flex gap-1" data-transcript-cost>
              <dt>{ts("spendPerRecording")}</dt>
              <dd className="font-medium tabular-nums text-ink-900">${usd(transcript.cost.totalUsd)}</dd>
            </div>
          ) : null}
        </dl>
        {chips}
      </header>

      {audio.hasAudio ? (
        <div className="flex items-center gap-3 rounded-md border border-line-200 bg-card px-3 py-2">
          <audio {...audio.bind} className="hidden" data-transcript-audio />
          <button
            type="button"
            onClick={audio.toggle}
            aria-label={audio.playing ? ts("pause") : ts("play")}
            className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-purple-700 text-white transition hover:bg-purple-800"
            data-testid="transcript-play"
          >
            {audio.playing ? <Pause className="h-4 w-4" aria-hidden /> : <Play className="ml-0.5 h-4 w-4" aria-hidden />}
          </button>
          <div className="relative min-w-0 flex-1" data-audio-timeline>
          <input
            type="range"
            min={0}
            max={Math.max(duration, 1)}
            step={100}
            value={Math.min(audio.currentMs, Math.max(duration, 1))}
            onChange={(event) => audio.seek(Number(event.target.value), false)}
            aria-label={ts("playFrom", { time: clock(audio.currentMs) })}
            className="audio-range w-full"
            style={
              {
                "--audio-range-fill": `linear-gradient(to right, rgb(var(--progress-fill)) 0%, rgb(var(--progress-fill)) ${filled}%, rgb(var(--progress-track)) ${filled}%, rgb(var(--progress-track)) 100%)`
              } as React.CSSProperties
            }
          />
          {duration > 0 && transcript
            ? transcript.turns
                .filter((turn) => marks.has(turn.id))
                .map((turn) => (
                  <button
                    key={turn.id}
                    type="button"
                    className="absolute top-1/2 h-4 w-4 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white bg-amber-500 shadow-sm transition hover:scale-125 focus-visible:outline-2 focus-visible:outline-purple-600 dark:border-ink-900"
                    style={{ left: `${Math.min(100, (100 * turn.startMs) / duration)}%` }}
                    aria-label={ts("jumpTo", { time: clock(turn.startMs) })}
                    title={`${turn.speakerName} · ${clock(turn.startMs)}`}
                    onClick={() => audio.seek(turn.startMs, true)}
                    data-timeline-mark={turn.id}
                  />
                ))
            : null}
          </div>
          <span className="shrink-0 font-mono text-xs tabular-nums text-ink-500" data-audio-clock>
            {clock(audio.currentMs)}
          </span>
        </div>
      ) : null}
      {audio.hasAudio && cleanedAudio ? (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-ink-500" data-audio-cleaned>
          <span>{ts("cleanedAudioNote")}</span>
          <label className="inline-flex min-h-9 cursor-pointer items-center gap-1.5 max-sm:min-h-11">
            <input
              type="checkbox"
              className="h-4 w-4 accent-purple-700"
              checked={original}
              onChange={(event) => setOriginal(event.target.checked)}
              data-testid="play-original"
            />
            {ts("originalAudio")}
          </label>
        </div>
      ) : null}

      {ready && !open ? (
        <div className="flex justify-center">
          <button type="button" className={SECONDARY} aria-expanded={false} onClick={() => setOpen(true)} data-testid="show-transcript">
            {ts("showTranscript")}
          </button>
        </div>
      ) : null}

      {ready && open ? (
        <>
          {transcript.summary.length ? (
            <ul className="grid gap-1 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700" data-transcript-summary>
              {transcript.summary.map((line, index) => (
                <li key={index}>
                  <TranscriptMarkdown text={line} />
                </li>
              ))}
            </ul>
          ) : null}

          <div className="flex flex-wrap items-center gap-2">
            <div role="tablist" aria-label={ts("tabsLabel")} className="inline-flex rounded-md border border-line-200 bg-surface-50 p-0.5">
              {TABS.map((entry) => (
                <button
                  key={entry.id}
                  type="button"
                  role="tab"
                  aria-selected={tab === entry.id}
                  data-tab={entry.id}
                  onClick={() => setTab(entry.id)}
                  className={`min-h-9 rounded-sm px-3 text-sm font-medium transition max-sm:min-h-11 ${
                    tab === entry.id ? "bg-card text-purple-800 shadow-sm dark:text-purple-200" : "text-ink-500 hover:text-ink-900"
                  }`}
                >
                  {ts(entry.key)}
                </button>
              ))}
            </div>
            <CopyStatus message={message} />
          </div>

          {/* A toolbar of controls on one transcript, not an action row. */}
          <div className="flex flex-wrap gap-2" data-transcript-toolbar>
            <MenuButton
              label={ts("copyAll")}
              ariaLabel={ts("copyAll")}
              icon={<Copy className="h-4 w-4" aria-hidden />}
              testId="copy-all"
              items={[
                { id: "original", label: ts("copyAllOriginal"), onSelect: () => void copy(copyAll(transcript, "original")) },
                { id: "english", label: ts("copyAllEnglish"), onSelect: () => void copy(copyAll(transcript, "english")) },
                { id: "both", label: ts("copyAllBoth"), onSelect: () => void copy(copyAll(transcript, "both")) },
                {
                  id: "both-plain",
                  label: ts("copyAllBothNoTimestamps"),
                  onSelect: () => void copy(copyAll(transcript, "both", false))
                }
              ]}
            />
            <button
              type="button"
              className={SECONDARY}
              aria-pressed={selecting}
              data-testid="select-turns"
              onClick={() => {
                setSelecting((value) => !value);
                setSelected(new Set());
                if (panel === "quote") setPanel(null);
              }}
            >
              <CheckSquare className="h-4 w-4" aria-hidden />
              {selecting ? ts("doneSelecting") : ts("selectTurns")}
            </button>
            <MenuButton
              label={ts("download")}
              ariaLabel={ts("download")}
              icon={<Download className="h-4 w-4" aria-hidden />}
              testId="download"
              items={[
                { id: "md", label: ts("downloadMarkdown"), onSelect: () => void download("md") },
                { id: "docx", label: ts("downloadWord"), onSelect: () => void download("docx") },
                { id: "md-plain", label: ts("downloadMarkdownNoTimestamps"), onSelect: () => void download("md", false) },
                { id: "docx-plain", label: ts("downloadWordNoTimestamps"), onSelect: () => void download("docx", false) },
                { id: "srt", label: ts("downloadSrt"), onSelect: () => void download("srt") },
                { id: "vtt", label: ts("downloadVtt"), onSelect: () => void download("vtt") }
              ]}
            />
            {transcript.permissions.mapSpeakers && transcript.speakers.length ? (
              <button
                type="button"
                className={SECONDARY}
                aria-expanded={panel === "speakers"}
                onClick={() => setPanel(panel === "speakers" ? null : "speakers")}
                data-testid="speakers-open"
              >
                <Users className="h-4 w-4" aria-hidden />
                {ts("speakers")}
              </button>
            ) : null}
            {transcript.permissions.ask ? (
              <button type="button" className={SECONDARY} aria-expanded={panel === "ask"} onClick={() => setPanel(panel === "ask" ? null : "ask")} data-testid="ask-open">
                <MessageCircleQuestion className="h-4 w-4" aria-hidden />
                {ts("askTitle")}
              </button>
            ) : null}
          </div>

          {selecting ? (
            <div
              className="flex flex-wrap items-center gap-2 rounded-md border border-purple-200 bg-purple-50 px-3 py-2 dark:border-purple-800 dark:bg-purple-950/40"
              data-selection-bar
            >
              <span className="text-sm font-medium text-purple-800 dark:text-purple-200" data-selected-count>
                {ts("selectedCount", { count: chosen.length })}
              </span>
              <span className="ml-auto inline-flex flex-wrap gap-2">
                <MenuButton
                  label={ts("copySelected")}
                  ariaLabel={ts("copySelected")}
                  icon={<Copy className="h-4 w-4" aria-hidden />}
                  className={PRIMARY}
                  testId="copy-selected"
                  align="end"
                  items={(["original", "english", "both"] as const).map((track) => ({
                    id: track,
                    label: track === "original" ? ts("copyOriginal") : track === "english" ? ts("copyEnglish") : ts("copyBoth"),
                    disabled: chosen.length === 0,
                    onSelect: () => void copy(copySelected(transcript, selected, track))
                  }))}
                />
                {transcript.permissions.quoteCard ? (
                  <button
                    type="button"
                    className={SECONDARY}
                    disabled={chosen.length === 0 || chosen.length > 6}
                    onClick={() => setPanel("quote")}
                    data-testid="quote-open"
                  >
                    <Quote className="h-4 w-4" aria-hidden />
                    {ts("quoteCard")}
                  </button>
                ) : null}
              </span>
            </div>
          ) : null}

          {actionError ? (
            <p role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700 dark:border-red-900 dark:bg-red-950/40 dark:text-red-200">
              {actionError}
            </p>
          ) : null}

          {panel === "speakers" ? (
            <SpeakerNames
              transcript={transcript}
              onSave={(mapping: SpeakerMapping) => runView(`speaker-${mapping.speakerKey}`, () => mapSpeakers(mediaId, transcript.revision, [mapping]))}
              onClose={() => setPanel(null)}
              onPlaySample={audio.hasAudio ? playSample : undefined}
              playingKey={audio.sampling ? sampleKey : null}
            />
          ) : null}
          {panel === "ask" ? <AskPanel mediaId={mediaId} onSeek={seek} /> : null}
          {panel === "quote" && chosen.length ? <QuoteCardBuilder mediaId={mediaId} title={transcript.title} turns={chosen} onClose={() => setPanel(null)} /> : null}
          {questionnaire && transcript.permissions.autodraft && transcript.status === "READY" ? (
            <FillFromRecording mediaId={mediaId} onSeek={seek} onReview={questionnaire.onReview} />
          ) : null}

          <ol className="grid gap-1" data-turns>
            {transcript.turns.map((turn, index) => {
              const active = position?.turnIndex === index;
              return (
                <TurnRow
                  key={`${turn.id}-${turn.revision}`}
                  turn={turn}
                  tab={tab}
                  active={active}
                  activeWord={active ? (position?.wordIndex ?? null) : null}
                  selecting={selecting}
                  selected={selected.has(turn.id)}
                  canEdit={transcript.permissions.edit}
                  onToggle={toggle}
                  onSeek={seek}
                  onCopy={copyTurn}
                  onSave={save}
                  onRetranslate={retranslate}
                  retranslating={busy === `retranslate-${turn.id}`}
                  marked={marks.has(turn.id)}
                  onMark={audio.hasAudio ? marks.set : undefined}
                />
              );
            })}
          </ol>
        </>
      ) : null}

      {transcript.status === "READY" && !ready ? <p className="text-sm text-ink-500">{ts("noTurns")}</p> : null}
    </section>
  );
}

/**
 * The turns a reader marked as points on this recording's timeline: off for every turn until ticked,
 * kept on this device per recording (a reading aid, not part of the transcript), and readable when
 * storage is not.
 */
function useTimelineMarks(mediaId: string) {
  const storageKey = `transcript-marks:${mediaId}`;
  const [ids, setIds] = useState<Set<string>>(() => new Set());
  useEffect(() => {
    try {
      const stored = window.localStorage.getItem(storageKey);
      setIds(new Set(stored ? (JSON.parse(stored) as string[]) : []));
    } catch {
      setIds(new Set());
    }
  }, [storageKey]);
  const set = useCallback(
    (turnId: string, marked: boolean) => {
      setIds((current) => {
        const next = new Set(current);
        if (marked) next.add(turnId);
        else next.delete(turnId);
        try {
          window.localStorage.setItem(storageKey, JSON.stringify([...next]));
        } catch {
          // A private window keeps the marks for this visit only.
        }
        return next;
      });
    },
    [storageKey]
  );
  return useMemo(() => ({ has: (turnId: string) => ids.has(turnId), set }), [ids, set]);
}
