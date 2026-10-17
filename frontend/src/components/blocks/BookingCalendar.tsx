"use client";
import { useEffect, useRef, useState } from "react";

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

  useEffect(() => {
    const handler = (e: MessageEvent) => {
      if (typeof e.data === "object" && e.data?.type === "slot_selected") {
        const slotAt = e.data.slot_at as string;
        setSelected(slotAt);
        onSlotSelected?.(slotAt, tz);
        fetch("/api/v1/events", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            event_type: "SLOT_SELECTED",
            payload: { slot_at: slotAt, browser_tz: tz },
          }),
        }).catch(() => {});
      }
    };
    window.addEventListener("message", handler);
    return () => window.removeEventListener("message", handler);
  }, [tz, onSlotSelected]);

  return (
    <div className="booking-calendar-atom w-full">
      {showTimezone && (
        <p className="text-sm text-gray-500 mb-2">Your timezone: {tz}</p>
      )}
      <iframe
        src={calendarUrl}
        className="w-full min-h-[600px] border-0 rounded-lg"
        title="Schedule a call"
        onLoad={() => {
          if (calendarOpenFired.current) return;
          calendarOpenFired.current = true;
          fetch("/api/v1/events", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              event_type: "CALENDAR_OPEN",
              payload: { browser_tz: tz },
            }),
          }).catch(() => {});
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
