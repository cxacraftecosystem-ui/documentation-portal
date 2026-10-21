/**
 * The prompts of DESIGN §4.4–4.5 as DATA: title, body and the buttons in order. `components/
 * questionnaire/Prompts.tsx` draws them through `ButtonRow`, so the button-row rule (one centred,
 * two on a line, more stacked or pills) is decided once; the unit specs read these builders to pin
 * the exact copy.
 */

import { qs } from "@/lib/strings/qs";
import { shortName } from "@/lib/coverageCache";
import type { CoverageCheckResponse, CoverageTake, RepeatReason } from "@/lib/questionnaireApi";

export type PromptButton = { id: string; label: string; tone?: "primary" | "secondary" | "danger" };
/**
 * `dismiss` is what Escape, the backdrop and a lost focus resolve to (audit H16): the button that loses
 * nothing, named per prompt, or null for "close and do nothing". Never derived from the button order —
 * that once made dismissing P13 discard a recovered take and dismissing Finish finish the session.
 */
export type PromptSpec = { id: string; title: string | null; body: string; buttons: PromptButton[]; more?: PromptButton[]; dismiss: string | null };

/** What a dismissal reports when a prompt's `dismiss` is null; no handler acts on it. */
export const DISMISSED = "dismissed";

/** "7 Jul" — the take date style used in copy. */
export function shortDate(iso: string | null | undefined): string {
  if (!iso) return "";
  const date = new Date(iso.length === 10 ? `${iso}T00:00:00Z` : iso);
  if (Number.isNaN(date.getTime())) return "";
  // en-IN writes September as "Sept"; the copy (DESIGN §4) uses three letters for every month.
  return date.toLocaleDateString("en-IN", { day: "numeric", month: "short", timeZone: "Asia/Kolkata" }).replace("Sept", "Sep");
}

/** "14 min", "0:42" under a minute. */
export function durationLabel(ms: number | null | undefined): string {
  if (!ms || ms <= 0) return "";
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `0:${String(seconds).padStart(2, "0")}`;
  return `${Math.round(seconds / 60)} min`;
}

export function settingLabel(rosterCount: number): string {
  return rosterCount > 1 ? qs("modeGroupOf", { count: rosterCount }) : qs("solo");
}

export type P2Input = {
  check: CoverageCheckResponse;
  names: Record<string, string>;
  /** "Section D" or "D3". */
  targetLabel: string;
  /** "D" for a section, "D3" for a question; used in "hasn't been recorded for D". */
  code: string;
  filePick?: boolean;
  part2Allowed: boolean;
  replaceAllowed: boolean;
};

export type P2Row = { artisanId: string; line: string };

/** P2 "Already recorded": the copy variants of §4.4, decided from the check answer. */
export function alreadyRecorded(input: P2Input): {
  spec: PromptSpec;
  covered: P2Row[];
  first: string[];
} {
  const target = input.check.targets[0];
  const per = target?.perArtisan ?? [];
  const covered = per.filter((p) => p.takes.length > 0);
  const fresh = per.filter((p) => p.takes.length === 0);
  const nameOf = (id: string) => shortName(input.names[id] ?? "");
  let body: string;
  if (per.length === 1 && covered.length === 1) {
    const take = covered[0].takes[covered[0].takes.length - 1];
    body = qs(covered[0].viaSection ? "alreadyRecordedWhole" : "alreadyRecordedOne", {
      target: input.targetLabel,
      name: nameOf(covered[0].artisanId),
      date: shortDate(take.session.date ?? take.capturedAt)
    });
  } else {
    body = qs("alreadyRecordedBody", { target: input.targetLabel, covered: covered.length, count: per.length });
  }
  const rows = covered.map((p) => {
    const take: CoverageTake = p.takes[p.takes.length - 1];
    return {
      artisanId: p.artisanId,
      line: [
        nameOf(p.artisanId),
        durationLabel(take.durationMs),
        take.recordedBy.name,
        shortDate(take.session.date ?? take.capturedAt),
        settingLabel(take.session.rosterCount)
      ]
        .filter(Boolean)
        .join(" · ")
    };
  });
  const first = per.length > 1 ? fresh.map((p) => qs("firstTakeFor", { name: nameOf(p.artisanId), target: input.code })) : [];
  const more: PromptButton[] = [];
  if (input.part2Allowed) more.push({ id: "part2", label: qs("continueAsPart2") });
  if (input.replaceAllowed) more.push({ id: "replace", label: qs("replaceEarlierTake") });
  more.push({ id: "different", label: qs("useDifferentQuestion") });
  return {
    spec: {
      id: "P2",
      title: qs("alreadyRecordedTitle"),
      body,
      buttons: [
        { id: "cancel", label: qs("cancel"), tone: "secondary" },
        { id: "confirm", label: qs(input.filePick ? "uploadAgain" : "recordAgain"), tone: "primary" }
      ],
      more,
      dismiss: "cancel"
    },
    covered: rows,
    first
  };
}

export const REASON_LABEL: Record<RepeatReason, string> = {
  REDO: qs("reasonRedo"),
  FOLLOW_UP: qs("reasonFollowUp"),
  RE_INTERVIEW: qs("reasonReInterview"),
  NEW_SETTING: qs("reasonNewSetting")
};

const two = (a: PromptButton, b: PromptButton): PromptButton[] => [a, b];

