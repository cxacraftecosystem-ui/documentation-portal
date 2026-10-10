import { NextResponse, type NextRequest } from "next/server";

import { ANDROID_SIGN_IN_SCHEME, callbackTarget } from "@/lib/oidcSignIn";

/**
 * WHERE MICROSOFT AND YAHOO SEND THE BROWSER BACK TO — the one redirect URI both app registrations
 * hold, for the web and for the Android app alike. See `lib/oidcSignIn.ts`.
 *
 * A ROUTE HANDLER AND NOT A PAGE, for the phone: a browser tab follows a server redirect into an app's
 * own scheme as part of the navigation the person started, where a script-driven one after the page
 * has loaded may be held back for want of a tap. For the web it costs nothing — the answer goes back
 * to `/login` in the fragment, which never reaches a server log or a `Referer`.
 *
 * `no-store` and `no-referrer`: the query carries a single-use code, and neither a cache nor the
 * next page's request has any business keeping it.
 */
export function GET(request: NextRequest) {
  const target = callbackTarget(request.nextUrl.searchParams, ANDROID_SIGN_IN_SCHEME);
  const response = NextResponse.redirect(target.startsWith("/") ? new URL(target, request.nextUrl.origin) : target, 302);
  response.headers.set("Cache-Control", "no-store");
  response.headers.set("Referrer-Policy", "no-referrer");
  return response;
}
