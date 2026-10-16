"use client";

import { useEffect, useRef, useState } from "react";
import { Copy, Download, ShieldCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { useToast } from "@/components/ui/Toast";
import { ApiError } from "@/lib/api";
import {
  beginTwoFactorSetup,
  disableTwoFactor,
  enableTwoFactor,
  fetchTwoFactorStatus,
  makeNewRecoveryCodes,
  recoveryCodesFileText,
  type TwoFactorSetup,
  type TwoFactorStatus
} from "@/lib/twoFactor";
import { formatDate } from "@/lib/format";
import { saveBlobToDevice } from "@/lib/saveToDevice";

/**
 * Two-step verification for THIS account: turn it on, see how many recovery codes are left, make new
 * ones, turn it off. Open to every role — it is the account's own lock, so there is no gate here.
 *
 * One card, one step at a time, never a modal: setup is a short conversation (scan, type a code, save
 * the recovery codes) and the page around it is the account screen the person came to anyway.
 *
 * Everything is decided on the server: the QR square arrives drawn, a code is checked and SPENT there,
 * and the recovery codes exist in plain text only in the one response that issues them. That is why
 * the codes step refuses to be left by accident — they cannot be shown again.
 */

type Step =
  | { kind: "idle" }
  | { kind: "setup"; setup: TwoFactorSetup }
  | { kind: "codes"; codes: string[] }
  | { kind: "confirm"; action: "disable" | "regenerate" };

export function TwoStepVerificationCard() {
  const { user } = useAuth();
  const { toast } = useToast();
  const [status, setStatus] = useState<TwoFactorStatus | null>(null);
  const [step, setStep] = useState<Step>({ kind: "idle" });
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const box = useRef<HTMLInputElement | null>(null);

  async function reload() {
    try {
      setStatus(await fetchTwoFactorStatus());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't load this setting. Try again.");
    }
  }

  useEffect(() => {
    let live = true;
    void fetchTwoFactorStatus()
      .then((answer) => {
        if (live) setStatus(answer);
      })
      .catch((err: unknown) => {
        if (live) setError(err instanceof Error ? err.message : "Couldn't load this setting. Try again.");
      });
    return () => {
      live = false;
    };
  }, []);

  // The code box is where the next keystroke belongs whenever one appears.
  useEffect(() => {
    if (step.kind === "setup" || step.kind === "confirm") box.current?.focus();
  }, [step]);

  function go(next: Step) {
    setStep(next);
    setCode("");
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
      setError(err instanceof ApiError ? err.message : "Couldn't connect. Check your connection and try again.");
      box.current?.focus();
    } finally {
      setBusy(false);
    }
  }

  function start() {
    void run(async () => go({ kind: "setup", setup: await beginTwoFactorSetup() }));
  }

  function submitCode(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const typed = code.trim();
    if (!typed || busy) return;
    if (step.kind === "setup") {
      void run(async () => {
        const codes = await enableTwoFactor(typed);
        go({ kind: "codes", codes });
        await reload();
      });
    } else if (step.kind === "confirm" && step.action === "disable") {
      void run(async () => {
        await disableTwoFactor(typed);
        go({ kind: "idle" });
        await reload();
        toast({ title: "Two-step verification is off", tone: "success" });
      });
    } else if (step.kind === "confirm") {
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
    saveBlobToDevice(blob, "field-repository-recovery-codes.txt");
    setSaved(true);
  }

  const codeForm = (label: string, hint: string, submitLabel: string, acceptsRecovery: boolean) => (
    <form onSubmit={submitCode} className="mt-4 grid gap-3 sm:max-w-sm">
      <div className="grid gap-1.5">
        <label htmlFor="two-step-setting-code" className="text-sm font-medium text-ink-900">
          {label}
        </label>
        <input
          id="two-step-setting-code"
          ref={box}
          type="text"
          required
          inputMode={acceptsRecovery ? "text" : "numeric"}
          autoComplete="one-time-code"
          autoCapitalize="none"
          spellCheck={false}
          maxLength={acceptsRecovery ? 20 : 7}
          placeholder={acceptsRecovery ? "123456 or xxxx-xxxx" : "123456"}
          value={code}
          onChange={(event) => setCode(event.target.value)}
          aria-describedby="two-step-setting-hint"
          className="field-input text-center font-mono tracking-[0.2em]"
        />
        <p id="two-step-setting-hint" className="text-xs leading-5 text-ink-500">
          {hint}
        </p>
      </div>
      <div className="flex flex-wrap gap-2">
        <button type="submit" className="field-button" disabled={busy || !code.trim()}>
          {busy ? "Checking…" : submitLabel}
        </button>
        <button type="button" className="field-button-secondary" disabled={busy} onClick={() => go({ kind: "idle" })}>
          Cancel
        </button>
      </div>
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
        After your password or Google, Microsoft or Yahoo sign-in, also ask for a 6-digit code from an authenticator
        app on your phone.
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
      ) : step.kind === "setup" ? (
        <div className="mt-4 grid gap-4 md:grid-cols-[auto_minmax(0,1fr)] md:items-start">
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
            <div className="mt-2 flex min-w-0 items-center gap-2">
              <code className="min-w-0 break-all rounded-md bg-surface-50 px-2 py-1 font-mono text-sm text-ink-900">
                {step.setup.secret}
              </code>
              <button type="button" className="field-button-secondary shrink-0" onClick={() => void copy(step.setup.secret, "Key")}>
                <Copy className="h-4 w-4" aria-hidden />
                Copy key
              </button>
            </div>
            <p className="mt-4 text-sm leading-6 text-ink-700">2. Enter the 6-digit code the app shows.</p>
            {codeForm("6-digit code", "Codes change every 30 seconds.", "Turn on", false)}
          </div>
        </div>
      ) : step.kind === "codes" ? (
        <div className="mt-4">
          <p className="text-sm font-semibold text-ink-900">Save these codes</p>
          <p className="mt-1 text-sm leading-6 text-ink-700">
            If you lose your phone, each code signs you in once instead of the 6-digit code. Keep them somewhere safe —
            they won&rsquo;t be shown again.
          </p>
          <ul className="mt-3 grid max-w-md grid-cols-2 gap-2 rounded-md border border-line-200 bg-surface-50 p-3 font-mono text-sm text-ink-900">
            {step.codes.map((recovery) => (
              <li key={recovery}>{recovery}</li>
            ))}
          </ul>
          <div className="mt-3 flex flex-wrap gap-2">
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
          </div>
          {!saved ? <p className="mt-2 text-xs text-ink-500">Copy or download the codes to finish.</p> : null}
        </div>
      ) : step.kind === "confirm" ? (
        step.action === "disable" ? (
          codeForm(
            "Code",
            "Enter the 6-digit code from your authenticator app, or one of your recovery codes.",
            "Turn off",
            true
          )
        ) : (
          codeForm(
            "6-digit code",
            "Your old recovery codes stop working as soon as the new ones are made.",
            "Make new codes",
            false
          )
        )
      ) : status.enabled ? (
        <div className="mt-4">
          <p className="text-sm text-ink-900">
            <span className="font-semibold text-success-600">On</span>
            {status.enabled_at ? <span className="text-ink-500"> since {formatDate(status.enabled_at)}</span> : null}
          </p>
          <p className="mt-1 text-sm text-ink-700">
            {status.recovery_codes_remaining === 1
              ? "1 recovery code left."
              : `${status.recovery_codes_remaining} recovery codes left.`}
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <button type="button" className="field-button-secondary" onClick={() => go({ kind: "confirm", action: "regenerate" })}>
              Make new recovery codes
            </button>
            <button
              type="button"
              className="field-danger"
              onClick={() => go({ kind: "confirm", action: "disable" })}
            >
              Turn off
            </button>
          </div>
        </div>
      ) : (
        <div className="mt-4">
          <p className="text-sm text-ink-700">Off.</p>
          <button type="button" className="field-button mt-3" disabled={busy} onClick={start}>
            {busy ? "Starting…" : "Turn on"}
          </button>
        </div>
      )}
    </section>
  );
}
