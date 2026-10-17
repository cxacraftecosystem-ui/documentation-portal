import { handleSignIn } from "@/lib/server/apiProxy";

/** Google sign-in: same as `/api/auth/login` — the token goes into the session cookie. */
export async function POST(request: Request) {
  return handleSignIn(request, "/api/auth/google");
}
