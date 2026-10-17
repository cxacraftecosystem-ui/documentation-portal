"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { FilePen } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { ButtonRow } from "@/components/ui/ButtonRow";
import {
  deleteDraft,
  draftOfferSentence,
  editRecordKey,
  newRecordKey,
  readDraft,
  readLatestNewDraft,
  writeDraft,
  type FormDraftRecord
} from "@/lib/formDrafts";

/** Autosave waits this long after the last change — the handset's `debounceMillis`. */
export const DRAFT_DEBOUNCE_MS = 700;

/** A stable text for "has anything changed", with files reduced to what identifies them. */
export function draftFingerprint(fields: Record<string, unknown>): string {
  return JSON.stringify(fields, (_key, value) =>
    typeof File !== "undefined" && value instanceof File ? `file:${value.name}:${value.size}:${value.lastModified}` : value
  );
}

export type FormDraftHandle = {
  /** An earlier draft waiting for Continue or Discard. Autosave waits for the answer. */
  offer: FormDraftRecord | null;
  /** Continue: hand the earlier draft's fields to the form and keep saving under its key. */
  take: () => void;
  /** Discard: delete the earlier draft and start a fresh one. */
  discard: () => void;
  /** The record is saved or queued: delete the draft; a form that stays open starts a fresh one. */
  clear: () => void;
};

/**
 * B6 — one open form's draft (`lib/formDrafts.ts` has the rules). `fields` is what the form wants
 * kept, rebuilt each render; `dirty` is the same flag its leave guard reads, so a clean form writes
 * nothing. `onRestore` receives an earlier draft's fields when the researcher continues it.
 */
export function useFormDraft({
  kind,
  recordId,
  parentId = null,
  parentLabel = null,
  dirty,
  fields,
  label,
  collect,
  onRestore
}: {
  kind: string;
  recordId: string | null;
  parentId?: string | null;
  parentLabel?: string | null;
  dirty: boolean;
  fields: Record<string, unknown>;
  label: string;
  /**
   * Values the form does not hold in state (its uncontrolled boxes), read from the DOM at WRITE time
   * — never during render. A form using it bumps something in `fields` on every input, so the
   * debounce sees the change.
   */
  collect?: () => Record<string, unknown>;
  onRestore: (fields: Record<string, unknown>) => void;
}): FormDraftHandle {
  const { user } = useAuth();
  const account = user?.id ?? "signed-out";
  const recordKey = useRef<string>("");
  const generation = useRef(0);
  const [offer, setOffer] = useState<FormDraftRecord | null>(null);
  const [decided, setDecided] = useState(false);
  const restoreRef = useRef(onRestore);
  const collectRef = useRef(collect);
  useEffect(() => {
    restoreRef.current = onRestore;
    collectRef.current = collect;
  });

  useEffect(() => {
    let live = true;
    // A form that switches record (the interview page loading `?edit=`) keys its draft afresh.
    recordKey.current = recordId ? editRecordKey(recordId) : newRecordKey(parentId);
    setOffer(null);
    setDecided(false);
    const read = recordId ? readDraft(account, kind, editRecordKey(recordId)) : readLatestNewDraft(account, kind, parentId);
    read
      .then((earlier) => {
        if (!live) return;
        setOffer(earlier);
        setDecided(earlier === null);
      })
      .catch(() => {
        // No IndexedDB (a private window): the form works, it simply keeps no draft.
        if (live) setDecided(true);
      });
    return () => {
      live = false;
    };
  }, [account, kind, recordId, parentId]);

  const fingerprint = draftFingerprint(fields);
  const fieldsRef = useRef(fields);
  /** What the form held when its record was saved: nothing is written again until it changes. */
  const clearedAt = useRef<string | null>(null);
  useEffect(() => {
    fieldsRef.current = fields;
  });
  useEffect(() => {
    if (!decided || !dirty || fingerprint === clearedAt.current) return;
    const mine = generation.current;
    const timer = window.setTimeout(() => {
      if (mine !== generation.current) return;
      void writeDraft({
        account,
        formKind: kind,
        recordKey: recordKey.current,
        label,
        savedAt: new Date().toISOString(),
        fields: { ...(collectRef.current?.() ?? {}), ...fieldsRef.current },
        parentId,
        parentLabel
      }).catch(() => undefined);
    }, DRAFT_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [decided, dirty, fingerprint, account, kind, label, parentId, parentLabel]);

  const take = useCallback(() => {
    if (!offer) return;
    recordKey.current = offer.recordKey;
    restoreRef.current(offer.fields);
    setOffer(null);
    setDecided(true);
  }, [offer]);

  const discard = useCallback(() => {
    if (offer) void deleteDraft(account, kind, offer.recordKey).catch(() => undefined);
    setOffer(null);
    setDecided(true);
  }, [offer, account, kind]);

  const clear = useCallback(() => {
    generation.current += 1;
    clearedAt.current = draftFingerprint(fieldsRef.current);
    const done = recordKey.current;
    void deleteDraft(account, kind, done).catch(() => undefined);
    if (!recordId) recordKey.current = newRecordKey(parentId);
    setOffer(null);
    setDecided(true);
  }, [account, kind, recordId, parentId]);

  return { offer, take, discard, clear };
}

/** "Continue" or "Discard", at the top of a form that has an earlier draft. */
export function DraftOfferCard({ draft }: { draft: FormDraftHandle }) {
  if (!draft.offer) return null;
  return (
    <div
      role="status"
      data-testid="draft-offer"
      className="grid gap-2 rounded-md border border-purple-200 bg-purple-50 px-3 py-2.5 text-sm text-purple-950"
    >
      <p className="flex items-start gap-2">
        <FilePen className="mt-0.5 h-4 w-4 shrink-0 text-purple-700" aria-hidden />
        <span>{draftOfferSentence(draft.offer)}</span>
      </p>
      <ButtonRow align="start">
        <button type="button" className="field-button" onClick={draft.take}>
          Continue
        </button>
        <button type="button" className="field-button-secondary" onClick={draft.discard}>
          Discard
        </button>
      </ButtonRow>
    </div>
  );
}
