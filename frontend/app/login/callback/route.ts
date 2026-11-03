import { NextResponse, type NextRequest } from "next/server";

import { ANDROID_SIGN_IN_SCHEME, callbackTarget } from "@/lib/oidcSignIn";

export function GET(request: NextRequest) {
  const target = callbackTarget(request.nextUrl.searchParams, ANDROID_SIGN_IN_SCHEME);
  const response = NextResponse.redirect(target.startsWith("/") ? new URL(target, request.nextUrl.origin) : target, 302);
  response.headers.set("Cache-Control", "no-store");
  response.headers.set("Referrer-Policy", "no-referrer");
  return response;
}
