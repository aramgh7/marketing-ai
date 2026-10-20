"use client";
import { useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { hasSession } from "@/lib/api";

/**
 * Roadmap 9-5: admin routes require a stored access token. Without one the visitor is sent to
 * /login?next=<path>. The server still validates the token on every call; a final 401 in
 * `apiFetch` clears the storage and redirects as well.
 */
export default function AuthGuard({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (hasSession()) {
      setReady(true);
    } else {
      const next = encodeURIComponent(pathname || "/dashboard");
      router.replace(`/login?next=${next}`);
    }
  }, [pathname, router]);

  if (!ready) {
    return <p className="text-sm text-gray-400 p-8">Checking session…</p>;
  }
  return <>{children}</>;
}