export const prompts = {
  p1: (v: { researcher: string; artisans: string; time: string }): PromptSpec => ({
    id: "P1",
    title: qs("openMatchTitle"),
    body: qs("openMatchBody", v),
    buttons: two({ id: "new", label: qs("openMatchNew"), tone: "secondary" }, { id: "join", label: qs("openMatchJoin"), tone: "primary" }),
    // Either button starts or joins a session: a dismissal does neither.
    dismiss: null
  }),
  p3: (v: { target: string; elapsed: string; code: string }): PromptSpec => ({
    id: "P3",
    title: qs("p3Title", v),
    body: qs("p3Body", { duration: v.elapsed }),
    buttons: two({ id: "keep", label: qs("p3Keep"), tone: "secondary" }, { id: "stop", label: qs("p3Stop", v), tone: "primary" }),
    dismiss: "keep"
  }),
  /** P5: same session → add the artisan; other session of the same sitting → merge; otherwise OK. */
  p5: (v: { name: string; date: string; case: "SAME_SESSION" | "OTHER_SESSION" | "OTHER"; addName?: string }): PromptSpec => ({
    id: "P5",
    title: qs("p5Title"),
    body: qs("p5Body", v),
    buttons:
      v.case === "SAME_SESSION"
        ? two({ id: "cancel", label: qs("cancel"), tone: "secondary" }, { id: "add", label: qs("p5AddTo", { name: v.addName ?? "" }), tone: "primary" })
        : v.case === "OTHER_SESSION"
          ? two({ id: "cancel", label: qs("cancel"), tone: "secondary" }, { id: "merge", label: qs("p5Merge"), tone: "primary" })
          : [{ id: "ok", label: qs("ok"), tone: "primary" }],
    dismiss: v.case === "OTHER" ? "ok" : "cancel"
  }),
  p7: (v: { position: number; target: string }): PromptSpec => ({
    id: "P7",
    title: qs("p7Title", { n: v.position, target: v.target }),
    body: qs("p7Body", { n: v.position, next: v.position + 1 }),
    buttons: two({ id: "cancel", label: qs("cancel"), tone: "secondary" }, { id: "replace", label: qs("replace"), tone: "primary" }),
    dismiss: "cancel"
  }),
  p7r: (v: { position: number; target: string }): PromptSpec => ({
    id: "P7r",
    title: qs("p7rTitle", { n: v.position, target: v.target }),
    body: qs("p7rBody"),
    buttons: two({ id: "cancel", label: qs("cancel"), tone: "secondary" }, { id: "remove", label: qs("remove"), tone: "danger" }),
    dismiss: "cancel"
  }),
  p8: (v: { name: string; code: string; researcher: string; date: string }): PromptSpec => ({
    id: "P8",
    title: qs("p8Title", v),
    body: qs("p8Body", v),
    buttons: two({ id: "keep", label: qs("keepCurrent"), tone: "secondary" }, { id: "replace", label: qs("replace"), tone: "primary" }),
    dismiss: "keep"
  }),
  p9: (v: { researcher: string; name: string; code: string; time: string }): PromptSpec => ({
    id: "P9",
    title: qs("p9Title"),
    body: qs("p9Body", v),
    buttons: two({ id: "theirs", label: qs("keepTheirs"), tone: "secondary" }, { id: "mine", label: qs("keepMine"), tone: "primary" }),
    // Both answers are kept until somebody chooses; the typed one waits in the outbox.
    dismiss: null
  }),
  p10: (v: { name: string; count: number }): PromptSpec => ({
    id: "P10",
    title: qs("p10Title", v),
    body: qs("p10Body", v),
    buttons: two({ id: "cancel", label: qs("cancel"), tone: "secondary" }, { id: "remove", label: qs("remove"), tone: "danger" }),
    dismiss: "cancel"
  }),
  p11: (v: { count: number }): PromptSpec => ({ id: "P11", title: null, body: qs("p11Hint", v), buttons: [], dismiss: null }),
  p12: (): PromptSpec => ({ id: "P12", title: null, body: qs("p12Hint"), buttons: [], dismiss: null }),
  p13: (v: { minutes: number; target: string }): PromptSpec => ({
    id: "P13",
    title: qs("p13Title"),
    body: qs("p13Body", v),
    buttons: two({ id: "discard", label: qs("discard"), tone: "secondary" }, { id: "keep", label: qs("keep"), tone: "primary" }),
    // A recovered take is the only copy there is: dismissing the prompt keeps it.
    dismiss: "keep"
  }),
  p14: (): PromptSpec => ({ id: "P14", title: null, body: qs("p14Banner"), buttons: [{ id: "update", label: qs("update"), tone: "primary" }], dismiss: null }),
  leave: (v: { count: number }): PromptSpec => ({
    id: "LEAVE",
    title: qs("leaveTitle"),
    body: qs("leaveBodyWeb", v),
    buttons: two({ id: "stay", label: qs("stay"), tone: "secondary" }, { id: "leave", label: qs("leave"), tone: "primary" }),
    dismiss: "stay"
  }),
  finish: (v: { sections: number; artisans: number; pending: number }): PromptSpec => ({
    id: "FINISH",
    title: qs("finishTitle"),
    body: qs("finishBody", v),
    buttons: [{ id: "finish", label: qs("finish"), tone: "primary" }],
    // Finishing closes the session: a dismissal leaves it open.
    dismiss: null
  })
};

/** "Take 2 · Redo · 6:05 min · Ankur · 9 Jul · Solo" — a take row (§4.3), never a bare file name. */
export function takeRowLabel(take: {
  position: number | null;
  repeatReason?: RepeatReason | null;
  durationMs: number | null;
  researcher: string;
  capturedAt: string;
  rosterCount: number;
}): string {
  return [
    take.position ? qs("takeN", { n: take.position }) : "",
    take.repeatReason ? REASON_LABEL[take.repeatReason] : "",
    durationLabel(take.durationMs),
    take.researcher,
    shortDate(take.capturedAt),
    settingLabel(take.rosterCount)
  ]
    .filter(Boolean)
    .join(" · ");
}
