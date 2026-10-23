"use client";
/**
 * Fallback consent banner (§12.1a).
 *
 * Shown when Cookiebot is NOT configured (NEXT_PUBLIC_COOKIEBOT_DOMAIN_ID absent).
 * When Cookiebot IS configured, this component renders nothing — Cookiebot's
 * auto-generated banner takes over via its script.
 *
 * Three choices map directly to our consent levels:
 *   "Accept All"     → "full"           (behavioral + marketing tracking)
 *   "Necessary Only" → "none"           (audit 7.8: no analytics — PAGE_VIEW/session/UTMs are not "necessary")
 *   "Decline"        → "none"           (nothing tracked)
 */

import { useState, useEffect } from "react";
import { hasConsentDecision, setConsentLevel, syncCookiebotConsentCookie } from "@/lib/consent";

export default function CookieBanner() {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    // Cookiebot is configured — its own banner handles consent; we only mirror its decision into
    // the `_consent` cookie the edge middleware reads (audit F-6).
    if (process.env.NEXT_PUBLIC_COOKIEBOT_DOMAIN_ID) {
      syncCookiebotConsentCookie();
      return;
    }
    if (!hasConsentDecision()) setVisible(true);
  }, []);

  if (!visible) return null;

  const accept = () => { setConsentLevel("full");           setVisible(false); };
  const necessary = () => { setConsentLevel("none");           setVisible(false); };
  const decline = () => { setConsentLevel("none");           setVisible(false); };

  return (
    <div className="fixed bottom-0 left-0 right-0 z-50 bg-gray-900 border-t border-gray-700
                    px-6 py-4 flex flex-wrap items-center gap-4 shadow-lg">
      <p className="flex-1 min-w-[200px] text-sm text-gray-300">
        We use cookies to analyze site traffic and optimize your experience.
        <a
          href="/privacy"
          className="ml-1 underline text-indigo-400 hover:text-indigo-300"
        >
          Privacy Policy
        </a>
      </p>
      <div className="flex gap-2 flex-wrap">
        <button
          onClick={decline}
          className="px-4 py-2 text-sm border border-gray-500 rounded text-gray-300
                     hover:bg-gray-800 transition-colors"
        >
          Decline
        </button>
        <button
          onClick={necessary}
          className="px-4 py-2 text-sm border border-indigo-500 rounded text-indigo-300
                     hover:bg-indigo-900/30 transition-colors"
        >
          Necessary Only
        </button>
        <button
          onClick={accept}
          className="px-4 py-2 text-sm bg-indigo-600 hover:bg-indigo-500 rounded
                     text-white transition-colors"
        >
          Accept All
        </button>
      </div>
    </div>
  );
}
