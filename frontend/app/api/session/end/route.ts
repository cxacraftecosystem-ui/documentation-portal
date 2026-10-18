import { handleEnd } from "@/lib/server/apiProxy";

/** Clear a session the API has refused (scripts cannot delete an HttpOnly cookie). */
export async function POST(request: Request) {
  return handleEnd(request);
}
