export type OidcProviderId = "MICROSOFT" | "YAHOO";

export interface OidcProvider {
  id: OidcProviderId;
  label: string;
  clientId: string;
  authorizeEndpoint: string;
  scope: string;
}

export const OIDC_CALLBACK_PATH = "/login/callback";

export const WEB_STATE_PREFIX = "web.";
export const APP_STATE_PREFIX = "app.";
export const REAUTH_STATE_PREFIX = "reauth.";

export const REAUTH_RETURN_PATH = "/settings";

export const ANDROID_SIGN_IN_SCHEME = "com.fieldrepository.app.signin";

export const PENDING_LIFETIME_MS = 10 * 60 * 1000;

const PENDING_KEY = "frp.oidc.pending";

const MICROSOFT_TENANT_KEYWORDS = ["common", "organizations", "consumers"];
const GUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface OidcEnvironment {
  microsoftClientId?: string;
  microsoftTenant?: string;
  yahooClientId?: string;
}

export function buildEnvironment(): OidcEnvironment {
  return {
    microsoftClientId: process.env.NEXT_PUBLIC_MICROSOFT_CLIENT_ID,
    microsoftTenant: process.env.NEXT_PUBLIC_MICROSOFT_TENANT,
    yahooClientId: process.env.NEXT_PUBLIC_YAHOO_CLIENT_ID
  };
}

export function microsoftTenant(raw: string | undefined): string | null {
  const value = (raw ?? "").trim().toLowerCase() || "common";
  return MICROSOFT_TENANT_KEYWORDS.includes(value) || GUID.test(value) ? value : null;
}

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

export function randomToken(bytes = 32): string {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return base64Url(buffer);
}

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
  if (provider.id === "MICROSOFT") {
    params.set("response_mode", "query");
    params.set("prompt", "select_account");
  }
  return `${provider.authorizeEndpoint}?${params.toString()}`;
}

export interface PendingSignIn {
  provider: OidcProviderId;
  state: string;
  codeVerifier: string;
  rawNonce: string;
  redirectUri: string;
  agreedAt: string | null;
  startedAt: number;
}

export async function beginSignIn(
  provider: OidcProvider,
  origin: string,
  agreedAt: string | null,
  storage: Pick<Storage, "setItem"> = window.sessionStorage,
  statePrefix: string = WEB_STATE_PREFIX
): Promise<string> {
  const pending: PendingSignIn = {
    provider: provider.id,
    state: `${statePrefix}${randomToken()}`,
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

export function parseCallbackFragment(hash: string): CallbackResult | null {
  const fragment = new URLSearchParams(hash.replace(/^#/, ""));
  const forwarded = fragment.get("oidc");
  if (!forwarded) return null;
  const params = new URLSearchParams(forwarded);
  const state = params.get("state");
  if (!state) return null;
  return { code: params.get("code"), state, error: params.get("error") };
}

const FORWARDED = ["code", "state", "error"] as const;

export function callbackTarget(query: URLSearchParams, appScheme: string = ANDROID_SIGN_IN_SCHEME): string {
  const kept = new URLSearchParams();
  for (const name of FORWARDED) {
    const value = query.get(name);
    if (value) kept.set(name, value.slice(0, 4096));
  }
  const state = kept.get("state") ?? "";
  if (state.startsWith(APP_STATE_PREFIX)) return `${appScheme}://oidc/callback?${kept.toString()}`;
  if (state.startsWith(REAUTH_STATE_PREFIX)) return `${REAUTH_RETURN_PATH}#oidc=${encodeURIComponent(kept.toString())}`;
  return `/login#oidc=${encodeURIComponent(kept.toString())}`;
}

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

export function callbackErrorMessage(id: OidcProviderId, error: string | null): string {
  const label = providerLabel(id);
  if (error === "access_denied") return `Signing in with ${label} was cancelled. You can try again.`;
  return `${label} sign-in didn’t complete. Try again.`;
}
