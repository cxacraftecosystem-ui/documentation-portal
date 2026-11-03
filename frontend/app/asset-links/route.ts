import assetLinks from "@/public/.well-known/assetlinks.json";

export const dynamic = "force-static";

export function GET() {
  return Response.json(assetLinks, { headers: { "Cache-Control": "public, max-age=3600" } });
}
