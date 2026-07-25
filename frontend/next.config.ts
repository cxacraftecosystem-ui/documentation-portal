import type { NextConfig } from "next";

/**
 * Next.js configuration for the Field Repository web client.
 *
 * Deployment target is **Vercel** (Root Directory `frontend`) talking to the FastAPI backend
 * behind CloudFront — see `docs/DEPLOYMENT_VERCEL.md`. Two deliberate non-settings:
 *
 * - No `output: "standalone"`. That mode emits a self-hosted Node server bundle; Vercel's build
 *   pipeline needs the default output and errors out (or silently ships nothing) when it is set.
 *   Only add it if this app is ever moved onto the EC2 box / a container.
 * - No rewrites/proxy to the API. The browser calls the backend origin directly
 *   (`lib/api.ts` -> `${NEXT_PUBLIC_API_URL}/api/...`), so no API traffic passes through a Vercel
 *   Function and signed-S3 uploads stay off Vercel's bandwidth. (The three `[id]/edit` routes still
 *   render on demand — dynamic segment, no generateStaticParams — but fetch nothing server-side.)
 */

const isProduction = process.env.NODE_ENV === "production";

/**
 * Hosts that may serve media through `next/image`.
 *
 * Media URLs are minted by the backend (`backend/app/services/s3.py::public_url_for_key`), which
 * promotes real AWS hosts to their **dual-stack** form (`s3.dualstack.<region>.amazonaws.com`) so
 * media loads on IPv6-only mobile networks. Both the plain regional and the dual-stack host are
 * allowed because older rows may still hold the pre-promotion URL. Localhost covers the MinIO
 * container from `docker-compose.yml` during local development.
 *
 * Nothing renders through `next/image` today (media is shown with plain `<img>`/`<audio>` tags so
 * signed and cross-region URLs pass through untouched), but an unconfigured host is a hard runtime
 * error the moment somebody switches one component over — so the allowlist is declared up front.
 * Note Next.js only allows the `**` wildcard at the *start* of a hostname, so the bucket hosts are
 * listed literally: change `AWS_S3_BUCKET`/`AWS_REGION` on the backend and you must add the new
 * host here too.
 */
const remotePatterns: NonNullable<NextConfig["images"]>["remotePatterns"] = [
  // Any CloudFront distribution — currently d2b34i3e92al6i.cloudfront.net, which fronts the API
  // (and any media proxied through it) over HTTPS + IPv6.
  { protocol: "https", hostname: "**.cloudfront.net", pathname: "/**" },
  // Production media bucket: dual-stack host (what the backend mints today)…
  {
    protocol: "https",
    hostname: "fieldrepo-media-626159998512.s3.dualstack.ap-south-1.amazonaws.com",
    pathname: "/**"
  },
  // …and the plain regional host, still present on rows written before dual-stack promotion.
  {
    protocol: "https",
    hostname: "fieldrepo-media-626159998512.s3.ap-south-1.amazonaws.com",
    pathname: "/**"
  },
  // Local MinIO (docker-compose) — path-style URLs, e.g. http://localhost:9000/field-repository/...
  { protocol: "http", hostname: "localhost", port: "9000", pathname: "/**" },
  { protocol: "http", hostname: "127.0.0.1", port: "9000", pathname: "/**" }
];

/**
 * Response headers applied to every route.
 *
 * These are intentionally conservative — the app is a pure SPA-style client, embeds nothing, and
 * is never meant to be framed. The one thing that must NOT be over-tightened is `Permissions-Policy`:
 * field capture depends on `navigator.geolocation` (LocationFields), `getUserMedia({ audio: true })`
 * (MediaCaptureField / questionnaire recorder) and camera-backed file inputs, so those three
 * features stay enabled for our own origin instead of being blanket-denied.
 */
const securityHeaders = [
  // Never sniff a response into a different Content-Type than the one the server declared.
  { key: "X-Content-Type-Options", value: "nosniff" },
  // The app is never legitimately embedded; DENY blocks clickjacking outright.
  { key: "X-Frame-Options", value: "DENY" },
  // Modern equivalent of the above for browsers that prefer CSP framing rules.
  { key: "Content-Security-Policy", value: "frame-ancestors 'none'" },
  // Send the full URL only same-origin; cross-origin requests leak the origin at most, and nothing
  // at all when downgrading HTTPS -> HTTP.
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  // Keep the capture features the field workflows need; deny the rest.
  {
    key: "Permissions-Policy",
    value: [
      "geolocation=(self)",
      "microphone=(self)",
      "camera=(self)",
      "clipboard-write=(self)",
      "autoplay=(self)",
      "accelerometer=()",
      "gyroscope=()",
      "magnetometer=()",
      "payment=()",
      "usb=()",
      "xr-spatial-tracking=()"
    ].join(", ")
  }
];

const nextConfig: NextConfig = {
  reactStrictMode: true,
  turbopack: {
    root: __dirname
  },
  images: {
    remotePatterns
  },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          ...securityHeaders,
          // HSTS is only meaningful (and only honoured) over HTTPS, and browsers ignore it on
          // plain-HTTP origins — but it is still gated on a production build so a developer who
          // fronts `next dev` with a local TLS proxy can never pin `localhost` to HTTPS for two
          // years. Vercel serves everything over HTTPS, so production always gets it.
          ...(isProduction
            ? [
                {
                  key: "Strict-Transport-Security",
                  value: "max-age=63072000; includeSubDomains; preload"
                }
              ]
            : [])
        ]
      }
    ];
  }
};

export default nextConfig;
