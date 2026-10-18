"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Copy, Download, ShieldCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { OtpCodeInput } from "@/components/ui/OtpCodeInput";
import { Toggle } from "@/components/ui/Toggle";
import { useToast } from "@/components/ui/Toast";
import { ApiError } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { isCompleteCode } from "@/lib/otp";
import { saveBlobToDevice } from "@/lib/saveToDevice";
import {
  beginTwoFactorSetup,
  cancelTwoFactorSetup,
  disableTwoFactor,
  enableTwoFactor,
  fetchTwoFactorStatus,
  makeNewRecoveryCodes,
  recoveryCodesFileText,
  twoFactorStatusLine,
  twoFactorView,
  type TwoFactorSetup,
  type TwoFactorStatus
} from "@/lib/twoFactor";
import { exportFilename } from "@/lib/naming";

/**
 * Two-step verification for THIS account, as ONE switch (owner, 2026-10-17).
 *
 * - Off → switch on → setup opens (QR, key, six code boxes). The switch reads "Setting up…" and
 *   stays OFF until the server has checked a code (`/enable` succeeded). Only then is it On.
 * - Leaving setup without a verified code DISCARDS it: Cancel, closing or reloading the page and
 *   navigating away all call `POST /auth/two-factor/setup/cancel`; a tab hidden for longer than
 *   `AWAY_LIMIT_MS` (the reader went somewhere else, not merely to the authenticator app) does too.
 *   The server also drops a pending secret older than 15 minutes, and reports `pending` so a page
 *   that finds a setup left over from an earlier visit shows Off and clears it — never On.
 * - On → switch off → asks for a current code (six boxes) or, behind "Use a recovery code instead",
 *   a recovery code; then it is off.
 * - The line under the switch says the state in plain words: "On since 17 Oct 2026 · 8 recovery
 *   codes left".
 *
 * Everything is decided on the server: the QR square arrives drawn, a code is checked and SPENT
 * there, and the recovery codes exist in plain text only in the one response that issues them —
 * which is why the codes step refuses to be left by accident.
 */

type Step =
  | { kind: "idle" }
  | { kind: "setup"; setup: TwoFactorSetup }
  | { kind: "codes"; codes: string[] }
  | { kind: "confirm"; action: "disable" | "regenerate" };

/** Hidden this long, a setup is abandoned rather than paused for a look at the authenticator app. */
const AWAY_LIMIT_MS = 5 * 60 * 1000;

