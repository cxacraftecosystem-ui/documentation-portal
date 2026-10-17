import { handleAdopt } from "@/lib/server/apiProxy";

/** The one-time move of a token a browser kept in localStorage into the session cookie. */
export async function POST(request: Request) {
  return handleAdopt(request);
}
