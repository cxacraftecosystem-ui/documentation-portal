import { handleChangePassword } from "@/lib/server/apiProxy";

/** A password change: the fresh session the API hands back replaces the cookie. */
export async function POST(request: Request) {
  return handleChangePassword(request, "/api/auth/change-password");
}
