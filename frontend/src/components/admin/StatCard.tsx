interface StatCardProps {
  label: string;
  value: string | number;
  sub?: string;
  /** alias used by the Month-1 page */
  subLabel?: string;
  color?: "green" | "red" | "yellow" | "blue" | "gray";
}

const colorMap: Record<string, string> = {
  green:  "text-green-600 bg-green-50",
  red:    "text-red-600 bg-red-50",
  yellow: "text-yellow-600 bg-yellow-50",
  blue:   "text-indigo-600 bg-indigo-50",
  gray:   "text-gray-600 bg-gray-50",
};

export default function StatCard({
  label,
  value,
  sub,
  subLabel,
  color = "blue",
}: StatCardProps) {
  const subText = sub ?? subLabel;
  return (
    <div className="bg-white rounded-xl border border-gray-200 p-5 flex flex-col gap-1">
      <span className="text-xs text-gray-500 uppercase tracking-wide">
        {label}
      </span>
      <span
        className={`text-2xl font-bold px-2 py-0.5 rounded self-start ${colorMap[color]}`}
      >
        {value}
      </span>
      {subText && <span className="text-xs text-gray-400">{subText}</span>}
    </div>
  );
}
