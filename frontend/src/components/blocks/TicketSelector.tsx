"use client";

const TICKET_BUCKETS = [
  { value: "LT25K",    label: "Under $25,000" },
  { value: "25_50K",   label: "$25,000 – $50,000" },
  { value: "50_100K",  label: "$50,000 – $100,000" },
  { value: "100_250K", label: "$100,000 – $250,000" },
  { value: "GT250K",   label: "$250,000+" },
];

interface TicketSelectorProps {
  label?: string;
  required?: boolean;
  onChange?: (bucket: string) => void;
}

export default function TicketSelector({
  label = "Intended investment amount",
  required = false,
  onChange,
}: TicketSelectorProps) {
  return (
    <div className="ticket-selector-atom my-4">
      <label className="block text-sm font-medium text-gray-700 mb-1">
        {label}
        {required && <span className="text-red-500 ml-1">*</span>}
      </label>
      <select
        name="ticket_bucket"
        required={required}
        defaultValue=""
        className="w-full rounded-md border border-gray-300 shadow-sm focus:border-blue-500 focus:ring-blue-500 sm:text-sm p-2"
        onChange={(e) => onChange?.(e.target.value)}
      >
        <option value="" disabled>Select a range...</option>
        {TICKET_BUCKETS.map((b) => (
          <option key={b.value} value={b.value}>
            {b.label}
          </option>
        ))}
      </select>
    </div>
  );
}
