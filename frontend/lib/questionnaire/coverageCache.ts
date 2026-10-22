/**
 * Coverage offline (DESIGN §3.1–3.3, §5.2): the workshop snapshot plus this browser's outbox
 * overlay answer `coverage/check` in the SERVER'S shape, so P2 asks the same question with or
 * without a signal. Online, the server's answer is used; this is the fallback and the overlay.
 *
 * The hierarchy rule, one rule everywhere: a target is covered for an artisan when the target, OR
 * ITS PARENT SECTION, has an ANSWERED take for them. PRESENT subjects never gain coverage.
 */

import { qs } from "@/lib/questionnaire/qs";
import type { CoverageCheckRequest, CoverageCheckResponse, CoverageTake, Recording, RepeatReason, Snapshot, SnapshotTake } from "@/lib/questionnaire/api";
import type { ClipStep, SessionUnit } from "@/lib/questionnaire/queue";

export const sectionKey = (sectionId: string) => `S:${sectionId}`;
export const questionKey = (lineageId: string) => `Q:${lineageId}`;

export function targetKeyOf(target: { sectionId: string } | { questionLineageId: string }): string {
  return "sectionId" in target ? sectionKey(target.sectionId) : questionKey(target.questionLineageId);
}

/** Folds a `sinceSeq` delta into a held snapshot: new or edited takes replace, removals drop. */
export function applySnapshotDelta(base: Snapshot | null, delta: Snapshot): Snapshot {
  if (!base) return delta;
  const removed = new Set(delta.removedRecordingIds);
  const key = (t: SnapshotTake) => `${t.recordingId}|${t.artisanId}|${t.targetKey}`;
  const merged = new Map<string, SnapshotTake>();
  for (const take of base.takes) if (!removed.has(take.recordingId)) merged.set(key(take), take);
  const touched = new Set(delta.takes.map((t) => t.recordingId));
  for (const k of [...merged.keys()]) if (touched.has(k.split("|")[0])) merged.delete(k);
  for (const take of delta.takes) merged.set(key(take), take);
  return { asOfSeq: delta.asOfSeq, takes: [...merged.values()], removedRecordingIds: [] };
}

export type OverlayTake = SnapshotTake & { onDevice: true; durationMs?: number | null };

// ── the snapshot this browser holds, and what it lays over it (audit M4) ─────────────────────────

/** Takes still in the outbox, as snapshot rows: every ANSWERED subject × target of every pending clip. */
export function overlayFromUnits(units: SessionUnit[]): OverlayTake[] {
  return units.flatMap((unit) =>
    unit.steps
      .filter((s): s is ClipStep => s.kind === "clip" && s.status !== "DONE")
      .flatMap((step) =>
        step.recording.subjects
          .filter((s): s is typeof s & { artisanId: string } => s.role === "ANSWERED" && Boolean(s.artisanId))
          .flatMap((subject) =>
            step.recording.targets.map(
              (target): OverlayTake => ({
                recordingId: step.clientClipKey,
                sessionId: unit.sessionId ?? unit.id,
                artisanId: subject.artisanId,
                targetKey: targetKeyOf(target),
                role: "ANSWERED",
                capturedAt: step.recording.deviceCapturedAt,
                estimated: step.recording.capturedAtSource !== "DEVICE",
                filing: "FILED",
                position: 0,
                onDevice: true,
                durationMs: step.recording.durationMs
              })
            )
          )
      )
  );
}

/** The takes a session payload carries, as snapshot rows; fresher than any snapshot for that session. */
export function takesFromRecordings(recordings: Recording[]): SnapshotTake[] {
  return recordings
    .filter((r) => !r.removedAt)
    .flatMap((r) =>
      r.subjects.flatMap((s) =>
        r.targets.map((t) => ({
          recordingId: r.id,
          sessionId: r.sessionId,
          artisanId: s.artisanId,
          targetKey: t.targetKey,
          role: s.role,
          capturedAt: r.capturedAt,
          estimated: r.estimated,
          filing: r.filing,
          position: s.position ?? 0
        }))
      )
    );
}

/** A snapshot with rows laid over it: a row for a recording replaces every held row of that recording. */
export function withTakes(snapshot: Snapshot | null, takes: SnapshotTake[]): Snapshot {
  const fresh = new Set(takes.map((t) => t.recordingId));
  return {
    asOfSeq: snapshot?.asOfSeq ?? "",
    takes: [...(snapshot?.takes ?? []).filter((t) => !fresh.has(t.recordingId)), ...takes],
    removedRecordingIds: []
  };
}

