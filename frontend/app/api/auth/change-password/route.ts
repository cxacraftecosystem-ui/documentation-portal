import { handleChangePassword } from "@/lib/server/apiProxy";

export async function POST(request: Request) {
  return handleChangePassword(request, "/api/auth/change-password");
}
