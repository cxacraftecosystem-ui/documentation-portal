"use client";

/**
 * SETTINGS → PASSWORD: change your own, or set a first one.
 *
 * Ported from the Design Prototype Workshop's card of the same name, with one more branch:
 *
 *  - An account WITH a password changes it, entering the current one.
 *  - An account WITHOUT one (it signs in with Google, Microsoft or Yahoo) may SET a first password,
 *    so it can sign in either way. The server asks for that provider's sign-in RUN AGAIN first
 *    (`lib/providerReauth.ts`): this card offers the provider's button, keeps what it answers for
 *    five minutes, and sends it with the password. A refusal carrying `X-Confirm-Sign-In` drops the
 *    confirmation and asks again.
 *
 * Either way the server ends every other session of the account and hands this one a fresh token in
 * `X-Session-Token`, which is adopted BEFORE `/me` is re-read.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { CheckCircle2, Eye, EyeOff, KeyRound } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { OtpCodeInput } from "@/components/ui/OtpCodeInput";
import { Toggle } from "@/components/ui/Toggle";
import { setToken } from "@/lib/api";
import { isCompleteCode } from "@/lib/otp";
import {
  REAUTH_STATE_PREFIX,
  beginSignIn,
  callbackErrorMessage,
  configuredOidcProviders,
  parseCallbackFragment,
  takePendingSignIn,
  type OidcProvider
} from "@/lib/oidcSignIn";
import {
  asReauthProvider,
  carriedProof,
  confirmMissing,
  confirmPrompt,
  confirmedLine,
  googleProof,
  oidcProof,
  proofIsFresh,
  reauthLabel,
  reauthProviderFor,
  type ProviderProof,
  type ReauthProvider
} from "@/lib/providerReauth";
import {
  FIRST_PASSWORD_NOTE,
  PASSWORD_CHANGE_SESSIONS,
  currentPasswordRefused,
  hasOwnPassword,
  newPasswordProblem,
  passwordFailureSentence
} from "@/lib/passwordChange";
import {
  MAX_PASSWORD_LENGTH,
  MIN_PASSWORD_LENGTH,
  asksForTwoFactorCode,
  asksToConfirmSignIn,
  changeOwnPassword,
  passwordRuleLine,
  signInProofFrom
} from "@/lib/signIn";

const CHANGED_HERE_ONLY = "Password changed. You are still signed in here; everywhere else, sign in with the new one.";
const CHANGED = "Password changed. Use the new one the next time you sign in.";
const TWO_FACTOR_CODE_MISSING = "Enter the code from your authenticator app to set a password.";
const FIRST_SET = "Password set. You can now sign in with it as well as the way you do now.";

/**
 * Google's own button, drawn into this card. Its answer is an ID token, kept as the confirmation; it
 * signs nobody in. The script is the one the sign-in page loads, and is loaded once.
 */
function GoogleConfirmButton({ clientId, onCredential }: { clientId: string; onCredential: (token: string) => void }) {
  const host = useRef<HTMLDivElement | null>(null);
  const answer = useRef(onCredential);
  useEffect(() => {
    answer.current = onCredential;
  }, [onCredential]);

  useEffect(() => {
    let live = true;
    const draw = () => {
      if (!live || !host.current || !window.google) return;
      window.google.accounts.id.initialize({
        client_id: clientId,
        callback: (response) => answer.current(response.credential)
      });
      host.current.replaceChildren();
      window.google.accounts.id.renderButton(host.current, {
        theme: "outline",
        size: "large",
        shape: "rectangular",
        text: "continue_with",
        width: Math.round(Math.min(320, Math.max(200, host.current.offsetWidth || 260)))
      });
    };
    if (window.google) {
      draw();
    } else {
      const existing = document.querySelector<HTMLScriptElement>('script[src="https://accounts.google.com/gsi/client"]');
      if (existing) {
        existing.addEventListener("load", draw, { once: true });
      } else {
        const script = document.createElement("script");
        script.src = "https://accounts.google.com/gsi/client";
        script.async = true;
        script.defer = true;
        script.onload = draw;
        document.body.appendChild(script);
      }
    }
    return () => {
      live = false;
    };
  }, [clientId]);

  return <div ref={host} className="min-h-10 max-w-xs" />;
}

