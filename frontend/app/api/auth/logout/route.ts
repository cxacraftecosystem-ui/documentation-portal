import { handleLogout } from "@/lib/server/apiProxy";

/** Sign-out: told to the API, and the cookies cleared whatever it answers. */
export async function POST(request: Request) {
  return handleLogout(request, "/api/auth/logout");
}
