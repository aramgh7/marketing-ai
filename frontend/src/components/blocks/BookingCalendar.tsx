"use client";
import { useEffect, useRef, useState } from "react";
import { Tracker } from "@/lib/tracker";

/** Only http(s) calendar embeds; a tenant/LLM-supplied `javascript:` URL must never become an iframe src. */
function safeEmbedUrl(url: string): string | null {
  try {
    const u = new URL(url);
    return u.protocol === "https:" || u.protocol === "http:" ? u.toString() : null;
  } catch {
    return null;
  }
}

interface BookingCalendarProps {
  calendarUrl: string;
  calendarId?: string;
  showTimezone?: boolean;
  onSlotSelected?: (slotAt: string, browserTz: string) => void;
}

export default function BookingCalendar({
  calendarUrl,
  showTimezone = true,
  onSlotSelected,
}: BookingCalendarProps) {
  const [selected, setSelected] = useState<string | null>(null);
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const calendarOpenFired = useRef(false);
  const embedUrl = safeEmbedUrl(calendarUrl);
  const embedOrigin = embedUrl ? new URL(embedUrl).origin : null;

  useEffect(() => {
    const handler = (e: MessageEvent) => {
      // Audit §3.4: accept slot messages only from the embedded calendar's origin
      if (!embedOrigin || e.origin !== embedOrigin) return;
      if (typeof e.data === "object" && e.data?.type === "slot_selected") {
        const slotAt = e.data.slot_at as string;
        setSelected(slotAt);
        onSlotSelected?.(slotAt, tz);
        const slotMs = Date.parse(slotAt);
        const bookingDistanceH = Number.isFinite(slotMs)
          ? Math.round(((slotMs - Date.now()) / 3_600_000) * 10) / 10
          : undefined;
        // Audit 4.10: go through the tracker (correct endpoint, schema, consent gate, session id)
        Tracker.track("SLOT_SELECTED", {
          slot_at: slotAt,
          browser_tz: tz,
          ...(bookingDistanceH !== undefined ? { booking_distance_h: bookingDistanceH } : {}),
        });
      }
    };
    window.addEventListener("message", handler);
    return () => window.removeEventListener("message", handler);
  }, [tz, onSlotSelected, embedOrigin]);

  if (!embedUrl) return null;

  return (
    <div className="booking-calendar-atom w-full">
      {showTimezone && (
        <p className="text-sm text-gray-500 mb-2">Your timezone: {tz}</p>
      )}
      <iframe
        src={embedUrl}
        className="w-full min-h-[600px] border-0 rounded-lg"
        title="Schedule a call"
        onLoad={() => {
          if (calendarOpenFired.current) return;
          calendarOpenFired.current = true;
          Tracker.track("CALENDAR_OPEN", { browser_tz: tz });
        }}
      />
      {selected && (
        <p className="text-green-600 text-sm mt-2">
          Slot selected: {new Date(selected).toLocaleString()}
        </p>
      )}
    </div>
  );
}
