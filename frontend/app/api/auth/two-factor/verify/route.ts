import { handleSignIn } from "@/lib/server/apiProxy";

export async function POST(request: Request) {
  return handleSignIn(request, "/api/auth/two-factor/verify");
}