const SNAPSHOT_PREFIX = "field_repo_qv2_snapshot:";
export type HeldSnapshot = { snapshot: Snapshot; at: string };

function snapshotKey(questionnaireId: string, workshopId: string | null | undefined) {
  return `${SNAPSHOT_PREFIX}${questionnaireId}:${workshopId || "none"}`;
}

/** The snapshot this browser last fetched for an instrument and workshop, and when; null if none. */
export function readSnapshot(questionnaireId: string, workshopId: string | null | undefined): HeldSnapshot | null {
  try {
    const raw = window.localStorage.getItem(snapshotKey(questionnaireId, workshopId));
    if (!raw) return null;
    const held = JSON.parse(raw) as HeldSnapshot;
    return held && held.snapshot && Array.isArray(held.snapshot.takes) ? held : null;
  } catch {
    return null;
  }
}

function writeSnapshot(questionnaireId: string, workshopId: string | null | undefined, held: HeldSnapshot) {
  try {
    window.localStorage.setItem(snapshotKey(questionnaireId, workshopId), JSON.stringify(held));
  } catch {
    // Storage full or blocked: offline coverage falls back to the session's own takes and the outbox.
  }
}

/** Fetches the delta since the held snapshot (or the whole one) and keeps it; the held one on failure. */
export async function refreshSnapshot(
  api: { snapshot: (p: { questionnaireId: string; workshopId?: string | null; sinceSeq?: string | null }) => Promise<Snapshot> },
  questionnaireId: string,
  workshopId: string | null | undefined,
  now: Date = new Date()
): Promise<HeldSnapshot | null> {
  const held = readSnapshot(questionnaireId, workshopId);
  try {
    const delta = await api.snapshot({ questionnaireId, workshopId: workshopId ?? null, sinceSeq: held?.snapshot.asOfSeq || null });
    const next = { snapshot: applySnapshotDelta(held?.snapshot ?? null, delta), at: now.toISOString() };
    writeSnapshot(questionnaireId, workshopId, next);
    return next;
  } catch {
    return held;
  }
}

/**
 * The start sheet's offline coverage line, by the "14 of 24 sections" rule (DESIGN §3.2): a section
 * counts when it has an ANSWERED section take, or every one of its active questions has one.
 * NEEDS_DECISION takes do not count.
 */
export function coveredSectionsOffline(takes: SnapshotTake[], artisanId: string, questionsBySection: Record<string, string[]>): number {
  const mine = takes.filter((t) => t.artisanId === artisanId && t.role === "ANSWERED" && t.filing !== "NEEDS_DECISION").map((t) => t.targetKey);
  const keys = new Set(mine);
  return Object.entries(questionsBySection).filter(
    ([sectionId, lineages]) => keys.has(sectionKey(sectionId)) || (lineages.length > 0 && lineages.every((l) => keys.has(questionKey(l))))
  ).length;
}

export type OfflineContext = {
  /** The section a question lineage belongs to. */
  sectionOf: (lineageId: string) => string | null;
  labelOf: (targetKey: string) => string;
  sessionId?: string | null;
  me?: string | null;
  recordedBy?: (recordingId: string) => { id: string; name: string } | null;
  sessionInfo?: (sessionId: string) => { date: string | null; rosterCount: number } | null;
};

