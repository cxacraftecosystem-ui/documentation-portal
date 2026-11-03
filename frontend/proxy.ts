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
  response.headers.set("x-vercel-enable-rewrite-caching", "0");
  return response;
}

export const config = {
  matcher: "/api/:path*"
};
