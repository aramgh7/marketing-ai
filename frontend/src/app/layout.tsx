import type { Metadata } from "next";
import Script from "next/script";
import { globalCss } from "@/_generated/global-css";
import CookieBanner from "@/components/CookieBanner";

export const metadata: Metadata = {
  title: "Marketing AI Platform",
  description: "AI-powered ad optimization",
};

const cookiebotDomainId = process.env.NEXT_PUBLIC_COOKIEBOT_DOMAIN_ID;

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <head>
        {/*
         * Cookiebot CMP (§12.1a).
         * Inject only when NEXT_PUBLIC_COOKIEBOT_DOMAIN_ID is configured.
         * blocking-mode="auto" pauses other scripts until consent is given.
         * When absent, our custom <CookieBanner> renders instead.
         */}
        {cookiebotDomainId && (
          <Script
            id="Cookiebot"
            src="https://consent.cookiebot.com/uc.js"
            data-cbid={cookiebotDomainId}
            data-blockingmode="auto"
            strategy="beforeInteractive"
          />
        )}
      </head>
      <body>
        {globalCss && <style dangerouslySetInnerHTML={{ __html: globalCss }} />}
        {children}
        {/* Fallback banner shown when Cookiebot is not configured */}
        <CookieBanner />
      </body>
    </html>
  );
}
