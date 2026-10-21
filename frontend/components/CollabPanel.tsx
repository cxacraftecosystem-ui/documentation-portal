"use client";

import { useCallback, useEffect, useState } from "react";
import { Pencil, Trash2 } from "lucide-react";

import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { deleteConfirm, useConfirm } from "@/components/dialogs";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import type { EntryComment, RecordRevision } from "@/lib/types";

/**
 * Comments + edit history for a single record, powered by the data-access API.
 * - Anyone who can see the record can read comments; posting requires COMMENT-tier access (or owner/admin).
 * - Edit history is visible to the record's owner and admins (the API returns 403 otherwise, which we hide).
 */
export function CollabPanel({ recordType, recordId }: { recordType: string; recordId: string }) {
  const [comments, setComments] = useState<EntryComment[]>([]);
  const [revisions, setRevisions] = useState<RecordRevision[] | null>(null);
  const [body, setBody] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /** The comment being edited by its author, and the draft in the box. */
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [saving, setSaving] = useState(false);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const { user } = useAuth();
  // Deleting is the content admins' alone, admin view or not (owner, 2026-10-21).
  const { canDeleteRecords } = useAdminView();
  const confirm = useConfirm();

  const load = useCallback(async () => {
    try {
      const c = await apiFetch<EntryComment[]>(`/data-access/comments?recordType=${recordType}&recordId=${recordId}`);
      setComments(c);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load comments");
    }
    // Edit history is owner/admin-only; silently skip if not permitted.
    try {
      const r = await apiFetch<RecordRevision[]>(`/data-access/revisions?recordType=${recordType}&recordId=${recordId}`);
      setRevisions(r);
    } catch {
      setRevisions(null);
    }
  }, [recordType, recordId]);

  useEffect(() => {
    load();
  }, [load]);

  async function postComment() {
    if (!body.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await apiFetch("/data-access/comments", { method: "POST", body: JSON.stringify({ recordType, recordId, body: body.trim() }) });
      setBody("");
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to post comment");
    } finally {
      setBusy(false);
    }
  }

  /** The author's edit — `PATCH /data-access/comments/{id}`, which 403s for anybody else. */
  async function saveEdit(comment: EntryComment) {
    const next = draft.trim();
    if (!next || next === comment.body) {
      setEditingId(null);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await apiFetch(`/data-access/comments/${comment.id}`, { method: "PATCH", body: JSON.stringify({ body: next }) });
      setEditingId(null);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save comment");
    } finally {
      setSaving(false);
    }
  }

  /** `DELETE /data-access/comments/{id}` — content admins only, so drawn for them alone, admin view or not. */
  async function removeComment(comment: EntryComment) {
    const ok = await confirm(deleteConfirm("Delete this comment?", comment.body, "This cannot be undone."));
    if (!ok) return;
    setRemovingId(comment.id);
    setError(null);
    try {
      await apiFetch(`/data-access/comments/${comment.id}`, { method: "DELETE" });
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete comment");
    } finally {
      setRemovingId(null);
    }
  }

  return (
    <div className="grid gap-4">
      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      <div>
        <h3 className="font-display font-bold text-base text-ink">Comments</h3>
        <ul className="mt-2 grid gap-2">
          {comments.length === 0 ? <li className="text-sm text-ink-muted">No comments yet.</li> : null}
          {comments.map((c) => (
            <li key={c.id} className="rounded-md border border-line-200 bg-field-50 p-2 text-sm">
              {editingId === c.id ? (
                <div className="grid gap-2">
                  <textarea
                    className="field-input min-h-20"
                    aria-label="Edit comment"
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                  />
                  <ButtonRow align="end" size="sm">
                    <button type="button" className="field-button-secondary" disabled={saving} onClick={() => setEditingId(null)}>
                      Cancel
                    </button>
                    <button type="button" className="field-button" disabled={saving || !draft.trim()} onClick={() => saveEdit(c)}>
                      {saving ? "Saving…" : "Save"}
                    </button>
                  </ButtonRow>
                </div>
              ) : (
                <div className="text-ink">{c.body}</div>
              )}
              <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-ink-muted">
                <span>
                  {c.author?.name ?? "Someone"} · {formatDateTime(c.createdAt)}
                </span>
                {user && c.authorId === user.id && editingId !== c.id ? (
                  <button
                    type="button"
                    className="inline-flex items-center gap-1 text-purple-700 underline underline-offset-2"
                    onClick={() => {
                      setEditingId(c.id);
                      setDraft(c.body);
                    }}
                  >
                    <Pencil className="h-3 w-3" aria-hidden />
                    Edit
                  </button>
                ) : null}
                {user && canDeleteRecords ? (
                  <button
                    type="button"
                    className="inline-flex items-center gap-1 text-error-600 underline underline-offset-2 disabled:no-underline disabled:opacity-60"
                    disabled={removingId === c.id}
                    onClick={() => removeComment(c)}
                  >
                    <Trash2 className="h-3 w-3" aria-hidden />
                    {removingId === c.id ? "Deleting…" : "Delete"}
                  </button>
                ) : null}
              </div>
            </li>
          ))}
        </ul>
        <div className="mt-2 flex gap-2">
          <input
            className="field-input min-w-0 flex-1"
            aria-label="Add a comment"
            placeholder="Add a comment"
            value={body}
            onChange={(e) => setBody(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !busy && body.trim()) postComment();
            }}
          />
          <button type="button" className="field-button" disabled={busy || !body.trim()} onClick={postComment}>
            Post
          </button>
        </div>
      </div>

      {revisions ? (
        <div>
          <h3 className="font-display font-bold text-base text-ink">Edit history</h3>
          <p className="text-xs text-ink-muted">Visible to the owner, reviewers and admins.</p>
          <ul className="mt-2 grid gap-2">
            {revisions.length === 0 ? <li className="text-sm text-ink-muted">No edits recorded.</li> : null}
            {revisions.map((r) => (
              <li key={r.id} className="rounded-md border border-line-200 bg-field-50 p-2 text-sm">
                <div className="text-xs text-ink-muted">
                  {r.editedBy?.name ?? "Unknown"} · {formatDateTime(r.createdAt)}
                </div>
                <ul className="mt-1 grid gap-0.5">
                  {Object.entries(r.changes).map(([field, change]) => (
                    <li key={field} className="text-xs">
                      <span className="font-semibold text-ink">{field}</span>:{" "}
                      <span className="text-red-700 line-through">{String(change.old ?? "—")}</span>{" → "}
                      <span className="text-emerald-700">{String(change.new ?? "—")}</span>
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
