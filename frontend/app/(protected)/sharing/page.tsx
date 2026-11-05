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
import { apiFetch } from "@/lib/api";
import { listAllPages } from "@/lib/listAllPages";
import { runPerPerson, type BatchOutcome, type BatchTarget } from "@/lib/sharingBatch";
import { saveBlobToDevice } from "@/lib/saveToDevice";
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
import { ButtonRow } from "@/components/ui/ButtonRow";

type OwnRecord = { recordType: string; recordId: string; label: string };

const TIER_LABEL: Record<DataAccessTier, string> = {
  DOWNLOAD: "Download (minimum)",
  COMMENT: "Comment (medium)",
  EDIT: "Edit (maximum)"
};

const TIER_RANK: Record<DataAccessTier, number> = { DOWNLOAD: 1, COMMENT: 2, EDIT: 3 };

const TIER_CONSEQUENCE: Record<DataAccessTier, string> = {
  DOWNLOAD: "download",
  COMMENT: "download and comment on",
  EDIT: "download, comment on and edit"
};

const BULK_CONFIRM_AT = 2;

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

type Standing = { status: DataAccessStatus; tier: DataAccessTier; allData: boolean; keys: Set<string> };

type Scope = { allData: boolean; keys: Set<string> };

function scopeKeysOf(grant: DataAccessGrant) {
  return new Set((grant.scopeItems ?? []).map((item) => `${item.recordType}::${item.recordId}`));
}

function standingsBy(rows: DataAccessGrant[], personField: "granteeId" | "ownerId") {
  const map = new Map<string, Standing>();
  rows.forEach((row) =>
    map.set(row[personField], { status: row.status, tier: row.tier, allData: row.allData, keys: scopeKeysOf(row) })
  );
  return map;
}

function covers(wider: Scope, narrower: Scope) {
  if (wider.allData) return true;
  if (narrower.allData) return false;
  for (const key of narrower.keys) if (!wider.keys.has(key)) return false;
  return true;
}

type Change = "new" | "same" | "raise" | "reduce";

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

type Attempt =
  | { kind: "grant"; tier: DataAccessTier; allData: boolean; scopeItems: Array<{ recordType: string; recordId: string }> }
  | { kind: "request"; tier: DataAccessTier; requestNote?: string };

type Ledger = { attempt: Attempt; intent: string; succeeded: BatchOutcome[]; failed: BatchOutcome[] };

