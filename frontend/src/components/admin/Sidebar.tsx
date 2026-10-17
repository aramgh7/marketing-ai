"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";

const nav = [
  { href: "/dashboard",  label: "Dashboard",  icon: "📊" },
  { href: "/creatives",  label: "Creatives",  icon: "🎨" },
  { href: "/ab-results", label: "A/B Results", icon: "🧪" },
  { href: "/briefs",     label: "Briefs",      icon: "📝" },
  { href: "/settings",   label: "Settings",    icon: "⚙️" },
];

export default function Sidebar() {
  const pathname = usePathname();
  return (
    <aside className="w-56 bg-white border-r border-gray-200 flex flex-col py-6 gap-1">
      <div className="px-6 mb-6">
        <span className="text-lg font-bold text-indigo-600">Marketing AI</span>
      </div>
      {nav.map((item) => {
        const active = pathname?.startsWith(item.href);
        return (
          <Link
            key={item.href}
            href={item.href}
            className={`flex items-center gap-3 px-6 py-2 text-sm font-medium rounded-r-lg transition-colors ${
              active
                ? "bg-indigo-50 text-indigo-700 border-l-2 border-indigo-600"
                : "text-gray-600 hover:bg-gray-50"
            }`}
          >
            <span>{item.icon}</span>
            {item.label}
          </Link>
        );
      })}
    </aside>
  );
}
