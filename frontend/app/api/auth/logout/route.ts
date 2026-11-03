import { handleLogout } from "@/lib/server/apiProxy";

export async function POST(request: Request) {
  return handleLogout(request, "/api/auth/logout");
}
