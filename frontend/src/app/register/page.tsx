"use client";
import { FormEvent, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { apiPath, storeAuthResponse } from "@/lib/api";

/** POST /auth/register — RegisterRequest { tenantName, tenantSlug, email, password } (roadmap 9-5). */
export default function RegisterPage() {
  const router = useRouter();
  const [tenantName, setTenantName] = useState("");
  const [tenantSlug, setTenantSlug] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function slugify(v: string): string {
    return v.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 50);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(apiPath("/auth/register"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tenantName, tenantSlug, email, password }),
      });
      if (!res.ok) {
        let msg = `Registration failed (${res.status}).`;
        try {
          const body = await res.json();
          if (body?.error) msg = String(body.error);
          else if (body?.message) msg = String(body.message);
        } catch {
          /* no body */
        }
        setError(msg);
        return;
      }
      storeAuthResponse(await res.json());
      router.replace("/dashboard");
    } catch {
      setError("Cannot reach the API.");
    } finally {
      setBusy(false);
    }
  }

  const input =
    "mt-1 w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500";

  return (
    <main className="min-h-screen bg-gray-50 flex items-center justify-center p-6">
      <form onSubmit={submit} className="bg-white rounded-xl border border-gray-200 p-8 w-full max-w-sm space-y-4">
        <div>
          <h1 className="text-xl font-bold text-indigo-600">Create a tenant</h1>
          <p className="text-sm text-gray-500 mt-1">The first user becomes the tenant admin.</p>
        </div>
        <label className="block text-sm">
          <span className="text-gray-700">Company / tenant name</span>
          <input
            required
            value={tenantName}
            onChange={(e) => {
              setTenantName(e.target.value);
              if (!tenantSlug || tenantSlug === slugify(tenantName)) setTenantSlug(slugify(e.target.value));
            }}
            className={input}
          />
        </label>
        <label className="block text-sm">
          <span className="text-gray-700">Slug (3–50 chars)</span>
          <input required minLength={3} maxLength={50} value={tenantSlug} onChange={(e) => setTenantSlug(e.target.value)} className={input} />
        </label>
        <label className="block text-sm">
          <span className="text-gray-700">E-mail</span>
          <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} className={input} />
        </label>
        <label className="block text-sm">
          <span className="text-gray-700">Password (min 8)</span>
          <input type="password" required minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} className={input} />
        </label>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={busy}
          className="w-full bg-indigo-600 text-white text-sm font-medium rounded-lg py-2 hover:bg-indigo-700 disabled:opacity-50"
        >
          {busy ? "Creating…" : "Register"}
        </button>
        <p className="text-xs text-gray-500 text-center">
          Already registered?{" "}
          <Link href="/login" className="text-indigo-600 hover:underline">
            Sign in
          </Link>
        </p>
      </form>
    </main>
  );
}
