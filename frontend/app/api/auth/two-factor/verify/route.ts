import { handleSignIn } from "@/lib/server/apiProxy";

/** The second step of a sign-in: same as `/api/auth/login` once the code is right. */
export async function POST(request: Request) {
  return handleSignIn(request, "/api/auth/two-factor/verify");
}
