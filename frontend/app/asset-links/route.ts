import assetLinks from "@/public/.well-known/assetlinks.json";

/**
 * The Digital Asset Links file that verifies the Android app's /set-password App Link, served by a
 * route because the prebuilt Vercel deploy leaves `public/.well-known/` out of the upload — the
 * static file 404s in production. next.config.ts rewrites /.well-known/assetlinks.json here. The
 * JSON itself stays in public/ so there is one copy of the release fingerprint.
 */
export const dynamic = "force-static";

export function GET() {
  return Response.json(assetLinks, { headers: { "Cache-Control": "public, max-age=3600" } });
}
