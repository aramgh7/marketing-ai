const map: Record<string, string> = {
  ACTIVE:         "bg-green-100 text-green-800",
  APPROVED:       "bg-blue-100 text-blue-800",
  PENDING_REVIEW: "bg-yellow-100 text-yellow-800",
  REVIEW:         "bg-yellow-100 text-yellow-800",
  DRAFT:          "bg-gray-100 text-gray-700",
  PAUSED:         "bg-orange-100 text-orange-800",
  REJECTED:       "bg-red-100 text-red-800",
  COMPLETED:      "bg-purple-100 text-purple-800",
  ARCHIVED:       "bg-gray-100 text-gray-500",
};

export default function StatusBadge({ status }: { status: string }) {
  return (
    <span
      className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${
        map[status] ?? "bg-gray-100 text-gray-600"
      }`}
    >
      {status}
    </span>
  );
}
