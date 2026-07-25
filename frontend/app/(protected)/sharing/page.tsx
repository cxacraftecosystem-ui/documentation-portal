"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Share2 } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { Field, Select, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { RowActions, rowAction } from "@/components/RowActions";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import type {
  Artisan,
  DataAccessGrant,
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
  const order: DataAccessTier[] = ["DOWNLOAD", "COMMENT", "EDIT"];
  return order.indexOf(tier) >= order.indexOf(min);
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

  // Request form state
  const [reqOwnerId, setReqOwnerId] = useState("");
  const [reqTier, setReqTier] = useState<DataAccessTier>("DOWNLOAD");
  const [reqNote, setReqNote] = useState("");

  // Direct-grant form state (owner grants a colleague access to all, or a chosen subset, of their data)
  const [grantGranteeId, setGrantGranteeId] = useState("");
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

  async function submitRequest() {
    if (!reqOwnerId.trim()) {
      setError("Choose a researcher to request access from.");
      return;
    }
    await act(
      () =>
        apiFetch("/data-access/requests", {
          method: "POST",
          body: JSON.stringify({ ownerId: reqOwnerId.trim(), tier: reqTier, allData: true, requestNote: reqNote.trim() || undefined })
        }),
      "Request sent."
    );
    setReqNote("");
  }

  async function submitGrant() {
    if (!grantGranteeId) {
      setError("Choose a colleague to grant access to.");
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
    await act(
      () =>
        apiFetch("/data-access/grants", {
          method: "POST",
          body: JSON.stringify({ granteeId: grantGranteeId, tier: grantTier, allData: grantScopeAll, scopeItems })
        }),
      "Access granted."
    );
    setGrantGranteeId("");
    setSelectedKeys(new Set());
    setGrantScopeAll(true);
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

  const incoming = grants?.incoming ?? [];
  const outgoing = grants?.outgoing ?? [];

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

      {/* Request access from another researcher. */}
      <section className="panel mb-5 p-4">
        <h2 className="font-display font-bold text-lg text-ink">Request access to a researcher&apos;s data</h2>
        <div className="mt-3 grid gap-3 md:grid-cols-[2fr_1.4fr_2fr_auto] md:items-end">
          <Field label="Researcher">
            {canPickUsers ? (
              <Select value={reqOwnerId} onChange={(e) => setReqOwnerId(e.target.value)}>
                <option value="">Select…</option>
                {users.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.name} · {u.email}
                  </option>
                ))}
              </Select>
            ) : (
              <TextInput value={reqOwnerId} onChange={(e) => setReqOwnerId(e.target.value)} placeholder="Researcher user id" />
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
        <p className="mt-2 text-xs text-ink-muted">Requests cover all of that researcher&apos;s data. The owner can narrow it to a subset when they approve.</p>
      </section>

      {/* Grant access directly — owner shares all, or a chosen subset, of their own data. */}
      <section className="panel mb-5 p-4">
        <h2 className="font-display font-bold text-lg text-ink">Grant access to your data</h2>
        <div className="mt-3 grid gap-3 md:grid-cols-[2fr_1.4fr_auto] md:items-end">
          <Field label="Colleague">
            <Select value={grantGranteeId} onChange={(e) => setGrantGranteeId(e.target.value)}>
              <option value="">Select…</option>
              {users.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.name} · {u.email}
                </option>
              ))}
            </Select>
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
          Granted immediately. The recipient can download (and, at higher tiers, comment on or edit) exactly what you share here.
        </p>
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
