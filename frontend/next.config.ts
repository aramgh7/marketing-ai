import type { NextConfig } from "next";

/**
 * Audit F-14 (minimum): security headers.
 *  - admin / auth pages: never framed (X-Frame-Options DENY + CSP frame-ancestors 'none')
 *  - landings (/l/*): frame-ancestors 'self' (a landing may embed a booking calendar, nobody embeds us)
 *  - HSTS only when the deployment terminates TLS (ENABLE_HSTS=1), otherwise browsers would pin http hosts
 *  - Referrer-Policy strict-origin-when-cross-origin everywhere
 */
const hsts = process.env.ENABLE_HSTS === "1";

const common = [
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  ...(hsts ? [{ key: "Strict-Transport-Security", value: "max-age=31536000; includeSubDomains" }] : []),
];

const nextConfig: NextConfig = {
  output: "standalone",
  experimental: {
    serverActions: { allowedOrigins: ["localhost:3000"] },
  },
  async headers() {
    return [
      {
        // landings: may be framed only by the same origin
        source: "/l/:path*",
        headers: [
          ...common,
          { key: "X-Frame-Options", value: "SAMEORIGIN" },
          { key: "Content-Security-Policy", value: "frame-ancestors 'self'" },
        ],
      },
      {
        // everything else (admin route group, login, register, privacy, API routes): never framed
        source: "/((?!l/).*)",
        headers: [
          ...common,
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Content-Security-Policy", value: "frame-ancestors 'none'" },
        ],
      },
    ];
  },
};

export default nextConfig;
