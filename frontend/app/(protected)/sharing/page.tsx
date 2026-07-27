"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertTriangle, Check, Share2, X } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { Field, Select, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { RowActions, rowAction } from "@/components/RowActions";
import { useAuth } from "@/components/AuthProvider";
import { SearchableMultiSelect, type SelectOption } from "@/components/ui/SearchableSelect";
import { apiFetch, listResource } from "@/lib/api";
import { runPerPerson, type BatchOutcome, type BatchTarget } from "@/lib/sharingBatch";
import type {
  Artisan,
  DataAccessGrant,
  DataAccessStatus,
  DataAccessTier,
  MyGrants,
  ProductDocumentation,
  QuestionnaireInterview,
  TierInfo,
  ToolDocumentation,
  User,
  Workshop
} from "@/lib/types";

type OwnRecord = { recordType: string; recordId: string; label: string };

const TIER_LABEL: Record<DataAccessTier, string> = {
  DOWNLOAD: "Download (minimum)",
  COMMENT: "Comment (medium)",
  EDIT: "Edit (maximum)"
};

/**
 * The tier ladder as ranks, mirroring `TIER_ORDER` in `backend/app/services/access.py`.
 *
 * This is why the two tier pickers on this page stay SINGLE-select while the two people pickers
 * became multi. The tiers are cumulative, not a set of independent permissions: the backend compares
 * them with `tier_at_least`, a `>=` against these ranks, and its own catalogue text says so outright
 * — COMMENT is "everything in Download, plus…", EDIT is "everything in Comment, plus…". A person
 * holds exactly one rung. So a multi-select of "DOWNLOAD and EDIT" would have no meaning to express;
 * it is just EDIT, and offering it would invite an owner to think they had granted something
 * narrower than they had.
 */
const TIER_RANK: Record<DataAccessTier, number> = { DOWNLOAD: 1, COMMENT: 2, EDIT: 3 };

/** What a tier lets someone actually DO, for the one sentence in the confirm dialog. */
const TIER_CONSEQUENCE: Record<DataAccessTier, string> = {
  DOWNLOAD: "download",
  COMMENT: "download and comment on",
  EDIT: "download, comment on and edit"
};

/**
 * From how many people an action stops being routine and gets a confirm.
 *
 * Two, not one. Granting a single colleague has never asked for confirmation and must not start:
 * this is the everyday act the page exists for, it is visible in the table underneath the moment it
 * lands, and one Revoke click undoes it. What is new — and what earns the interruption — is that a
 * single "Select all" can now widen access to nineteen people's worth of someone else's unpublished
 * fieldwork in one press, which is not a mistake anyone notices from a green banner.
 */
const BULK_CONFIRM_AT = 2;

/** Names read out in full in a dialog before the tail collapses to a count. */
const NAMES_IN_PROSE = 6;

const STATUS_STYLE: Record<string, string> = {
  PENDING: "bg-amber-100 text-amber-800",
  GRANTED: "bg-emerald-100 text-emerald-800",
  DENIED: "bg-red-100 text-red-700",
  REVOKED: "bg-line-200 text-ink-700"
};

