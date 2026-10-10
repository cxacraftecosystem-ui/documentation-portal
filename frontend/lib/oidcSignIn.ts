/**
 * "CONTINUE WITH MICROSOFT" AND "CONTINUE WITH YAHOO": the browser's half of the sign-in.
 *
 * ── THE SHAPE, AND WHY IT IS NOT GOOGLE'S ───────────────────────────────────────────────────────
 *
 * Google's script hands this page an ID token, which is posted to `POST /auth/login`. Microsoft and
 * Yahoo are driven as plain OpenID Connect instead: this page sends the browser to the provider with
 * an authorization-code request carrying PKCE (S256) and a nonce, the provider sends it back to
 * `/login/callback` with a code, and the code — with the PKCE verifier, the redirect URI and the RAW
 * nonce — is posted to `POST /auth/login`, which redeems it with the client secret and verifies the
 * ID token against the provider's published keys (`backend/app/services/oidc_sign_in.py`). Yahoo's
 * token endpoint accepts a client secret only and answers no cross-origin request, so a browser could
 * not redeem the code itself even if it wanted to; Microsoft is driven the same way so there is one
 * path to keep correct, not two.
 *
 * ── THE NONCE ───────────────────────────────────────────────────────────────────────────────────
 *
 * The provider is sent `base64url(sha256(raw))` and the server is sent `raw`, so an ID token is only
 * ever accepted from the browser tab that started the flow which minted it — a code or a token lifted
 * from anywhere else is missing the half that never left this tab.
 *
 * ── ONE REDIRECT URI, SHARED WITH THE PHONE ─────────────────────────────────────────────────────
 *
 * The Android app opens the provider in a browser tab with this same `/login/callback` and a `state`
 * that starts `app.`; the callback route hands those back to the app over its own scheme (see
 * `callbackTarget`). One URI per provider is all an app registration needs.
 *
 * ── SHOWN ONLY WHEN CONFIGURED ──────────────────────────────────────────────────────────────────
 *
 * A provider whose client ID is not in this build's environment has no button at all — not a
 * disabled one and not a badge. `NEXT_PUBLIC_*` values are inlined at build time, so each is read by
 * its literal name below.
 */

export type OidcProviderId = "MICROSOFT" | "YAHOO";

export interface OidcProvider {
  id: OidcProviderId;
  label: string;
  clientId: string;
  /** The provider's authorization endpoint for this build's configuration. */
  authorizeEndpoint: string;
  scope: string;
}

/** Where the provider sends the browser back to. Registered, verbatim, on both providers. */
export const OIDC_CALLBACK_PATH = "/login/callback";

/** A `state` this page minted. The callback route sends anything else to the app. */
export const WEB_STATE_PREFIX = "web.";
/** A `state` the Android app minted: the callback route hands the code to the app. */
export const APP_STATE_PREFIX = "app.";

/** The Android app's own scheme for the handover, as `android/app/build.gradle.kts` registers it. */
export const ANDROID_SIGN_IN_SCHEME = "com.fieldrepository.app.signin";

/** How long a started sign-in may take before its stored half is discarded. */
export const PENDING_LIFETIME_MS = 10 * 60 * 1000;

const PENDING_KEY = "frp.oidc.pending";

const MICROSOFT_TENANT_KEYWORDS = ["common", "organizations", "consumers"];
const GUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface OidcEnvironment {
  microsoftClientId?: string;
  microsoftTenant?: string;
  yahooClientId?: string;
}

/** This build's environment, read by literal name so Next inlines it. */
export function buildEnvironment(): OidcEnvironment {
  return {
    microsoftClientId: process.env.NEXT_PUBLIC_MICROSOFT_CLIENT_ID,
    microsoftTenant: process.env.NEXT_PUBLIC_MICROSOFT_TENANT,
    yahooClientId: process.env.NEXT_PUBLIC_YAHOO_CLIENT_ID
  };
}

/** The tenant segment, or null when the configured value is not one Microsoft accepts. */
export function microsoftTenant(raw: string | undefined): string | null {
  const value = (raw ?? "").trim().toLowerCase() || "common";
  return MICROSOFT_TENANT_KEYWORDS.includes(value) || GUID.test(value) ? value : null;
}

