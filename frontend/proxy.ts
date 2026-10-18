import { NextResponse, type NextRequest } from "next/server";

import {
  apiOrigin,
  csrfRefusal,
  refuseCrossSite,
  refuseUnconfigured,
  sessionTokenOf,
  upstreamHeaders,
  upstreamUrl
} from "@/lib/server/apiProxy";

/**
 * EVERY `/api/*` CALL THE BROWSER MAKES, passed on to the API with the session cookie turned into a
 * bearer token. See `lib/server/apiProxy.ts` for the design and docs/SECURITY.md for why.
 *
 * The seven routes whose answer starts or ends a session are left to their route handlers under
 * `app/api/` (`NextResponse.next()`), which can read the answer. Everything else is a REWRITE to the
 * API: Vercel forwards it without a function in the path, so uploads and downloads stream at any size.
 * This code never reads a request body, which is what lets `vercel.json` set
 * `skipMiddlewareRequestBody`.
 */

const SESSION_ROUTES = new Set([
  "/api/auth/login",
  "/api/auth/google",
  "/api/auth/two-factor/verify",
  "/api/auth/change-password",
  "/api/auth/logout",
  "/api/session/adopt",
  "/api/session/end"
]);

export function proxy(request: NextRequest) {
  const path = request.nextUrl.pathname;
  if (SESSION_ROUTES.has(path)) return NextResponse.next();
  if (csrfRefusal(request)) return refuseCrossSite();
  const origin = apiOrigin();
  if (!origin) return refuseUnconfigured();
  const headers = upstreamHeaders(request, sessionTokenOf(request));
  const response = NextResponse.rewrite(new URL(upstreamUrl(request, origin)), { request: { headers } });
  // Never stored by Vercel's CDN, whatever the API's Cache-Control says: every answer here belongs to
  // one signed-in person.
  response.headers.set("x-vercel-enable-rewrite-caching", "0");
  return response;
}

export const config = {
  matcher: "/api/:path*"
};