function StatusPill({ status }: { status: string }) {
  return <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${STATUS_STYLE[status] ?? "bg-line-200"}`}>{status}</span>;
}

function tierAtLeast(tier: DataAccessTier, min: DataAccessTier) {
  return TIER_RANK[tier] >= TIER_RANK[min];
}

function plural(count: number, noun: string) {
  return `${count} ${noun}${count === 1 ? "" : "s"}`;
}

function nameList(names: string[], max = NAMES_IN_PROSE) {
  if (names.length <= max) return names.join(", ");
  return `${names.slice(0, max).join(", ")} and ${plural(names.length - max, "other")}`;
}

// --------------------------------------------------------------------------- existing standing
//
// "Do not offer a person who already holds a grant as though they were new." Everything below turns
// the grant rows the page already loads into a one-line statement of where each person stands, so an
// owner reading the picker can see what they are CHANGING rather than only what they are choosing.

/** One person's existing relationship, flattened out of their single (owner, grantee) grant row. */
type Standing = { status: DataAccessStatus; tier: DataAccessTier; allData: boolean; keys: Set<string> };

/** A reach over records: everything, or an explicit set of `type::id` keys. */
type Scope = { allData: boolean; keys: Set<string> };

function scopeKeysOf(grant: DataAccessGrant) {
  return new Set((grant.scopeItems ?? []).map((item) => `${item.recordType}::${item.recordId}`));
}

/**
 * People indexed by id. Safe as a plain overwrite: the grant table is uniquely keyed on
 * (ownerId, granteeId), so a person can appear at most once on either side.
 */
function standingsBy(rows: DataAccessGrant[], personField: "granteeId" | "ownerId") {
  const map = new Map<string, Standing>();
  rows.forEach((row) =>
    map.set(row[personField], { status: row.status, tier: row.tier, allData: row.allData, keys: scopeKeysOf(row) })
  );
  return map;
}

/** Does `wider` reach every record `narrower` reaches? */
function covers(wider: Scope, narrower: Scope) {
  if (wider.allData) return true;
  if (narrower.allData) return false;
  for (const key of narrower.keys) if (!wider.keys.has(key)) return false;
  return true;
}

type Change = "new" | "same" | "raise" | "reduce";

/**
 * What pressing Grant would do to one person who already holds something.
 *
 * `reduce` is the case worth the trouble. The server's `_upsert_grant` writes the single
 * (owner, grantee) row in place and DELETES its scope items before rebuilding them, so a grant is a
 * replacement and not an addition: including a colleague who holds EDIT on everything in a bulk
 * "DOWNLOAD on 3 records" action silently strips the access they had. That is invisible from a
 * picker that only shows names, and it is the one outcome on this screen that destroys something.
 */
function classifyChange(standing: Standing | undefined, next: Scope & { tier: DataAccessTier }): Change {
  if (!standing || standing.status !== "GRANTED") return "new";
  const held: Scope = { allData: standing.allData, keys: standing.keys };
  if (TIER_RANK[next.tier] < TIER_RANK[standing.tier] || !covers(next, held)) return "reduce";
  if (TIER_RANK[next.tier] === TIER_RANK[standing.tier] && covers(held, next)) return "same";
  return "raise";
}

function scopeWords(standing: Standing) {
  return standing.allData ? "all data" : plural(standing.keys.size, "record");
}

/**
 * The tail of a picker row, for people I might grant access to.
 *
 * Kept short on purpose. The panel truncates a label that outruns its width, and the longest name
 * and address in this deployment already spend fifty characters before this suffix starts — so the
 * state has to survive in a handful of words or it is the part that gets cut off.
 */
function grantSuffix(standing: Standing | undefined, change: Change) {
  if (!standing) return "";
  switch (standing.status) {
    case "GRANTED":
      return change === "same"
        ? ` — ${standing.tier}, ${scopeWords(standing)} · no change`
        : ` — has ${standing.tier}, ${scopeWords(standing)}`;
    case "PENDING":
      return ` — asked for ${standing.tier}`;
    case "DENIED":
      return ` — you denied ${standing.tier}`;
    default:
      return ` — ${standing.tier} revoked`;
  }
}

/** The same tail for people I might request access FROM, worded from the other side. */
function requestSuffix(standing: Standing | undefined) {
  if (!standing) return "";
  switch (standing.status) {
    case "GRANTED":
      return ` — you already have ${standing.tier}`;
    case "PENDING":
      return " — request pending";
    case "DENIED":
      return " — previously denied";
    default:
      return " — your access was revoked";
  }
}

// --------------------------------------------------------------------------- the batch report

/** The single-person call this batch replays, held so Retry cannot drift from what was pressed. */
type Attempt =
  | { kind: "grant"; tier: DataAccessTier; allData: boolean; scopeItems: Array<{ recordType: string; recordId: string }> }
  | { kind: "request"; tier: DataAccessTier; requestNote?: string };

type Ledger = { attempt: Attempt; intent: string; succeeded: BatchOutcome[]; failed: BatchOutcome[] };

/**
 * Who worked, who did not, and why — shown only when something failed.
 *
 * A batch that goes through completely says so in the ordinary green banner and leaves the tables
 * below to name the people; a report would be noise. A batch that half-worked cannot be summarised
 * by either banner, because "Access granted" and "Action failed" are both lies about it.
 */
function BatchReport({ ledger, busy, onRetry, onDismiss }: { ledger: Ledger; busy: boolean; onRetry: () => void; onDismiss: () => void }) {
  const { succeeded, failed } = ledger;
  const noun = ledger.attempt.kind === "grant" ? "granted" : "sent";
  const total = succeeded.length + failed.length;

  return (
    <div className="mt-4 overflow-hidden rounded-md border border-amber-500/40">
      {/* The small status pills on this page keep a fixed light palette in both themes, but a band
          this wide would be a slab of cream across a dark screen, so it is tinted instead. */}
      <div className="flex items-start gap-2 bg-amber-100 px-3 py-2 text-amber-800 dark:bg-amber-500/15 dark:text-amber-100">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold">
            {succeeded.length} of {total} {noun}.
          </p>
          <p className="text-xs">{ledger.intent}</p>
        </div>
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss this report"
          className="-mr-1 shrink-0 rounded p-1 text-amber-800 transition hover:bg-amber-500/20 dark:text-amber-100"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      </div>
      <div className="bg-card p-3 text-sm">
        {succeeded.length ? (
          <>
            <p className="field-label">{ledger.attempt.kind === "grant" ? "Granted" : "Request sent"}</p>
            <ul className="mt-1 grid gap-1">
              {succeeded.map((row) => (
                <li key={row.id} className="flex items-start gap-2 text-ink-700">
                  <Check className="mt-0.5 h-4 w-4 shrink-0 text-emerald-700 dark:text-emerald-400" aria-hidden />
                  <span className="min-w-0">{row.label}</span>
                </li>
              ))}
            </ul>
          </>
        ) : null}
        <p className={`field-label ${succeeded.length ? "mt-3" : ""}`}>
          {ledger.attempt.kind === "grant" ? "Not granted" : "Not sent"}
        </p>
        <ul className="mt-1 grid gap-1">
          {failed.map((row) => (
            <li key={row.id} className="flex items-start gap-2 text-ink-700">
              <X className="mt-0.5 h-4 w-4 shrink-0 text-error-600 dark:text-red-400" aria-hidden />
              <span className="min-w-0">
                <span className="font-medium text-ink-900">{row.label}</span> — {row.error}
                {row.status ? <span className="text-ink-500"> (HTTP {row.status})</span> : null}
              </span>
            </li>
          ))}
        </ul>
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <button className="field-button" disabled={busy} onClick={onRetry}>
            Retry {failed.length === 1 ? "the one that failed" : `the ${failed.length} that failed`}
          </button>
          {succeeded.length ? (
            // Said out loud because the reader's actual worry about a Retry button is that it will
            // do the successful half twice. It cannot: the server keeps one row per person and
            // rewrites it in place, and this retries only the failures anyway.
            <p className="min-w-0 flex-1 text-xs text-ink-500">
              The {succeeded.length} above {succeeded.length === 1 ? "keeps its" : "keep their"} access — retrying
              re-sends only the {failed.length} that failed, and cannot duplicate anything.
            </p>
          ) : null}
        </div>
      </div>
    </div>
  );
}

export default function SharingPage() {
  const confirm = useConfirm();
  const { user: currentUser } = useAuth();
  const [grants, setGrants] = useState<MyGrants | null>(null);
  const [tiers, setTiers] = useState<TierInfo[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Carries WHICH of the two forms is running, so the progress line appears under the button that
  // was pressed rather than under both of them.
  const [progress, setProgress] = useState<{ kind: Attempt["kind"]; done: number; total: number } | null>(null);
  const [ledger, setLedger] = useState<Ledger | null>(null);

  // Request form state
  const [reqOwnerIds, setReqOwnerIds] = useState<string[]>([]);
  const [reqOwnerText, setReqOwnerText] = useState("");
  const [reqTier, setReqTier] = useState<DataAccessTier>("DOWNLOAD");
  const [reqNote, setReqNote] = useState("");

  // Direct-grant form state (owner grants colleagues access to all, or a chosen subset, of their data)
  const [grantGranteeIds, setGrantGranteeIds] = useState<string[]>([]);
  const [grantTier, setGrantTier] = useState<DataAccessTier>("DOWNLOAD");
  const [grantScopeAll, setGrantScopeAll] = useState(true);
  const [myRecords, setMyRecords] = useState<OwnRecord[] | null>(null);
  const [loadingRecords, setLoadingRecords] = useState(false);
  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set());

  async function loadMyRecords() {
    if (myRecords || loadingRecords || !currentUser?.id) return;
    setLoadingRecords(true);
    try {
      const mine = currentUser.id;
      const [a, p, t, w, q] = await Promise.all([
        listResource<Artisan>("/artisans", { pageSize: 100 }),
        listResource<ProductDocumentation>("/products", { pageSize: 100 }),
        listResource<ToolDocumentation>("/tools", { pageSize: 100 }),
        listResource<Workshop>("/workshops", { pageSize: 100 }),
        listResource<QuestionnaireInterview>("/questionnaire/interviews", { pageSize: 100 })
      ]);
      const recs: OwnRecord[] = [
        ...a.items.filter((x) => x.createdById === mine).map((x) => ({ recordType: "artisan", recordId: x.id, label: `Artisan · ${x.name}` })),
        ...p.items.filter((x) => x.createdById === mine).map((x) => ({ recordType: "product", recordId: x.id, label: `Product · ${x.productName}` })),
        ...t.items.filter((x) => x.createdById === mine).map((x) => ({ recordType: "tool", recordId: x.id, label: `Tool · ${x.toolkitName}` })),
        ...w.items.filter((x) => x.createdById === mine).map((x) => ({ recordType: "workshop", recordId: x.id, label: `Workshop · ${x.title}` })),
        ...q.items.filter((x) => x.createdById === mine).map((x) => ({ recordType: "questionnaire", recordId: x.id, label: `Interview · ${x.title}` }))
      ];
      setMyRecords(recs);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load your records");
    } finally {
      setLoadingRecords(false);
    }
  }

  function toggleRecord(key: string) {
    setSelectedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  const load = useCallback(async () => {
    try {
      const [g, t, u] = await Promise.all([
        apiFetch<MyGrants>("/data-access/grants"),
        apiFetch<TierInfo[]>("/data-access/tiers"),
        apiFetch<User[]>("/users/directory").catch(() => [] as User[])
      ]);
      setGrants(g);
      setTiers(t);
      setUsers((u ?? []).filter((x) => x.id !== currentUser?.id));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load sharing data");
    }
  }, [currentUser?.id]);

  useEffect(() => {
    load();
  }, [load]);

  // Researchers can't list /users (admin-only). Fall back to a free-text owner id only if needed.
  const canPickUsers = users.length > 0;

  const ownerNameById = useMemo(() => {
    const map = new Map<string, string>();
    users.forEach((u) => map.set(u.id, `${u.name} (${u.email})`));
    return map;
  }, [users]);

  /** Just the person's name, for prose. The picker rows carry the address; a sentence should not. */
  const plainNameById = useMemo(() => {
    const map = new Map<string, string>();
    users.forEach((u) => map.set(u.id, u.name));
    return map;
  }, [users]);

  const incoming = useMemo(() => grants?.incoming ?? [], [grants]);
  const outgoing = useMemo(() => grants?.outgoing ?? [], [grants]);

  const granteeStandings = useMemo(() => standingsBy(incoming, "granteeId"), [incoming]);
  const ownerStandings = useMemo(() => standingsBy(outgoing, "ownerId"), [outgoing]);

  const nextScope: Scope = useMemo(
    () => ({ allData: grantScopeAll, keys: selectedKeys }),
    [grantScopeAll, selectedKeys]
  );

  /**
   * The colleague picker's rows, each carrying what that person holds today.
   *
   * Recomputed against the current tier and scope so "no change" means no change to what is on
   * screen right now, not to some earlier draft of the action.
   */
  const grantOptions: SelectOption[] = useMemo(
    () =>
      users.map((u) => {
        const standing = granteeStandings.get(u.id);
        return {
          value: u.id,
          label: `${u.name} · ${u.email}${grantSuffix(standing, classifyChange(standing, { ...nextScope, tier: grantTier }))}`
        };
      }),
    [users, granteeStandings, nextScope, grantTier]
  );

  /**
   * The researcher picker's rows.
   *
   * Anyone whose data I already hold an active grant on is DISABLED rather than merely annotated,
   * because the server refuses that request outright — `request_access` 409s on an existing GRANTED
   * row so that re-asking cannot knock a working grant back to PENDING. Offering the row anyway
   * would mean "Select all" reliably produced failures nobody could have avoided; disabled rows are
   * also skipped by the panel's own select-all, so the bulk action stays clean by construction.
   */
  const requestOptions: SelectOption[] = useMemo(
    () =>
      users.map((u) => {
        const standing = ownerStandings.get(u.id);
        return {
          value: u.id,
          label: `${u.name} · ${u.email}${requestSuffix(standing)}`,
          disabled: standing?.status === "GRANTED"
        };
      }),
    [users, ownerStandings]
  );

  /** Colleagues in the current selection whose existing access this action would cut back. */
  const reductions = useMemo(
    () =>
      grantGranteeIds
        .map((id) => ({ id, standing: granteeStandings.get(id) }))
        .filter(
          (entry): entry is { id: string; standing: Standing } =>
            Boolean(entry.standing) && classifyChange(entry.standing, { ...nextScope, tier: grantTier }) === "reduce"
        )
        .map(({ id, standing }) => ({
          id,
          name: plainNameById.get(id) ?? id,
          held: `${standing.tier}, ${scopeWords(standing)}`
        })),
    [grantGranteeIds, granteeStandings, nextScope, grantTier, plainNameById]
  );

  const grantScopePhrase = grantScopeAll ? "all your data" : `${plural(selectedKeys.size, "selected record")}`;

  /**
   * The sentence to check before pressing. One scope and one tier apply to EVERY person chosen, and
   * that is exactly the thing a row of separate controls does not say out loud.
   */
  const grantSentence =
    grantGranteeIds.length === 0
      ? "Choose one or more colleagues, then press Grant."
      : `Grant ${grantTier} on ${grantScopePhrase} to ${
          grantGranteeIds.length === 1
            ? plainNameById.get(grantGranteeIds[0]) ?? "1 colleague"
            : plural(grantGranteeIds.length, "colleague")
        }.`;

  const requestCount = canPickUsers ? reqOwnerIds.length : reqOwnerText.trim() ? 1 : 0;
  const requestSentence =
    requestCount === 0
      ? "Choose one or more researchers, then press Request."
      : `Request ${reqTier} access to all data from ${
          requestCount === 1
            ? (canPickUsers ? plainNameById.get(reqOwnerIds[0]) : null) ?? "1 researcher"
            : plural(requestCount, "researcher")
        }.`;

  async function act<T>(fn: () => Promise<T>, ok: string) {
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      await fn();
      setMessage(ok);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Action failed");
    } finally {
      setBusy(false);
    }
  }

  /**
   * The fan-out: one POST per person, no rollback, a named reason for each one that fails.
   *
   * `runPerPerson` never throws — a batch always produces a full account — so everything after the
   * await is about telling the truth and leaving the screen in a state the reader can act from. On a
   * partial failure the picker is reset to exactly the people who did NOT go through, so both the
   * Retry button and a second press of Grant mean the same, obvious thing.
   */
  async function runPeople(attempt: Attempt, intent: string, targets: BatchTarget[]) {
    setBusy(true);
    setError(null);
    setMessage(null);
    setLedger(null);
    setProgress({ kind: attempt.kind, done: 0, total: targets.length });

    const result = await runPerPerson(
      targets,
      (target) =>
        attempt.kind === "grant"
          ? apiFetch("/data-access/grants", {
              method: "POST",
              body: JSON.stringify({
                granteeId: target.id,
                tier: attempt.tier,
                allData: attempt.allData,
                scopeItems: attempt.scopeItems
              })
            })
          : apiFetch("/data-access/requests", {
              method: "POST",
              body: JSON.stringify({ ownerId: target.id, tier: attempt.tier, allData: true, requestNote: attempt.requestNote })
            }),
      (done, total) => setProgress({ kind: attempt.kind, done, total })
    );

    setProgress(null);
    // Reload before the messaging, so the tables and the picker's standing labels already agree with
    // whatever the report is about to claim.
    await load();
    setBusy(false);

    const failedIds = result.failed.map((row) => row.id);
    if (attempt.kind === "grant") {
      setGrantGranteeIds(failedIds);
      if (!failedIds.length) {
        setSelectedKeys(new Set());
        setGrantScopeAll(true);
      }
    } else {
      setReqOwnerIds(failedIds);
      if (!failedIds.length) {
        setReqNote("");
        setReqOwnerText("");
      }
    }

    if (!result.failed.length) {
      setMessage(
        attempt.kind === "grant"
          ? `Access granted to ${plural(result.succeeded.length, "colleague")}.`
          : `Sent ${plural(result.succeeded.length, "request")}.`
      );
      return;
    }
    setLedger({ attempt, intent, succeeded: result.succeeded, failed: result.failed });
  }

  function targetsFor(ids: string[]): BatchTarget[] {
    return ids.map((id) => ({ id, label: ownerNameById.get(id) ?? id }));
  }

  async function submitRequest() {
    const ids = canPickUsers ? reqOwnerIds : reqOwnerText.trim() ? [reqOwnerText.trim()] : [];
    if (!ids.length) {
      setError("Choose at least one researcher to request access from.");
      return;
    }
    const names = ids.map((id) => plainNameById.get(id) ?? id);
    // A request exposes nothing, so this is not the permissions warning the grant side gets — it is
    // a guard against one "Select all" quietly putting a request in front of every colleague at once.
    if (ids.length >= BULK_CONFIRM_AT) {
      const ok = await confirm({
        title: `Send ${plural(ids.length, "access request")}?`,
        body: (
          <>
            <span className="font-medium text-ink-900">{nameList(names)}</span> will each be asked for{" "}
            <span className="font-medium text-ink-900">{reqTier}</span> access to all of their data.
          </>
        ),
        note: "Nothing is shared until each of them approves, and you can withdraw any request from the list below.",
        confirmLabel: `Send ${ids.length} requests`
      });
      if (!ok) return;
    }
    await runPeople({ kind: "request", tier: reqTier, requestNote: reqNote.trim() || undefined }, requestSentence, targetsFor(ids));
  }

  async function submitGrant() {
    if (!grantGranteeIds.length) {
      setError("Choose at least one colleague to grant access to.");
      return;
    }
    const scopeItems = grantScopeAll
      ? []
      : Array.from(selectedKeys).map((k) => {
          const [recordType, recordId] = k.split("::");
          return { recordType, recordId };
        });
    if (!grantScopeAll && scopeItems.length === 0) {
      setError("Pick at least one record to share, or choose All my data.");
      return;
    }
    const names = grantGranteeIds.map((id) => plainNameById.get(id) ?? id);
    if (grantGranteeIds.length >= BULK_CONFIRM_AT) {
      // Red, not amber, in the two cases that are not merely consequential: handing several people
      // the maximum tier over everything, and any action that destroys access somebody already has.
      const severe = reductions.length > 0 || (grantTier === "EDIT" && grantScopeAll);
      const ok = await confirm({
        title: `Grant ${grantTier} to ${plural(grantGranteeIds.length, "colleague")}?`,
        body: (
          <>
            <span className="font-medium text-ink-900">{nameList(names)}</span> will be able to{" "}
            {TIER_CONSEQUENCE[grantTier]} <span className="font-medium text-ink-900">{grantScopePhrase}</span> straight
            away — a direct grant has no approval step.
          </>
        ),
        note: (
          <>
            {reductions.length ? (
              <span className="block font-medium text-ink-900">
                This LOWERS access {reductions.length === 1 ? "someone" : `${reductions.length} of them`} already{" "}
                {reductions.length === 1 ? "holds" : "hold"}:{" "}
                {reductions.map((r) => `${r.name} (${r.held})`).join(", ")}. One tier and one scope apply to everyone
                chosen, so their existing grant is replaced, not kept.
              </span>
            ) : null}
            <span className="block">You can revoke any of them individually from the list below at any time.</span>
          </>
        ),
        tone: severe ? "danger" : "warning",
        confirmLabel: `Grant to ${grantGranteeIds.length}`
      });
      if (!ok) return;
    }
    await runPeople({ kind: "grant", tier: grantTier, allData: grantScopeAll, scopeItems }, grantSentence, targetsFor(grantGranteeIds));
  }

  function retryLedger() {
    if (!ledger) return;
    runPeople(ledger.attempt, ledger.intent, ledger.failed.map((row) => ({ id: row.id, label: row.label })));
  }

  async function decide(grant: DataAccessGrant, status: "GRANTED" | "DENIED", tier?: DataAccessTier) {
    const who = grant.grantee?.name ?? grant.granteeId;
    // Denying is reversible — the requester can ask again, and the owner can grant later — so this is
    // amber rather than red. Granting needs no confirmation at all.
    if (status === "DENIED") {
      const ok = await confirm({
        title: "Deny this request?",
        body: (
          <>
            <span className="font-medium text-ink-900">{who}</span> will not get access to your data, and will see the
            request as denied.
          </>
        ),
        note: "They can request access again, and you can grant it at any time.",
        tone: "warning",
        confirmLabel: "Deny request"
      });
      if (!ok) return;
    }
    await act(
      () =>
        apiFetch(`/data-access/grants/${grant.id}/decide`, {
          method: "POST",
          body: JSON.stringify({ status, tier: tier ?? grant.tier })
        }),
      status === "GRANTED" ? "Access granted." : "Request denied."
    );
  }

  async function changeTier(grant: DataAccessGrant, tier: DataAccessTier) {
    await act(() => apiFetch(`/data-access/grants/${grant.id}`, { method: "PATCH", body: JSON.stringify({ tier }) }), "Tier updated.");
  }

  async function revoke(grant: DataAccessGrant) {
    const who = grant.grantee?.name ?? grant.granteeId;
    const ok = await confirm({
      title: "Revoke this access?",
      body: (
        <>
          <span className="font-medium text-ink-900">{who}</span> loses access to your data immediately, including
          anything they were part-way through downloading.
        </>
      ),
      note: "Comments and edits they already made are kept.",
      tone: "danger",
      confirmLabel: "Revoke access"
    });
    if (!ok) return;
    await act(() => apiFetch(`/data-access/grants/${grant.id}/revoke`, { method: "POST" }), "Access revoked.");
  }

  // Destructive on both sides of the table: as owner it deletes a denied/revoked row, as grantee it
  // withdraws a pending request or drops access already held. Confirm before it fires.
  async function remove(grant: DataAccessGrant) {
    const ok = await confirm({
      ...deleteConfirm(
        "Remove this sharing entry?",
        "This permanently deletes the grant record, along with the history of who asked for what and when.",
        "As the owner this clears a denied or revoked row; as the requester it withdraws the request or drops access you hold."
      ),
      confirmLabel: "Remove entry"
    });
    if (!ok) return;
    await act(() => apiFetch(`/data-access/grants/${grant.id}`, { method: "DELETE" }), "Removed.");
  }

  async function downloadOwnerData(ownerId: string, ownerLabel: string) {
    let capped = false;
    await act(async () => {
      const manifest = await apiFetch<{
        files: Array<{ path: string; url?: string | null; content?: string | null }>;
        totalFiles: number;
        totalMedia: number;
        /** The server hit a per-table row cap: this archive is a prefix of the data, not all of it. */
        truncated?: boolean;
      }>(`/export/dataset?ownerId=${encodeURIComponent(ownerId)}`);
      capped = Boolean(manifest.truncated);
      // Assemble a real zip in the browser: text entries inline, media fetched from storage.
      const { default: JSZip } = await import("jszip");
      const zip = new JSZip();
      const failed: string[] = [];
      let done = 0;
      for (const file of manifest.files) {
        done += 1;
        setMessage(`Preparing download… ${done}/${manifest.files.length}`);
        if (file.content != null) {
          zip.file(file.path, file.content);
          continue;
        }
        if (!file.url) {
          failed.push(file.path);
          continue;
        }
        try {
          const response = await fetch(file.url);
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          zip.file(file.path, await response.blob());
        } catch {
          failed.push(file.path);
        }
      }
      if (failed.length) {
        zip.file(
          "_failed-downloads.txt",
          `These files could not be fetched and are not in the archive:\n\n${failed.join("\n")}`
        );
      }
      const blob = await zip.generateAsync({ type: "blob" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `data-${ownerLabel.replace(/[^A-Za-z0-9]+/g, "_")}.zip`;
      a.click();
      URL.revokeObjectURL(url);
    }, "Download ready.");
    // Said AFTER `act`, which writes its own success message last. A partial archive that presents
    // itself as complete is worse than a failed one, because nobody goes back for the rest.
    if (capped) {
      setMessage(
        "Download ready — but this export hit the server's row cap, so it does NOT contain all of " +
          `${ownerLabel}'s data. Ask an admin for a full extract.`
      );
    }
  }

  function progressLine(kind: Attempt["kind"]) {
    if (!progress || progress.kind !== kind) return null;
    return `Working… ${progress.done} of ${progress.total} sent.`;
  }

  return (
    <>
      <PageHeader
        title="Sharing"
        description="Request access to another researcher's data, and manage who can use yours — at three tiers."
        icon={<Share2 className="h-5 w-5" aria-hidden />}
      />

      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {message ? <div className="mb-4 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">{message}</div> : null}

      {/* Tier definitions, shown so a user knows exactly what each tier confers. */}
      <section className="panel mb-5 p-4">
        <h2 className="font-display font-bold text-lg text-ink">Access tiers</h2>
        <ul className="mt-2 grid gap-2 md:grid-cols-3">
          {tiers.map((t) => (
            <li key={t.tier} className="rounded-md border border-line-200 bg-field-50 p-3 text-sm">
              <div className="font-semibold text-ink">{TIER_LABEL[t.tier]}</div>
              <div className="mt-1 text-ink-muted">{t.description}</div>
            </li>
          ))}
        </ul>
      </section>

      {/* Request access from one or more researchers. */}
      <section className="panel mb-5 p-4">
        <h2 className="font-display font-bold text-lg text-ink">Request access to researchers&apos; data</h2>
        <div className="mt-3 grid gap-3 md:grid-cols-[2fr_1.4fr_2fr_auto] md:items-end">
          <Field label="Researchers">
            {canPickUsers ? (
              <SearchableMultiSelect
                values={reqOwnerIds}
                onChange={setReqOwnerIds}
                options={requestOptions}
                placeholder="Select…"
                ariaLabel="Researchers to request access from"
                disabled={busy}
                // No Confirm button inside the panel: the real commit is the Request button sitting
                // beside it, and two buttons that both look like "done" on a permissions form is how
                // someone sends the wrong thing. Clicking Request closes the panel and fires in one
                // press, so single-person use is no slower than the old single select.
                confirmOnSelect={false}
              />
            ) : (
              <TextInput value={reqOwnerText} onChange={(e) => setReqOwnerText(e.target.value)} placeholder="Researcher user id" />
            )}
          </Field>
          {/* Single-select on purpose: the tiers are a ladder, not a set. See TIER_RANK. */}
          <Field label="Tier">
            <Select value={reqTier} onChange={(e) => setReqTier(e.target.value as DataAccessTier)}>
              <option value="DOWNLOAD">{TIER_LABEL.DOWNLOAD}</option>
              <option value="COMMENT">{TIER_LABEL.COMMENT}</option>
              <option value="EDIT">{TIER_LABEL.EDIT}</option>
            </Select>
          </Field>
          <Field label="Note (optional)">
            <TextInput value={reqNote} onChange={(e) => setReqNote(e.target.value)} placeholder="Why you need access" />
          </Field>
          <button className="field-button" disabled={busy} onClick={submitRequest}>
            Request
          </button>
        </div>
        {/* Below the button row, never above it: this block grows and shrinks with the selection, and
            a control that moves between the press and the release is a control that misses. */}
        <p className="mt-3 rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm font-medium text-ink-900">
          {progressLine("request") ?? requestSentence}
        </p>
        <p className="mt-2 text-xs text-ink-muted">
          One tier applies to every researcher chosen. Requests cover all of that researcher&apos;s data — the owner can
          narrow it to a subset when they approve.
        </p>
        {ledger?.attempt.kind === "request" ? (
          <BatchReport ledger={ledger} busy={busy} onRetry={retryLedger} onDismiss={() => setLedger(null)} />
        ) : null}
      </section>

      {/* Grant access directly — owner shares all, or a chosen subset, of their own data. */}
      <section className="panel mb-5 p-4">
        <h2 className="font-display font-bold text-lg text-ink">Grant access to your data</h2>
        <div className="mt-3 grid gap-3 md:grid-cols-[2fr_1.4fr_auto] md:items-end">
          <Field label="Colleagues">
            <SearchableMultiSelect
              values={grantGranteeIds}
              onChange={setGrantGranteeIds}
              options={grantOptions}
              placeholder="Select…"
              ariaLabel="Colleagues to grant access to"
              disabled={busy}
              confirmOnSelect={false}
            />
          </Field>
          {/* Single-select on purpose: the tiers are a ladder, not a set. See TIER_RANK. */}
          <Field label="Tier">
            <Select value={grantTier} onChange={(e) => setGrantTier(e.target.value as DataAccessTier)}>
              <option value="DOWNLOAD">{TIER_LABEL.DOWNLOAD}</option>
              <option value="COMMENT">{TIER_LABEL.COMMENT}</option>
              <option value="EDIT">{TIER_LABEL.EDIT}</option>
            </Select>
          </Field>
          <button className="field-button" disabled={busy} onClick={submitGrant}>
            Grant
          </button>
        </div>
        <p className="mt-3 rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm font-medium text-ink-900">
          {progressLine("grant") ?? grantSentence}
        </p>
        {reductions.length ? (
          <p className="mt-2 flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-xs text-amber-800 dark:bg-amber-500/15 dark:text-amber-100">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <span>
              This would LOWER access {reductions.length === 1 ? "one of them" : `${reductions.length} of them`} already{" "}
              {reductions.length === 1 ? "holds" : "hold"}: {reductions.map((r) => `${r.name} (${r.held})`).join(", ")}. A
              grant replaces their existing one rather than adding to it.
            </span>
          </p>
        ) : null}
        <div className="mt-3 flex flex-wrap gap-4 text-sm">
          <label className="flex items-center gap-2">
            <input
              type="radio"
              name="grantScope"
              checked={grantScopeAll}
              onChange={() => setGrantScopeAll(true)}
            />
            All my data
          </label>
          <label className="flex items-center gap-2">
            <input
              type="radio"
              name="grantScope"
              checked={!grantScopeAll}
              onChange={() => {
                setGrantScopeAll(false);
                loadMyRecords();
              }}
            />
            Only selected records
          </label>
        </div>
        {!grantScopeAll ? (
          <div className="mt-2 max-h-64 overflow-y-auto rounded-md border border-line-200 bg-field-50 p-2">
            {loadingRecords ? (
              <p className="px-2 py-1 text-sm text-ink-muted">Loading your records…</p>
            ) : (myRecords ?? []).length === 0 ? (
              <p className="px-2 py-1 text-sm text-ink-muted">You have no records to share.</p>
            ) : (
              (myRecords ?? []).map((r) => {
                const key = `${r.recordType}::${r.recordId}`;
                return (
                  <label key={key} className="flex items-center gap-2 rounded px-2 py-1 hover:bg-field-100">
                    <input type="checkbox" checked={selectedKeys.has(key)} onChange={() => toggleRecord(key)} />
                    <span className="min-w-0 flex-1 truncate text-sm text-ink">{r.label}</span>
                  </label>
                );
              })
            )}
          </div>
        ) : null}
        <p className="mt-2 text-xs text-ink-muted">
          Granted immediately. One tier and one scope apply to every colleague chosen — the recipients can download
          (and, at higher tiers, comment on or edit) exactly what you share here.
        </p>
        {ledger?.attempt.kind === "grant" ? (
          <BatchReport ledger={ledger} busy={busy} onRetry={retryLedger} onDismiss={() => setLedger(null)} />
        ) : null}
      </section>

      {/* Incoming: requests and grants on MY data. */}
      <section className="panel mb-5 overflow-hidden">
        <div className="border-b border-line-200 p-4">
          <h2 className="font-display font-bold text-lg text-ink">Access to your data</h2>
          <p className="text-sm text-ink-muted">People who requested or hold access to data you uploaded.</p>
        </div>
        {incoming.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No requests yet" />
          </div>
        ) : (
          <ul className="divide-y divide-[#efe9e2]">
            {incoming.map((g) => (
              <li key={g.id} className="flex flex-wrap items-center gap-3 p-4">
                <div className="min-w-0 flex-1">
                  <div className="font-medium text-ink">{g.grantee?.name ?? ownerNameById.get(g.granteeId) ?? g.granteeId}</div>
                  <div className="text-xs text-ink-muted">
                    {g.grantee?.email} · {g.allData ? "All data" : `${g.scopeItems?.length ?? 0} records`} {g.requestNote ? `· “${g.requestNote}”` : ""}
                  </div>
                </div>
                <StatusPill status={g.status} />
                <Select className="max-w-44" value={g.tier} onChange={(e) => changeTier(g, e.target.value as DataAccessTier)} disabled={busy || g.status !== "GRANTED"}>
                  <option value="DOWNLOAD">{TIER_LABEL.DOWNLOAD}</option>
                  <option value="COMMENT">{TIER_LABEL.COMMENT}</option>
                  <option value="EDIT">{TIER_LABEL.EDIT}</option>
                </Select>
                <RowActions>
                  {g.status === "PENDING" ? (
                    <>
                      <button className="field-button" disabled={busy} onClick={() => decide(g, "GRANTED")}>
                        Approve
                      </button>
                      <button className={rowAction("danger")} disabled={busy} onClick={() => decide(g, "DENIED")}>
                        Deny
                      </button>
                    </>
                  ) : g.status === "GRANTED" ? (
                    <button className={rowAction("danger")} disabled={busy} onClick={() => revoke(g)}>
                      Revoke
                    </button>
                  ) : (
                    <button className={rowAction("neutral")} disabled={busy} onClick={() => remove(g)}>
                      Remove
                    </button>
                  )}
                </RowActions>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* Outgoing: access I hold on others' data. */}
      <section className="panel overflow-hidden">
        <div className="border-b border-line-200 p-4">
          <h2 className="font-display font-bold text-lg text-ink">Your access to others&apos; data</h2>
          <p className="text-sm text-ink-muted">Data you requested or were granted access to.</p>
        </div>
        {outgoing.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No access yet" />
          </div>
        ) : (
          <ul className="divide-y divide-[#efe9e2]">
            {outgoing.map((g) => (
              <li key={g.id} className="flex flex-wrap items-center gap-3 p-4">
                <div className="min-w-0 flex-1">
                  <div className="font-medium text-ink">{g.owner?.name ?? ownerNameById.get(g.ownerId) ?? g.ownerId}</div>
                  <div className="text-xs text-ink-muted">
                    {g.owner?.email} · {TIER_LABEL[g.tier]} · {g.allData ? "All data" : `${g.scopeItems?.length ?? 0} records`}
                  </div>
                </div>
                <StatusPill status={g.status} />
                <RowActions>
                  {g.status === "GRANTED" && tierAtLeast(g.tier, "DOWNLOAD") ? (
                    <button className="field-button" disabled={busy} onClick={() => downloadOwnerData(g.ownerId, g.owner?.name ?? g.ownerId)}>
                      Download data
                    </button>
                  ) : null}
                  <button className={rowAction("neutral")} disabled={busy} onClick={() => remove(g)}>
                    {g.status === "PENDING" ? "Withdraw" : "Remove"}
                  </button>
                </RowActions>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