function BatchReport({ ledger, busy, onRetry, onDismiss }: { ledger: Ledger; busy: boolean; onRetry: () => void; onDismiss: () => void }) {
  const { succeeded, failed } = ledger;
  const noun = ledger.attempt.kind === "grant" ? "granted" : "sent";
  const total = succeeded.length + failed.length;

  return (
    <div className="mt-4 overflow-hidden rounded-md border border-amber-500/40">
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
              </span>
            </li>
          ))}
        </ul>
        <ButtonRow className="mt-3">
          <button className="field-button" disabled={busy} onClick={onRetry}>
            Retry {failed.length === 1 ? "the one that failed" : `the ${failed.length} that failed`}
          </button>
        </ButtonRow>
        {succeeded.length ? (
          <p className="mt-2 text-center text-xs text-ink-500">Only the failed ones are sent again.</p>
        ) : null}
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
  const [progress, setProgress] = useState<{ kind: Attempt["kind"]; done: number; total: number } | null>(null);
  const [ledger, setLedger] = useState<Ledger | null>(null);

  const [reqOwnerIds, setReqOwnerIds] = useState<string[]>([]);
  const [reqOwnerText, setReqOwnerText] = useState("");
  const [reqTier, setReqTier] = useState<DataAccessTier>("DOWNLOAD");
  const [reqNote, setReqNote] = useState("");

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
        listAllPages<Artisan>("/artisans", { pageSize: 100, createdBy: mine }),
        listAllPages<ProductDocumentation>("/products", { pageSize: 100, createdBy: mine }),
        listAllPages<ToolDocumentation>("/tools", { pageSize: 100, createdBy: mine }),
        listAllPages<Workshop>("/workshops", { pageSize: 100, createdBy: mine }),
        listAllPages<QuestionnaireInterview>("/questionnaire/interviews", { pageSize: 100, createdBy: mine })
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

  const canPickUsers = users.length > 0;

  const ownerNameById = useMemo(() => {
    const map = new Map<string, string>();
    users.forEach((u) => map.set(u.id, `${u.name} (${u.email})`));
    return map;
  }, [users]);

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

  const grantSentence =
    grantGranteeIds.length === 0
      ? "Choose colleagues."
      : `Grant ${grantTier} on ${grantScopePhrase} to ${
          grantGranteeIds.length === 1
            ? plainNameById.get(grantGranteeIds[0]) ?? "1 colleague"
            : plural(grantGranteeIds.length, "colleague")
        }.`;

  const requestCount = canPickUsers ? reqOwnerIds.length : reqOwnerText.trim() ? 1 : 0;
  const requestSentence =
    requestCount === 0
      ? "Choose researchers."
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
      setError("Choose at least one researcher.");
      return;
    }
    const names = ids.map((id) => plainNameById.get(id) ?? id);
    if (ids.length >= BULK_CONFIRM_AT) {
      const ok = await confirm({
        title: `Send ${plural(ids.length, "access request")}?`,
        body: (
          <>
            <span className="font-medium text-ink-900">{nameList(names)}</span> will each be asked for{" "}
            <span className="font-medium text-ink-900">{reqTier}</span> access to all of their data.
          </>
        ),
        note: "Nothing is shared until they approve.",
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
      setError("Pick a record, or choose All my data.");
      return;
    }
    const names = grantGranteeIds.map((id) => plainNameById.get(id) ?? id);
    if (grantGranteeIds.length >= BULK_CONFIRM_AT) {
      const severe = reductions.length > 0 || (grantTier === "EDIT" && grantScopeAll);
      const ok = await confirm({
        title: `Grant ${grantTier} to ${plural(grantGranteeIds.length, "colleague")}?`,
        body: (
          <>
            <span className="font-medium text-ink-900">{nameList(names)}</span> will be able to{" "}
            {TIER_CONSEQUENCE[grantTier]} <span className="font-medium text-ink-900">{grantScopePhrase}</span> immediately.
          </>
        ),
        note: (
          <>
            {reductions.length ? (
              <span className="block font-medium text-ink-900">
                Lowers existing access for {reductions.map((r) => `${r.name} (${r.held})`).join(", ")}.
              </span>
            ) : null}
            <span className="block">You can revoke access any time.</span>
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
    if (status === "DENIED") {
      const ok = await confirm({
        title: "Deny this request?",
        body: (
          <>
            <span className="font-medium text-ink-900">{who}</span> won’t get access.
          </>
        ),
        note: "They can ask again.",
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
          <span className="font-medium text-ink-900">{who}</span> loses access immediately.
        </>
      ),
      note: "Their comments and edits are kept.",
      tone: "danger",
      confirmLabel: "Revoke access"
    });
    if (!ok) return;
    await act(() => apiFetch(`/data-access/grants/${grant.id}/revoke`, { method: "POST" }), "Access revoked.");
  }

  async function remove(grant: DataAccessGrant) {
    const ok = await confirm({
      ...deleteConfirm(
        "Remove this sharing entry?",
        "Deletes this entry and its history.",
        undefined
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
        truncated?: boolean;
      }>(`/export/dataset?ownerId=${encodeURIComponent(ownerId)}`);
      capped = Boolean(manifest.truncated);
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
          if (!response.ok) throw new Error("The download didn’t finish. Try again.");
          zip.file(file.path, await response.blob());
        } catch {
          failed.push(file.path);
        }
      }
      if (failed.length) {
        zip.file(
          "_failed-downloads.txt",
          `Not downloaded:\n\n${failed.join("\n")}`
        );
      }
      const blob = await zip.generateAsync({ type: "blob" });
      saveBlobToDevice(blob, `data-${ownerLabel.replace(/[^A-Za-z0-9]+/g, "_")}.zip`);
    }, "Download ready.");
    if (capped) {
      setMessage(
`Downloaded — some of ${ownerLabel}’s records aren’t included.`
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
        description="Request access to others’ data and manage who uses yours."
        icon={<Share2 className="h-5 w-5" aria-hidden />}
      />

      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {message ? <div className="mb-4 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">{message}</div> : null}

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
                confirmOnSelect={false}
              />
            ) : (
              <TextInput value={reqOwnerText} onChange={(e) => setReqOwnerText(e.target.value)} placeholder="Researcher ID" />
            )}
          </Field>
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
        <p className="mt-3 rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm font-medium text-ink-900">
          {progressLine("request") ?? requestSentence}
        </p>
        <p className="mt-2 text-xs text-ink-muted">
          Requests cover all their data; they can narrow it when approving.
        </p>
        {ledger?.attempt.kind === "request" ? (
          <BatchReport ledger={ledger} busy={busy} onRetry={retryLedger} onDismiss={() => setLedger(null)} />
        ) : null}
      </section>

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
          <div className="mt-2">
            {loadingRecords ? (
              <p className="px-2 py-1 text-sm text-ink-muted">Loading your records…</p>
            ) : (myRecords ?? []).length === 0 ? (
              <p className="px-2 py-1 text-sm text-ink-muted">You have no records to share.</p>
            ) : (
              <SearchableMultiSelect
                values={Array.from(selectedKeys)}
                onChange={(next) => setSelectedKeys(new Set(next))}
                options={(myRecords ?? []).map((r) => ({ value: `${r.recordType}::${r.recordId}`, label: r.label }))}
                placeholder="Choose records to share"
                ariaLabel="Records to share"
                confirmOnSelect={false}
                searchable
              />
            )}
          </div>
        ) : null}
        <p className="mt-2 text-xs text-ink-muted">
          Granted immediately.
        </p>
        {ledger?.attempt.kind === "grant" ? (
          <BatchReport ledger={ledger} busy={busy} onRetry={retryLedger} onDismiss={() => setLedger(null)} />
        ) : null}
      </section>

      <section className="panel mb-5 overflow-hidden">
        <div className="border-b border-line-200 p-4">
          <h2 className="font-display font-bold text-lg text-ink">Access to your data</h2>
          <p className="text-sm text-ink-muted">Who has or wants access.</p>
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
