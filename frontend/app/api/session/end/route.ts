import { handleEnd } from "@/lib/server/apiProxy";

export async function POST(request: Request) {
  return handleEnd(request);
}
