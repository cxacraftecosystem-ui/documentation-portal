"use client";

import { useCallback, useEffect, useState } from "react";

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

  return (
    <div className="grid gap-4">
      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      <div>
        <h3 className="font-serif text-base text-ink">Comments</h3>
        <ul className="mt-2 grid gap-2">
          {comments.length === 0 ? <li className="text-sm text-ink-muted">No comments yet.</li> : null}
          {comments.map((c) => (
            <li key={c.id} className="rounded-md border border-[#e6dfd8] bg-field-50 p-2 text-sm">
              <div className="text-ink">{c.body}</div>
              <div className="mt-1 text-xs text-ink-muted">
                {c.author?.name ?? "Someone"} · {formatDateTime(c.createdAt)}
              </div>
            </li>
          ))}
        </ul>
        <div className="mt-2 flex gap-2">
          <input
            className="field-input flex-1"
            placeholder="Add a comment (needs comment access)"
            value={body}
            onChange={(e) => setBody(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") postComment();
            }}
          />
          <button className="field-button" disabled={busy} onClick={postComment}>
            Post
          </button>
        </div>
      </div>

      {revisions ? (
        <div>
          <h3 className="font-serif text-base text-ink">Edit history</h3>
          <p className="text-xs text-ink-muted">Original values are the first &quot;before&quot; of each field. Visible to the owner and admins.</p>
          <ul className="mt-2 grid gap-2">
            {revisions.length === 0 ? <li className="text-sm text-ink-muted">No edits recorded.</li> : null}
            {revisions.map((r) => (
              <li key={r.id} className="rounded-md border border-[#e6dfd8] bg-field-50 p-2 text-sm">
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