/** `coverage/check` answered from the snapshot and the outbox overlay. */
export function offlineCheck(
  snapshot: Snapshot | null,
  overlay: OverlayTake[],
  request: CoverageCheckRequest,
  ctx: OfflineContext,
  now = new Date()
): CoverageCheckResponse {
  const takes = [...(snapshot?.takes ?? []), ...overlay].filter((t) => t.role === "ANSWERED");
  const targets = request.targets.map((target) => {
    const key = targetKeyOf(target);
    const parent = "questionLineageId" in target ? ctx.sectionOf(target.questionLineageId) : null;
    const parentKey = parent ? sectionKey(parent) : null;
    return {
      targetKey: key,
      label: ctx.labelOf(key),
      perArtisan: request.artisanIds.map((artisanId) => {
        const own = takes.filter((t) => t.artisanId === artisanId && t.targetKey === key);
        const viaParent = parentKey ? takes.filter((t) => t.artisanId === artisanId && t.targetKey === parentKey) : [];
        const chosen = own.length ? own : viaParent;
        const sorted = [...chosen].sort((a, b) => a.capturedAt.localeCompare(b.capturedAt));
        const decision = sorted.some((t) => t.filing === "NEEDS_DECISION");
        const pending = sorted.some((t) => "onDevice" in t);
        const state = !sorted.length
          ? "NOT_RECORDED"
          : decision
            ? "NEEDS_DECISION"
            : own.length
              ? pending && sorted.every((t) => "onDevice" in t)
                ? "ON_DEVICE"
                : "RECORDED"
              : "VIA_SECTION";
        return {
          artisanId,
          state: state as CoverageCheckResponse["targets"][number]["perArtisan"][number]["state"],
          viaSection: !own.length && viaParent.length > 0,
          takes: sorted.map(
            (t, index): CoverageTake => ({
              recordingId: t.recordingId,
              position: t.position || index + 1,
              capturedAt: t.capturedAt,
              estimated: t.estimated,
              durationMs: "durationMs" in t ? ((t as OverlayTake).durationMs ?? null) : null,
              recordedBy: ctx.recordedBy?.(t.recordingId) ?? { id: "", name: "" },
              session: { id: t.sessionId, ...(ctx.sessionInfo?.(t.sessionId) ?? { date: t.capturedAt.slice(0, 10), rosterCount: 1 }) },
              sameSession: Boolean(ctx.sessionId && t.sessionId === ctx.sessionId),
              mine: Boolean(ctx.me && ctx.recordedBy?.(t.recordingId)?.id === ctx.me),
              repeatReason: null
            })
          )
        };
      })
    };
  });
  return { asOf: now.toISOString(), identical: null, targets, suggestedReason: null };
}

/**
 * The preselected reason (§3.3.2): the session purpose decides first; otherwise a different setting
 * than the earlier take → New setting; a different researcher → Re-interview; the same → Redo.
 */
export function preselectReason(input: {
  purpose: "FIRST_SITTING" | "FOLLOW_UP" | "REDO";
  rosterCount: number;
  earlier: CoverageTake | null;
  me: string | null;
  suggested?: RepeatReason | null;
}): RepeatReason {
  if (input.purpose === "FOLLOW_UP") return "FOLLOW_UP";
  if (input.purpose === "REDO") return "REDO";
  if (input.suggested) return input.suggested;
  const earlier = input.earlier;
  if (!earlier) return "REDO";
  const soloNow = input.rosterCount <= 1;
  const soloThen = earlier.session.rosterCount <= 1;
  if (soloNow !== soloThen) return "NEW_SETTING";
  if (input.me && earlier.recordedBy.id && earlier.recordedBy.id !== input.me) return "RE_INTERVIEW";
  return "REDO";
}

export type ArtisanCoverage = { name: string; covered: number; total: number };

/** First name for the coverage line ("Giriraj" for "Giriraj Prasad Chhipa"). */
export function shortName(name: string): string {
  return name.trim().split(/\s+/)[0] ?? name;
}

/**
 * The start sheet's coverage line (§4.2): one artisan, up to three, or a count for more. `checkedAgo`
 * is set when the figures come from the offline snapshot.
 */
export function coverageLine(artisans: ArtisanCoverage[], checkedAgo?: string | null): string {
  if (artisans.length === 0) return "";
  let line: string;
  if (artisans.length === 1) {
    const a = artisans[0];
    line = qs("coverageOne", { name: shortName(a.name), covered: a.covered, total: a.total });
  } else if (artisans.length <= 3) {
    line = `${artisans
      .map((a) =>
        a.covered > 0
          ? qs("coverageShort", { name: shortName(a.name), covered: a.covered, total: a.total })
          : qs("coverageNoneYet", { name: shortName(a.name) })
      )
      .join(" · ")}.`;
  } else {
    line = qs("coverageMany", { withTakes: artisans.filter((a) => a.covered > 0).length, count: artisans.length });
  }
  return checkedAgo ? `${line.replace(/\.$/, "")} · ${qs("checkedAgo", { age: checkedAgo.replace(/ ago$/, "") })}` : line;
}

/** "2 h ago", "5 min ago", "just now" — the age of a snapshot. */
export function agoLabel(fromIso: string, now = new Date()): string {
  const minutes = Math.max(0, Math.round((now.getTime() - new Date(fromIso).getTime()) / 60_000));
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 48) return `${hours} h ago`;
  return `${Math.round(hours / 24)} days ago`;
}
