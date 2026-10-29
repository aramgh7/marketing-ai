"use client";
import { FormEvent, Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { apiPath, hasSession, storeAuthResponse } from "@/lib/api";

function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const rawNext = params?.get("next") || "";
  const next = /^\/(?![\/\\])/.test(rawNext) ? rawNext : "/dashboard";

  useEffect(() => {
    if (hasSession()) router.replace(next);
  }, [next, router]);

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
        setError("Wrong email or password.");
        return;
      }
      if (!res.ok) {
        setError(`Login failed (${res.status}).`);
        return;
      }
      const data = await res.json();
      storeAuthResponse(data);
      router.replace(next);
    } catch {
      setError("Cannot reach the server. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-5 w-full">
      <label className="block text-sm">
        <span className="text-gray-700 font-medium">Email address</span>
        <input
          type="email"
          required
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
          placeholder="you@company.com"
        />
      </label>
      <label className="block text-sm">
        <span className="text-gray-700 font-medium">Password</span>
        <input
          type="password"
          required
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="mt-1 w-full border border-gray-300 rounded-lg px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
          placeholder="••••••••"
        />
      </label>
      {error && (
        <p className="text-sm text-red-600 bg-red-50 rounded-lg px-3 py-2">{error}</p>
      )}
      <button
        type="submit"
        disabled={busy}
        className="w-full bg-indigo-600 text-white text-sm font-semibold rounded-lg py-2.5 hover:bg-indigo-700 active:bg-indigo-800 disabled:opacity-50 transition-colors"
      >
        {busy ? "Signing in…" : "Sign in →"}
      </button>
      <p className="text-xs text-gray-500 text-center pt-1">
        New to the platform?{" "}
        <Link href="/register" className="text-indigo-600 hover:underline font-medium">
          Create an account
        </Link>
      </p>
    </form>
  );
}

const FEATURES = [
  { icon: "⚡", text: "AI-generated ad creatives in seconds" },
  { icon: "📊", text: "Real-time campaign analytics & optimization" },
  { icon: "🎯", text: "Automated A/B testing with Thompson sampling" },
  { icon: "🔒", text: "Enterprise-grade security & multi-tenant isolation" },
];

export default function LoginPage() {
  return (
    <div className="min-h-screen flex">
      {/* Left — branding panel */}
      <div className="hidden lg:flex lg:w-1/2 bg-gradient-to-br from-indigo-700 via-indigo-800 to-violet-900 text-white flex-col justify-between p-12">
        <div>
          <div className="flex items-center gap-2 mb-16">
            <div className="w-8 h-8 bg-white/20 rounded-lg flex items-center justify-center text-sm font-bold">M</div>
            <span className="text-lg font-bold tracking-tight">Marketing AI</span>
          </div>
          <h1 className="text-4xl font-bold leading-tight mb-4">
            Smarter campaigns.<br />Better results.
          </h1>
          <p className="text-indigo-200 text-lg mb-10">
            The AI-powered platform that turns your marketing data into measurable growth — automatically.
          </p>
          <ul className="space-y-4">
            {FEATURES.map((f) => (
              <li key={f.text} className="flex items-start gap-3">
                <span className="text-xl">{f.icon}</span>
                <span className="text-indigo-100 text-sm leading-relaxed">{f.text}</span>
              </li>
            ))}
          </ul>
        </div>
        <p className="text-indigo-400 text-xs">
          © {new Date().getFullYear()} Marketing AI. All rights reserved.
        </p>
      </div>

      {/* Right — login form */}
      <div className="w-full lg:w-1/2 flex items-center justify-center p-8 bg-gray-50">
        <div className="w-full max-w-sm">
          {/* Mobile logo */}
          <div className="flex items-center gap-2 mb-8 lg:hidden">
            <div className="w-8 h-8 bg-indigo-600 rounded-lg flex items-center justify-center text-white text-sm font-bold">M</div>
            <span className="text-lg font-bold text-gray-900">Marketing AI</span>
          </div>

          <div className="mb-8">
            <h2 className="text-2xl font-bold text-gray-900">Welcome back</h2>
            <p className="text-gray-500 text-sm mt-1">Sign in to your account to continue</p>
          </div>

          <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-8">
            <Suspense fallback={<p className="text-sm text-gray-400">Loading…</p>}>
              <LoginForm />
            </Suspense>
          </div>

          <p className="text-center text-xs text-gray-400 mt-6">
            By signing in you agree to our{" "}
            <Link href="/privacy" className="hover:underline">Privacy Policy</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
