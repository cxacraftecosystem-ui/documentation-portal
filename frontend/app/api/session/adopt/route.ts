import { handleAdopt } from "@/lib/server/apiProxy";

export async function POST(request: Request) {
  return handleAdopt(request);
}
