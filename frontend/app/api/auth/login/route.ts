import { handleSignIn } from "@/lib/server/apiProxy";

/** Sign-in: the token goes into the session cookie, never to the page. See `lib/server/apiProxy.ts`. */
export async function POST(request: Request) {
  return handleSignIn(request, "/api/auth/login");
}