/** The providers this build can offer, in the order the card draws them. */
export function configuredOidcProviders(env: OidcEnvironment = buildEnvironment()): OidcProvider[] {
  const providers: OidcProvider[] = [];
  const microsoftId = env.microsoftClientId?.trim();
  const tenant = microsoftTenant(env.microsoftTenant);
  if (microsoftId && tenant) {
    providers.push({
      id: "MICROSOFT",
      label: "Microsoft",
      clientId: microsoftId,
      authorizeEndpoint: `https://login.microsoftonline.com/${tenant}/oauth2/v2.0/authorize`,
      scope: "openid email profile"
    });
  }
  const yahooId = env.yahooClientId?.trim();
  if (yahooId) {
    providers.push({
      id: "YAHOO",
      label: "Yahoo",
      clientId: yahooId,
      authorizeEndpoint: "https://api.login.yahoo.com/oauth2/request_auth",
      scope: "openid email profile"
    });
  }
  return providers;
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** A URL-safe random string: 32 bytes is 43 characters, which is also PKCE's minimum verifier. */
export function randomToken(bytes = 32): string {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return base64Url(buffer);
}

/** `base64url(sha256(text))`, unpadded: the PKCE challenge and the nonce the provider is sent. */
export async function sha256Base64Url(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return base64Url(new Uint8Array(digest));
}

export interface AuthorizeRequest {
  redirectUri: string;
  state: string;
  codeChallenge: string;
  nonceDigest: string;
}

export function authorizeUrl(provider: OidcProvider, request: AuthorizeRequest): string {
  const params = new URLSearchParams({
    client_id: provider.clientId,
    response_type: "code",
    redirect_uri: request.redirectUri,
    scope: provider.scope,
    state: request.state,
    nonce: request.nonceDigest,
    code_challenge: request.codeChallenge,
    code_challenge_method: "S256"
  });
  // Microsoft would otherwise default to a fragment for some account types; the callback is a
  // route handler, which only ever sees the query.
  if (provider.id === "MICROSOFT") {
    params.set("response_mode", "query");
    params.set("prompt", "select_account");
  }
  return `${provider.authorizeEndpoint}?${params.toString()}`;
}

/** What this tab keeps between leaving for the provider and coming back. Never sent anywhere. */
export interface PendingSignIn {
  provider: OidcProviderId;
  state: string;
  codeVerifier: string;
  rawNonce: string;
  redirectUri: string;
  /** When a consent box was ticked, on a sign-in screen that has one; null on this one. */
  agreedAt: string | null;
  startedAt: number;
}

/** Mint everything a sign-in needs, store the tab's half, and return where to send the browser. */
export async function beginSignIn(
  provider: OidcProvider,
  origin: string,
  agreedAt: string | null,
  storage: Pick<Storage, "setItem"> = window.sessionStorage
): Promise<string> {
  const pending: PendingSignIn = {
    provider: provider.id,
    state: `${WEB_STATE_PREFIX}${randomToken()}`,
    codeVerifier: randomToken(48),
    rawNonce: randomToken(),
    redirectUri: `${origin}${OIDC_CALLBACK_PATH}`,
    agreedAt,
    startedAt: Date.now()
  };
  storage.setItem(PENDING_KEY, JSON.stringify(pending));
  return authorizeUrl(provider, {
    redirectUri: pending.redirectUri,
    state: pending.state,
    codeChallenge: await sha256Base64Url(pending.codeVerifier),
    nonceDigest: await sha256Base64Url(pending.rawNonce)
  });
}

/**
 * The stored half of the sign-in this callback answers, removed as it is read — a code is single use
 * and so is its verifier. Null when there is none, when the `state` is not the one this tab minted
 * (a callback this tab did not start is never completed), or when it is too old.
 */
export function takePendingSignIn(
  state: string,
  storage: Pick<Storage, "getItem" | "removeItem"> = window.sessionStorage,
  now: number = Date.now()
): PendingSignIn | null {
  const raw = storage.getItem(PENDING_KEY);
  storage.removeItem(PENDING_KEY);
  if (!raw) return null;
  let pending: PendingSignIn;
  try {
    pending = JSON.parse(raw) as PendingSignIn;
  } catch {
    return null;
  }
  if (!pending || typeof pending.state !== "string" || pending.state !== state) return null;
  if (typeof pending.startedAt !== "number" || now - pending.startedAt > PENDING_LIFETIME_MS) return null;
  return pending;
}

export interface CallbackResult {
  code: string | null;
  state: string;
  error: string | null;
}

/** The provider's answer, as the callback route forwarded it in `/login#oidc=…`. */
export function parseCallbackFragment(hash: string): CallbackResult | null {
  const fragment = new URLSearchParams(hash.replace(/^#/, ""));
  const forwarded = fragment.get("oidc");
  if (!forwarded) return null;
  const params = new URLSearchParams(forwarded);
  const state = params.get("state");
  if (!state) return null;
  return { code: params.get("code"), state, error: params.get("error") };
}

/** Only these three ever travel on; anything else a provider appends is dropped. */
const FORWARDED = ["code", "state", "error"] as const;

/**
 * Where `/login/callback` sends the browser: to the Android app for a `state` the app minted, and
 * otherwise back to `/login` with the answer in the FRAGMENT — never the query — so the code is in
 * no server log, no `Referer` and no history entry once the page has read it.
 */
export function callbackTarget(query: URLSearchParams, appScheme: string = ANDROID_SIGN_IN_SCHEME): string {
  const kept = new URLSearchParams();
  for (const name of FORWARDED) {
    const value = query.get(name);
    if (value) kept.set(name, value.slice(0, 4096));
  }
  const state = kept.get("state") ?? "";
  if (state.startsWith(APP_STATE_PREFIX)) return `${appScheme}://oidc/callback?${kept.toString()}`;
  return `/login#oidc=${encodeURIComponent(kept.toString())}`;
}

/** The body `POST /auth/login` takes for these two providers. */
export function loginBody(pending: PendingSignIn, code: string) {
  return {
    oidcProvider: pending.provider,
    oidcCode: code,
    oidcCodeVerifier: pending.codeVerifier,
    oidcRedirectUri: pending.redirectUri,
    oidcNonce: pending.rawNonce
  };
}

export function providerLabel(id: OidcProviderId): string {
  return id === "MICROSOFT" ? "Microsoft" : "Yahoo";
}

/** What the card says when the provider sent the browser back without a code. */
export function callbackErrorMessage(id: OidcProviderId, error: string | null): string {
  const label = providerLabel(id);
  if (error === "access_denied") return `Signing in with ${label} was cancelled. You can try again.`;
  return `Signing in with ${label} did not complete. Try again, or use another way to sign in.`;
}
