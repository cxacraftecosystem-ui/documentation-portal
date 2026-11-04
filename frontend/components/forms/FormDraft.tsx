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

export const DRAFT_DEBOUNCE_MS = 700;

export function draftFingerprint(fields: Record<string, unknown>): string {
  return JSON.stringify(fields, (_key, value) =>
    typeof File !== "undefined" && value instanceof File ? `file:${value.name}:${value.size}:${value.lastModified}` : value
  );
}

export type FormDraftHandle = {
  offer: FormDraftRecord | null;
  take: () => void;
  discard: () => void;
  clear: () => void;
};

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
        if (live) setDecided(true);
      });
    return () => {
      live = false;
    };
  }, [account, kind, recordId, parentId]);

  const fingerprint = draftFingerprint(fields);
  const fieldsRef = useRef(fields);
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