export function ChangePasswordCard() {
  const { user, refreshMe } = useAuth();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [code, setCode] = useState("");
  const [useRecoveryCode, setUseRecoveryCode] = useState(false);
  const [show, setShow] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [proof, setProof] = useState<ProviderProof | null>(null);
  const [confirmAsked, setConfirmAsked] = useState<ReauthProvider | null>(null);
  const [leaving, setLeaving] = useState(false);
  const [oidcProviders] = useState<OidcProvider[]>(() => configuredOidcProviders());
  const googleClientId = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;
  const [changed, setChanged] = useState<string | null>(null);
  const [currentRefusals, setCurrentRefusals] = useState(0);
  const currentBox = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (currentRefusals > 0) currentBox.current?.focus();
  }, [currentRefusals]);

  /**
   * BACK FROM MICROSOFT OR YAHOO with a code for this card (a `reauth.` state). Taken off the address
   * bar first — a code is single use — and kept, unredeemed, as the confirmation: the server redeems
   * it when the password is saved. Anything else in the fragment is the sign-in page's business.
   */
  useEffect(() => {
    const callback = parseCallbackFragment(window.location.hash);
    if (!callback || !callback.state.startsWith(REAUTH_STATE_PREFIX)) return;
    window.history.replaceState(null, "", window.location.pathname + window.location.search);
    const pending = takePendingSignIn(callback.state);
    if (!pending) {
      setError("That sign-in could not be finished in this tab. Start it again from here.");
      return;
    }
    if (callback.error || !callback.code) {
      setError(callbackErrorMessage(pending.provider, callback.error));
      return;
    }
    setProof(oidcProof(pending, callback.code));
  }, []);

  const confirmWithGoogle = useCallback((token: string) => {
    setError(null);
    setConfirmAsked(null);
    setProof(googleProof(token));
  }, []);

  async function confirmWithOidc(provider: OidcProvider) {
    setError(null);
    setLeaving(true);
    try {
      window.location.assign(await beginSignIn(provider, window.location.origin, null, window.sessionStorage, REAUTH_STATE_PREFIX));
    } catch {
      setLeaving(false);
      setError(callbackErrorMessage(provider.id, null));
    }
  }

  if (!user) return null;
  const setting = !hasOwnPassword(user);
  // A first password on an account with two-step on needs a code from the authenticator app: the
  // session alone is not enough to add a second way in (the server refuses it with
  // `X-Two-Factor-Required`).
  const needsCode = setting && Boolean(user.twoFactorEnabled);
  // Which provider to run again: the one the server last named, else the account's own.
  const wanted = confirmAsked ?? reauthProviderFor(user);
  const confirmed = setting && proofIsFresh(proof);
  // The buttons this build can draw: the wanted provider's when it is configured here, else every
  // configured one (any provider that vouches for this address is accepted).
  const googleOffered = Boolean(googleClientId);
  const offersWanted = wanted === "GOOGLE" ? googleOffered : oidcProviders.some((p) => p.id === wanted);
  const showGoogle = googleOffered && (!offersWanted || wanted === "GOOGLE");
  const shownOidc = oidcProviders.filter((p) => !offersWanted || p.id === wanted);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setChanged(null);
    const problem = newPasswordProblem({ current: setting ? "" : current, next, confirm });
    if (problem) {
      setError(problem);
      return;
    }
    if (needsCode && (useRecoveryCode ? !code.trim() : !isCompleteCode(code))) {
      setError(TWO_FACTOR_CODE_MISSING);
      return;
    }
    const sending = setting && proofIsFresh(proof) ? proof : null;
    if (setting && !sending) {
      setProof(null);
      setError(confirmMissing(wanted));
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const fresh = await changeOwnPassword(setting ? "" : current, next, needsCode ? code : undefined, sending?.fields);
      setProof(null);
      setCurrent("");
      setCode("");
      setNext("");
      setConfirm("");
      setShow(false);
      if (fresh) {
        setToken(fresh);
        void refreshMe();
        setChanged(setting ? FIRST_SET : CHANGED_HERE_ONLY);
      } else {
        setChanged(setting ? FIRST_SET : CHANGED);
        if (setting) void refreshMe();
      }
    } catch (err) {
      const askedFor = asksToConfirmSignIn(err);
      if (askedFor) {
        // The confirmation was missing, stale or for another address: drop it and ask again.
        setProof(null);
        setConfirmAsked(asReauthProvider(askedFor));
      } else if (asksForTwoFactorCode(err)) {
        // The form is kept; only the code is cleared, so the next one can be typed straight in. The
        // confirmation stays good: a sign-in already accepted comes back as what stands in for it.
        setCode("");
        const carried = signInProofFrom(err);
        if (carried && sending) setProof(carriedProof(sending, carried));
      } else if (!setting && currentPasswordRefused(err)) {
        setCurrent("");
        setCurrentRefusals((count) => count + 1);
      }
      setError(passwordFailureSentence(err, setting ? "Could not set the password." : "Could not change the password."));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="panel p-5">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <KeyRound className="h-4 w-4" aria-hidden />
        </span>
        <h2 className="font-display font-bold text-ink-900">{setting ? "Set a password" : "Change password"}</h2>
      </div>
      <p className="mt-1.5 text-xs leading-5 text-ink-500">
        {setting
          ? "You sign in with Google, Microsoft or Yahoo. Add a password to sign in with your email address too."
          : "The password you sign in with. You need the current one to choose another."}
      </p>

      <form onSubmit={submit} className="mt-3 grid max-w-md gap-3">
        {/* For a password manager: the account this password belongs to. */}
        <input type="text" name="username" autoComplete="username" value={user.email} readOnly hidden />

        {/* Both regions mounted from first paint, so what lands in them is announced. */}
        <div
          role="alert"
          aria-live="assertive"
          className={error ? "rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600" : "sr-only"}
        >
          {error ?? ""}
        </div>
        <p
          role="status"
          aria-live="polite"
          className={changed ? "rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700" : "sr-only"}
        >
          {changed ?? ""}
        </p>

        {setting ? (
          <div className="rounded-md border border-line-200 bg-surface-50 p-3">
            {confirmed ? (
              <p className="flex items-start gap-2 text-sm text-ink-700">
                <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-success-600" aria-hidden />
                {confirmedLine(proof?.provider ?? wanted)}
              </p>
            ) : (
              <>
                <p className="text-sm text-ink-700">{confirmPrompt(wanted)}</p>
                <div className="mt-2.5 flex flex-wrap items-center gap-2">
                  {showGoogle && googleClientId ? (
                    <GoogleConfirmButton clientId={googleClientId} onCredential={confirmWithGoogle} />
                  ) : null}
                  {shownOidc.map((provider) => (
                    <button
                      key={provider.id}
                      type="button"
                      className="field-button-secondary"
                      disabled={leaving}
                      onClick={() => void confirmWithOidc(provider)}
                    >
                      {leaving ? "Opening…" : `Sign in with ${reauthLabel(provider.id)}`}
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>
        ) : (
          <div>
            <label htmlFor="settings-current-password" className="field-label">
              Current password
            </label>
            <input
              id="settings-current-password"
              ref={currentBox}
              type={show ? "text" : "password"}
              autoComplete="current-password"
              required
              maxLength={MAX_PASSWORD_LENGTH}
              value={current}
              onChange={(event) => setCurrent(event.target.value)}
              className="field-input mt-1.5"
            />
          </div>
        )}

        {needsCode ? (
          <div className="grid gap-2">
            {useRecoveryCode ? (
              <div>
                <label htmlFor="settings-two-factor-code" className="field-label">
                  Recovery code
                </label>
                <input
                  id="settings-two-factor-code"
                  type="text"
                  autoComplete="off"
                  autoCapitalize="none"
                  spellCheck={false}
                  required
                  maxLength={20}
                  placeholder="xxxx-xxxx"
                  value={code}
                  onChange={(event) => setCode(event.target.value)}
                  className="field-input mt-1.5 font-mono tracking-[0.15em]"
                />
              </div>
            ) : (
              <OtpCodeInput
                value={code}
                onChange={setCode}
                label="Code from your authenticator app"
                describedBy="settings-two-factor-hint"
                error={Boolean(error) && !code}
              />
            )}
            <p id="settings-two-factor-hint" className="text-xs leading-5 text-ink-500">
              Two-step verification is on for your account.
            </p>
            <Toggle
              label="Use a recovery code instead"
              checked={useRecoveryCode}
              onChange={(nextValue) => {
                setUseRecoveryCode(nextValue);
                setCode("");
              }}
            />
          </div>
        ) : null}

        <div>
          <label htmlFor="settings-new-password" className="field-label">
            New password
          </label>
          <div className="relative mt-1.5">
            <input
              id="settings-new-password"
              type={show ? "text" : "password"}
              autoComplete="new-password"
              required
              minLength={MIN_PASSWORD_LENGTH}
              maxLength={MAX_PASSWORD_LENGTH}
              value={next}
              onChange={(event) => setNext(event.target.value)}
              className="field-input pr-11"
            />
            <button
              type="button"
              aria-label={show ? "Hide passwords" : "Show passwords"}
              aria-pressed={show}
              onClick={() => setShow((value) => !value)}
              className="absolute right-2 top-1/2 -translate-y-1/2 grid place-items-center rounded-md p-1 text-ink-500 hover:text-ink-700 pointer-coarse:right-0 pointer-coarse:h-11 pointer-coarse:w-11"
            >
              {show ? <EyeOff size={20} aria-hidden /> : <Eye size={20} aria-hidden />}
            </button>
          </div>
        </div>

        <div>
          <label htmlFor="settings-confirm-password" className="field-label">
            Repeat password
          </label>
          <input
            id="settings-confirm-password"
            type={show ? "text" : "password"}
            autoComplete="new-password"
            required
            minLength={MIN_PASSWORD_LENGTH}
            maxLength={MAX_PASSWORD_LENGTH}
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            className="field-input mt-1.5"
          />
        </div>

        <p className="text-xs leading-5 text-ink-500">
          {passwordRuleLine(setting ? FIRST_PASSWORD_NOTE : PASSWORD_CHANGE_SESSIONS)}
        </p>

        <div>
          <button type="submit" className="field-button" disabled={saving || (setting && !confirmed)}>
            {saving ? "Saving…" : setting ? "Set password" : "Change password"}
          </button>
        </div>
      </form>
    </section>
  );
}
