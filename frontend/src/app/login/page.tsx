"use client";
import { FormEvent, Suspense, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { apiPath, storeAuthResponse } from "@/lib/api";

/** Roadmap 9-5: the admin had no login page — the JWT could only be placed in localStorage by hand. */
function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const next = params?.get("next") || "/dashboard";

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(apiPath("/auth/login"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      });
      if (res.status === 401 || res.status === 403) {
        setError("Wrong e-mail or password.");
        return;
      }
      if (!res.ok) {
        setError(`Login failed (${res.status}).`);
        return;
      }
      const data = await res.json();
      storeAuthResponse(data);
      router.replace(next.startsWith("/") ? next : "/dashboard");
    } catch {
      setError("Cannot reach the API. Check NEXT_PUBLIC_API_URL / the platform-api service.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="bg-white rounded-xl border border-gray-200 p-8 w-full max-w-sm space-y-5">
      <div>
        <h1 className="text-xl font-bold text-indigo-600">Marketing AI</h1>
        <p className="text-sm text-gray-500 mt-1">Sign in to the admin panel</p>
      </div>
      <label className="block text-sm">
        <span className="text-gray-700">E-mail</span>
        <input
          type="email"
          required
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
      </label>
      <label className="block text-sm">
        <span className="text-gray-700">Password</span>
        <input
          type="password"
          required
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
        />
      </label>
      {error && <p className="text-sm text-red-600">{error}</p>}
      <button
        type="submit"
        disabled={busy}
        className="w-full bg-indigo-600 text-white text-sm font-medium rounded-lg py-2 hover:bg-indigo-700 disabled:opacity-50"
      >
        {busy ? "Signing in…" : "Sign in"}
      </button>
      <p className="text-xs text-gray-500 text-center">
        No account yet?{" "}
        <Link href="/register" className="text-indigo-600 hover:underline">
          Register a tenant
        </Link>
      </p>
    </form>
  );
}

export default function LoginPage() {
  return (
    <main className="min-h-screen bg-gray-50 flex items-center justify-center p-6">
      <Suspense fallback={<p className="text-sm text-gray-400">Loading…</p>}>
        <LoginForm />
      </Suspense>
    </main>
  );
}
