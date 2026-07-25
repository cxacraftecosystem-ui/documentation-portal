"use client";

/**
 * The themed replacement for `window.confirm()`, and the Android `AlertDialog` confirm's web twin.
 *
 * Two pieces:
 *
 * - `ConfirmDialog` — the presentational dialog, for the rare caller that wants to own the open state.
 * - `ConfirmProvider` + `useConfirm()` — the imperative form, which is what every page uses. `confirm()`
 *   returns a promise, so a call site reads exactly like the `window.confirm` it replaces:
 *
 *   ```ts
 *   if (!(await confirm({ title: "Delete this artisan record?", tone: "danger" }))) return;
 *   ```
 *
 *   That shape matters: it is why fifteen destructive handlers across the app could stop using the
 *   native prompt without any of them growing dialog state, an extra JSX branch, or a callback tunnel.
 *
 * Tone drives everything else. `danger` gets the error tokens, a solid red confirm button, an
 * `alertdialog` role, a backdrop that refuses to dismiss it, and initial focus on **Cancel** — so no
 * combination of a reflex click or a reflex Enter deletes anything. `warning` is amber, for decisions
 * that are consequential but recoverable (denying a request, a late submission). `neutral` is the
 * plain "are you sure" and behaves like an ordinary dialog.
 */

import { AlertTriangle, HelpCircle, Trash2 } from "lucide-react";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode
} from "react";

import { DANGER_BUTTON_CLASS, FieldDialog, type DialogTone } from "@/components/dialogs/FieldDialog";
import { cn } from "@/lib/utils";

export type ConfirmOptions = {
  title: string;
  /** The sentence that explains the consequence. Keep it to what actually happens. */
  body?: ReactNode;
  /** A quieter second line — caveats, scope, what is *not* affected. */
  note?: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: DialogTone;
};

const TONE_ICON: Record<DialogTone, ReactNode> = {
  neutral: <HelpCircle className="h-4 w-4" />,
  warning: <AlertTriangle className="h-4 w-4" />,
  danger: <Trash2 className="h-4 w-4" />
};

export function ConfirmDialog({
  open,
  title,
  body,
  note,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  tone = "neutral",
  busy = false,
  onConfirm,
  onCancel
}: ConfirmOptions & {
  open: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const cancelRef = useRef<HTMLButtonElement | null>(null);
  const confirmRef = useRef<HTMLButtonElement | null>(null);
  const destructive = tone === "danger";

  return (
    <FieldDialog
      open={open}
      onClose={onCancel}
      role={tone === "neutral" ? "dialog" : "alertdialog"}
      tone={tone}
      icon={TONE_ICON[tone]}
      busy={busy}
      title={title}
      description={body}
      // Destructive: Cancel is under the keyboard when the dialog opens. Otherwise the affirmative
      // action is, because that is the one the user came here to press.
      initialFocusRef={destructive ? cancelRef : confirmRef}
      footer={
        <>
          <button type="button" ref={cancelRef} className="field-button-secondary" disabled={busy} onClick={onCancel}>
            {cancelLabel}
          </button>
          <button
            type="button"
            ref={confirmRef}
            className={cn(destructive ? DANGER_BUTTON_CLASS : "field-button")}
            disabled={busy}
            onClick={onConfirm}
          >
            {busy ? "Working…" : confirmLabel}
          </button>
        </>
      }
    >
      {note ? <p className="mt-2 text-sm leading-6 text-ink-500">{note}</p> : null}
    </FieldDialog>
  );
}

type ConfirmFn = (options: ConfirmOptions) => Promise<boolean>;

const ConfirmContext = createContext<ConfirmFn | undefined>(undefined);

/**
 * Mounted once, in the protected layout, so every page below it can call `useConfirm()` the same way
 * it calls `useToast()`. One provider only: a nested one shadows the outer and its dialog would open
 * behind whatever the outer had already put on screen.
 */
export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  // Options outlive `open` so the exit animation plays over the real content instead of an empty card.
  const [options, setOptions] = useState<ConfirmOptions>({ title: "" });
  const resolverRef = useRef<((value: boolean) => void) | null>(null);

  const settle = useCallback((value: boolean) => {
    const resolve = resolverRef.current;
    resolverRef.current = null;
    setOpen(false);
    resolve?.(value);
  }, []);

  const confirm = useCallback<ConfirmFn>(
    (next) =>
      new Promise<boolean>((resolve) => {
        // A second request while one is open answers the first as cancelled, so its awaiting handler
        // unwinds instead of hanging on a promise nobody can ever settle.
        resolverRef.current?.(false);
        resolverRef.current = resolve;
        setOptions(next);
        setOpen(true);
      }),
    []
  );

  // Navigating away with a confirm open must not strand the caller mid-await.
  useEffect(
    () => () => {
      resolverRef.current?.(false);
      resolverRef.current = null;
    },
    []
  );

  const value = useMemo(() => confirm, [confirm]);

  return (
    <ConfirmContext.Provider value={value}>
      {children}
      <ConfirmDialog {...options} open={open} onConfirm={() => settle(true)} onCancel={() => settle(false)} />
    </ConfirmContext.Provider>
  );
}

export function useConfirm(): ConfirmFn {
  const context = useContext(ConfirmContext);
  if (!context) throw new Error("useConfirm must be used inside ConfirmProvider");
  return context;
}

/** Shorthand for the commonest case: a red "delete this record" confirm. */
export function deleteConfirm(title: string, body?: ReactNode, note?: ReactNode): ConfirmOptions {
  return { title, body, note, tone: "danger", confirmLabel: "Delete" };
}
