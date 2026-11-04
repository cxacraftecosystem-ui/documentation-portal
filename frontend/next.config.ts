import type { NextConfig } from "next";

const isProduction = process.env.NODE_ENV === "production";

const remotePatterns: NonNullable<NextConfig["images"]>["remotePatterns"] = [
  { protocol: "https", hostname: "**.cloudfront.net", pathname: "/**" },
  {
    protocol: "https",
    hostname: "fieldrepo-media-626159998512.s3.dualstack.ap-south-1.amazonaws.com",
    pathname: "/**"
  },
  {
    protocol: "https",
    hostname: "fieldrepo-media-626159998512.s3.ap-south-1.amazonaws.com",
    pathname: "/**"
  },
  { protocol: "http", hostname: "localhost", port: "9000", pathname: "/**" },
  { protocol: "http", hostname: "127.0.0.1", port: "9000", pathname: "/**" }
];

const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Content-Security-Policy", value: "frame-ancestors 'none'" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
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
  env: {
    NEXT_PUBLIC_BUILD_NUMBER: process.env.NEXT_PUBLIC_BUILD_NUMBER || process.env.GITHUB_RUN_NUMBER || ""
  },
  experimental: {
    proxyClientMaxBodySize: "64mb",
    proxyTimeout: 300_000
  },
  turbopack: {
    root: __dirname
  },
  images: {
    remotePatterns
  },
  async redirects() {
    return [{ source: "/settings/workshop-access", destination: "/workshop-access", permanent: false }];
  },
  async rewrites() {
    return [{ source: "/.well-known/assetlinks.json", destination: "/asset-links" }];
  },
  async headers() {
    return [
      {
        source: "/.well-known/assetlinks.json",
        headers: [{ key: "Content-Type", value: "application/json" }]
      },
      {
        source: "/:path*",
        headers: [
          ...securityHeaders,
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