export function TwoStepVerificationCard() {
  const { user } = useAuth();
  const { toast } = useToast();
  const [status, setStatus] = useState<TwoFactorStatus | null>(null);
  const [step, setStep] = useState<Step>({ kind: "idle" });
  const [code, setCode] = useState("");
  const [recovery, setRecovery] = useState("");
  const [useRecovery, setUseRecovery] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errorNonce, setErrorNonce] = useState(0);
  const [saved, setSaved] = useState(false);
  const recoveryBox = useRef<HTMLInputElement | null>(null);

  // Whether a setup is open on THIS screen, readable from the leave handlers without re-binding.
  const settingUp = useRef(false);
  useEffect(() => {
    settingUp.current = step.kind === "setup";
  }, [step]);

  const view = twoFactorView(status, step.kind === "setup");

  const reload = useCallback(async () => {
    try {
      setStatus(await fetchTwoFactorStatus());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't load this setting. Try again.");
    }
  }, []);

  useEffect(() => {
    let live = true;
    void (async () => {
      try {
        let answer = await fetchTwoFactorStatus();
        // A setup somebody started on an earlier visit and never finished: it is Off, so forget it.
        if (!answer.enabled && answer.pending) {
          await cancelTwoFactorSetup().catch(() => undefined);
          answer = { ...answer, pending: false };
        }
        if (live) setStatus(answer);
      } catch (err) {
        if (live) setError(err instanceof Error ? err.message : "Couldn't load this setting. Try again.");
      }
    })();
    return () => {
      live = false;
    };
  }, []);

  // Every way out of the page while setting up discards the pending secret. `keepalive` lets the
  // request finish after the page is gone; the server is idempotent, so a double call is harmless.
  useEffect(() => {
    let hiddenAt: number | null = null;
    function onPageHide() {
      if (settingUp.current) void cancelTwoFactorSetup({ keepalive: true }).catch(() => undefined);
    }
    function onVisibility() {
      if (document.visibilityState === "hidden") {
        hiddenAt = Date.now();
        return;
      }
      const away = hiddenAt === null ? 0 : Date.now() - hiddenAt;
      hiddenAt = null;
      if (settingUp.current && away > AWAY_LIMIT_MS) {
        void cancelTwoFactorSetup().catch(() => undefined);
        setStep({ kind: "idle" });
        setCode("");
        setError("Setup was stopped because you were away. Turn it on again to get a new code to scan.");
        void reload();
      }
    }
    window.addEventListener("pagehide", onPageHide);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.removeEventListener("pagehide", onPageHide);
      document.removeEventListener("visibilitychange", onVisibility);
      // Navigating to another page inside the app unmounts the card without a pagehide.
      if (settingUp.current) void cancelTwoFactorSetup({ keepalive: true }).catch(() => undefined);
    };
  }, [reload]);

  useEffect(() => {
    if (useRecovery && step.kind === "confirm") recoveryBox.current?.focus();
  }, [useRecovery, step]);

  function go(next: Step) {
    setStep(next);
    setCode("");
    setRecovery("");
    setUseRecovery(false);
    setError(null);
    setSaved(false);
  }

  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (err) {
      setCode("");
      setRecovery("");
      setErrorNonce((n) => n + 1);
      setError(err instanceof ApiError ? err.message : "Couldn't connect. Check your connection and try again.");
      // A setup the server has already dropped (expired): back to Off with the server's sentence.
      if (err instanceof ApiError && err.status === 409 && step.kind === "setup") {
        setStep({ kind: "idle" });
        await reload();
      }
    } finally {
      setBusy(false);
    }
  }

  function onSwitch(next: boolean) {
    if (busy) return;
    if (next && view === "off") {
      void run(async () => go({ kind: "setup", setup: await beginTwoFactorSetup() }));
    } else if (!next && view === "on") {
      go({ kind: "confirm", action: "disable" });
    }
  }

  function cancelSetup() {
    go({ kind: "idle" });
    void cancelTwoFactorSetup()
      .catch(() => undefined)
      .finally(() => void reload());
  }

  function submit(typed: string) {
    if (busy) return;
    if (step.kind === "setup") {
      if (!isCompleteCode(typed)) return;
      void run(async () => {
        const codes = await enableTwoFactor(typed);
        go({ kind: "codes", codes });
        await reload();
      });
    } else if (step.kind === "confirm" && step.action === "disable") {
      if (!useRecovery && !isCompleteCode(typed)) return;
      if (useRecovery && !typed.trim()) return;
      void run(async () => {
        await disableTwoFactor(typed.trim());
        go({ kind: "idle" });
        await reload();
        toast({ title: "Two-step verification is off", tone: "success" });
      });
    } else if (step.kind === "confirm") {
      if (!isCompleteCode(typed)) return;
      void run(async () => {
        const codes = await makeNewRecoveryCodes(typed);
        go({ kind: "codes", codes });
        await reload();
      });
    }
  }

  async function copy(text: string, what: string) {
    try {
      await navigator.clipboard.writeText(text);
      toast({ title: `${what} copied`, tone: "success" });
      if (step.kind === "codes") setSaved(true);
    } catch {
      toast({ title: `Couldn't copy the ${what.toLowerCase()}`, description: "Select it and copy it by hand.", tone: "error" });
    }
  }

  function download(codes: string[]) {
    const blob = new Blob([recoveryCodesFileText(codes, user?.email ?? "")], { type: "text/plain" });
    // Through the shared helper, which revokes the object URL a minute later rather than on the
    // next line (that race made Safari save nothing, silently). See lib/saveToDevice.ts.
    // NAMING 2.1: FieldRepository_Account_2026-10-18_Recovery-Codes.txt — never the e-mail address.
    saveBlobToDevice(blob, exportFilename("Account", "Recovery-Codes", "txt"));
    setSaved(true);
  }

  /** Six boxes for a 6-digit code; for "turn off", a recovery code in its own field behind a switch. */
  const codeForm = (hint: string, submitLabel: string, acceptsRecovery: boolean, onCancel: () => void) => (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        submit(useRecovery ? recovery : code);
      }}
      className="mt-4 grid gap-3"
    >
      {acceptsRecovery && useRecovery ? (
        <div className="grid gap-1.5 sm:max-w-sm">
          <label htmlFor="two-step-recovery-code" className="text-sm font-medium text-ink-900">
            Recovery code
          </label>
          <input
            id="two-step-recovery-code"
            ref={recoveryBox}
            type="text"
            required
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
            maxLength={20}
            placeholder="xxxx-xxxx"
            value={recovery}
            onChange={(event) => setRecovery(event.target.value)}
            aria-describedby="two-step-setting-hint"
            aria-invalid={error ? true : undefined}
            className="field-input font-mono tracking-[0.15em]"
          />
        </div>
      ) : (
        <OtpCodeInput
          value={code}
          onChange={setCode}
          onComplete={submit}
          label="6-digit code"
          describedBy="two-step-setting-hint"
          error={Boolean(error)}
          errorNonce={errorNonce}
          disabled={busy}
          autoFocus
        />
      )}
      <p id="two-step-setting-hint" className="text-xs leading-5 text-ink-500">
        {acceptsRecovery && useRecovery ? "Each recovery code works once." : hint}
      </p>
      {acceptsRecovery ? (
        <div className="sm:max-w-sm">
          <Toggle
            label="Use a recovery code instead"
            description="For when your phone is lost or the authenticator app is gone."
            checked={useRecovery}
            onChange={(next) => {
              setUseRecovery(next);
              setCode("");
              setRecovery("");
              setError(null);
            }}
          />
        </div>
      ) : null}
      <ButtonRow align="start">
        <button
          type="submit"
          className="field-button"
          disabled={busy || (useRecovery ? !recovery.trim() : !isCompleteCode(code))}
        >
          {busy ? "Checking…" : submitLabel}
        </button>
        <button type="button" className="field-button-secondary" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
      </ButtonRow>
    </form>
  );

  return (
    <section className="panel p-5" aria-labelledby="two-step-heading">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <ShieldCheck className="h-4 w-4" aria-hidden />
        </span>
        <h2 id="two-step-heading" className="font-display font-bold text-ink-900">
          Two-step verification
        </h2>
      </div>
      <p className="mt-1.5 text-xs leading-5 text-ink-500">
        Use an authenticator app for a code at each sign-in.
      </p>

      {/* Mounted from first paint and emptied rather than removed, so the sentence is announced. */}
      <div
        role="alert"
        className={error ? "mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600" : "sr-only"}
      >
        {error ?? ""}
      </div>

      {status === null ? (
        error ? null : <p className="mt-3 text-sm text-ink-500">Loading…</p>
      ) : (
        <div className="mt-3 sm:max-w-xl">
          <Toggle
            label="Two-step verification"
            description={twoFactorStatusLine(status, view, (iso) => formatDate(iso))}
            checked={view === "on"}
            busy={view === "setting-up" || busy}
            disabled={step.kind === "codes" || step.kind === "confirm"}
            onChange={onSwitch}
          />
        </div>
      )}

      {status === null ? null : step.kind === "setup" ? (
        <div className="mt-4 grid gap-4 border-t border-line-200 pt-4 md:grid-cols-[auto_minmax(0,1fr)] md:items-start">
          {/* The server draws the square; it is shown as an image so nothing in it is ever markup here. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={`data:image/svg+xml;charset=utf-8,${encodeURIComponent(step.setup.qr_svg)}`}
            alt="QR code to scan with your authenticator app"
            className="h-48 w-48 rounded-md border border-line-200 bg-white"
          />
          <div className="min-w-0">
            <p className="text-sm leading-6 text-ink-700">
              1. Scan this code with an authenticator app, such as Google Authenticator, Microsoft Authenticator or
              1Password. Or type the key into the app instead.
            </p>
            <div className="mt-2 flex min-w-0 flex-wrap items-center gap-2">
              <code className="min-w-0 break-all rounded-md bg-surface-50 px-2 py-1 font-mono text-sm text-ink-900">
                {step.setup.secret}
              </code>
              <button type="button" className="field-button-secondary shrink-0" onClick={() => void copy(step.setup.secret, "Key")}>
                <Copy className="h-4 w-4" aria-hidden />
                Copy key
              </button>
            </div>
            <p className="mt-4 text-sm leading-6 text-ink-700">
              2. Enter the 6-digit code the app shows. Two-step verification turns on once the code is checked.
            </p>
            {codeForm("Codes change every 30 seconds. This setup is dropped if it isn't finished within 15 minutes.", "Turn on", false, cancelSetup)}
          </div>
        </div>
      ) : step.kind === "codes" ? (
        <div className="mt-4 border-t border-line-200 pt-4">
          <p className="text-sm font-semibold text-ink-900">Save these codes</p>
          <p className="mt-1 text-sm leading-6 text-ink-700">
            If you lose your phone, each code signs you in once instead of the 6-digit code. Keep them somewhere safe —
            they won&rsquo;t be shown again.
          </p>
          <ul className="mt-3 grid max-w-md grid-cols-2 gap-2 rounded-md border border-line-200 bg-surface-50 p-3 font-mono text-sm text-ink-900">
            {step.codes.map((recoveryCode) => (
              <li key={recoveryCode}>{recoveryCode}</li>
            ))}
          </ul>
          <ButtonRow align="start" className="mt-3">
            <button type="button" className="field-button-secondary" onClick={() => void copy(step.codes.join("\n"), "Recovery codes")}>
              <Copy className="h-4 w-4" aria-hidden />
              Copy
            </button>
            <button type="button" className="field-button-secondary" onClick={() => download(step.codes)}>
              <Download className="h-4 w-4" aria-hidden />
              Download
            </button>
            <button type="button" className="field-button" disabled={!saved} onClick={() => go({ kind: "idle" })}>
              Done
            </button>
          </ButtonRow>
          {!saved ? <p className="mt-2 text-xs text-ink-500">Copy or download the codes to finish.</p> : null}
        </div>
      ) : step.kind === "confirm" ? (
        <div className="mt-4 border-t border-line-200 pt-4">
          <p className="text-sm font-semibold text-ink-900">
            {step.action === "disable" ? "Turn off two-step verification" : "Make new recovery codes"}
          </p>
          {step.action === "disable"
            ? codeForm("Enter the 6-digit code from your authenticator app.", "Turn off", true, () => go({ kind: "idle" }))
            : codeForm(
                "Enter the 6-digit code from your authenticator app. Your old recovery codes stop working as soon as the new ones are made.",
                "Make new codes",
                false,
                () => go({ kind: "idle" })
              )}
        </div>
      ) : view === "on" ? (
        <div className="mt-3">
          <button type="button" className="field-button-secondary" onClick={() => go({ kind: "confirm", action: "regenerate" })}>
            Make new recovery codes
          </button>
        </div>
      ) : null}
    </section>
  );
}
