"use client";

import { useEffect, useId, useRef, useState, type FormEvent } from "react";

import { adminChromeVisible, useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { useToast } from "@/components/ui/Toast";
import { ApiError, apiErrorMessage } from "@/lib/api";
import {
  NOTE_MAX_LENGTH,
  basisOptions,
  fieldVoicesText,
  isWithdrawalBasis,
  loadWithdrawal,
  saveWithdrawal,
  withdrawalLine,
  type FieldVoiceSubjectType,
  type Withdrawal,
  type WithdrawalBasis
} from "@/lib/fieldVoices/withdrawals";
import { formatDate } from "@/lib/format";
import { isAdmin } from "@/lib/permissions";

const UNSHOWN_STATUSES = new Set([403, 404]);

export function FieldVoicesPanel({ subjectType, subjectId }: { subjectType: FieldVoiceSubjectType; subjectId: string }) {
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  if (!isAdmin(user) || !adminChromeVisible(user, adminMode)) return null;
  return <WithdrawalPanel key={`${subjectType}:${subjectId}`} subjectType={subjectType} subjectId={subjectId} />;
}

function WithdrawalPanel({ subjectType, subjectId }: { subjectType: FieldVoiceSubjectType; subjectId: string }) {
  const headingId = useId();
  const [status, setStatus] = useState<Withdrawal | null>(null);
  const [failure, setFailure] = useState<unknown>(null);
  const [unshown, setUnshown] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    loadWithdrawal(subjectType, subjectId)
      .then((found) => {
        if (cancelled) return;
        setFailure(null);
        setStatus(found);
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && UNSHOWN_STATUSES.has(err.status)) {
          setUnshown(true);
          return;
        }
        setFailure(err ?? new Error("load failed"));
      });
    return () => {
      cancelled = true;
    };
  }, [subjectType, subjectId, attempt]);

  if (unshown) return null;

  const line = status
    ? withdrawalLine(status, formatDate)
    : failure
      ? apiErrorMessage(failure, fieldVoicesText("loadFailed"))
      : fieldVoicesText("loading");

  return (
    <section aria-labelledby={headingId} className="panel flex flex-wrap items-center justify-between gap-3 p-4">
      <div className="min-w-0">
        <h2 id={headingId} className="display-title text-lg">
          {fieldVoicesText("heading")}
        </h2>
        <p role="status" className={`mt-0.5 text-sm ${failure && !status ? "text-red-700 dark:text-red-400" : "text-ink-500"}`}>
          {line}
        </p>
      </div>
      {status ? (
        <ActionGroup className="max-sm:w-full">
          <button type="button" className="field-button-secondary" onClick={() => setOpen(true)}>
            {status.shown ? fieldVoicesText("withdraw") : fieldVoicesText("showAgain")}
          </button>
        </ActionGroup>
      ) : failure ? (
        <ActionGroup className="max-sm:w-full">
          <button
            type="button"
            className="field-button-secondary"
            onClick={() => {
              setFailure(null);
              setAttempt((n) => n + 1);
            }}
          >
            {fieldVoicesText("tryAgain")}
          </button>
        </ActionGroup>
      ) : null}
      {status ? (
        <WithdrawalDialog
          open={open}
          status={status}
          onClose={() => setOpen(false)}
          onSaved={(next) => {
            setStatus(next);
            setOpen(false);
          }}
        />
      ) : null}
    </section>
  );
}

function WithdrawalDialog({
  open,
  status,
  onClose,
  onSaved
}: {
  open: boolean;
  status: Withdrawal;
  onClose: () => void;
  onSaved: (next: Withdrawal) => void;
}) {
  const { toast } = useToast();
  const formId = useId();
  const basisId = useId();
  const noteId = useId();
  const cancelRef = useRef<HTMLButtonElement | null>(null);
  const [basis, setBasis] = useState<WithdrawalBasis | "">("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const showing = !status.shown;

  function close() {
    if (busy) return;
    setError(null);
    onClose();
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!basis || busy) return;
    setBusy(true);
    setError(null);
    try {
      const next = await saveWithdrawal(status.subjectType, status.subjectId, { shown: showing, basis, note });
      toast({ title: fieldVoicesText(showing ? "shownToast" : "withdrawnToast"), tone: "success" });
      setBasis("");
      setNote("");
      onSaved(next);
    } catch (err) {
      setError(apiErrorMessage(err, fieldVoicesText("saveFailed")));
    } finally {
      setBusy(false);
    }
  }

  const action = fieldVoicesText(showing ? "showAgain" : "withdraw");
  return (
    <FieldDialog
      open={open}
      onClose={close}
      busy={busy}
      title={fieldVoicesText(showing ? "showTitle" : "withdrawTitle")}
      description={fieldVoicesText("takesEffect")}
      initialFocusRef={cancelRef}
      footer={
        <>
          <button ref={cancelRef} type="button" className="field-button-secondary" onClick={close} disabled={busy}>
            {fieldVoicesText("cancel")}
          </button>
          <button type="submit" form={formId} className="field-button" disabled={!basis || busy}>
            {busy ? fieldVoicesText("saving") : action}
          </button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} className="grid gap-4">
        <div className="grid gap-1">
          <label htmlFor={basisId} className="field-label">
            {fieldVoicesText("basisLabel")}
          </label>
          <SearchableSelect
            id={basisId}
            ariaLabel={fieldVoicesText("basisLabel")}
            value={basis}
            onChange={(value) => setBasis(isWithdrawalBasis(value) ? value : "")}
            options={basisOptions()}
            placeholder={fieldVoicesText("basisPlaceholder")}
            disabled={busy}
          />
        </div>
        <div className="grid gap-1">
          <label htmlFor={noteId} className="field-label">
            {fieldVoicesText("noteLabel")}
          </label>
          <textarea
            id={noteId}
            className="field-input min-h-20"
            maxLength={NOTE_MAX_LENGTH}
            value={note}
            onChange={(event) => setNote(event.target.value)}
            disabled={busy}
          />
        </div>
        {error ? (
          <p role="alert" className="text-sm text-red-700 dark:text-red-400">
            {error}
          </p>
        ) : null}
      </form>
    </FieldDialog>
  );
}
