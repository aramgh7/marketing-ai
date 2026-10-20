"use client";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { currentUser, logout } from "@/lib/api";

const nav = [
  { href: "/dashboard",  label: "Dashboard",   icon: "📊" },
  { href: "/creatives",  label: "Creatives",   icon: "🎨" },
  { href: "/ab-results", label: "A/B Results", icon: "🧪" },
  { href: "/quality",    label: "Quality",     icon: "🎯" },
  { href: "/briefs",     label: "Briefs",      icon: "📝" },
  { href: "/funnel",     label: "Funnel",      icon: "🔻" },
  { href: "/month1",     label: "Month 1",     icon: "📅" },
  { href: "/pixels",     label: "Pixels",      icon: "📡" },
  { href: "/compliance", label: "Compliance",  icon: "⚖️" },
  { href: "/ghl",        label: "GHL",         icon: "🔗" },
  { href: "/autonomy",   label: "Autonomy",    icon: "🤖" },
  { href: "/settings",   label: "Settings",    icon: "⚙️" },
];

export default function Sidebar() {
  const pathname = usePathname();
  const router = useRouter();
  const [email, setEmail] = useState<string | null>(null);

  useEffect(() => {
    setEmail(currentUser().email);
  }, []);

  async function handleLogout() {
    await logout();
    router.replace("/login");
  }

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
      <div className="mt-auto px-6 pt-6 border-t border-gray-100 space-y-2">
        {email && <p className="text-xs text-gray-400 truncate" title={email}>{email}</p>}
        <button
          type="button"
          onClick={handleLogout}
          className="text-sm text-gray-600 hover:text-red-600"
        >
          ⎋ Logout
        </button>
      </div>
    </aside>
  );
}
