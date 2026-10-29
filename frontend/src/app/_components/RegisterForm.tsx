"use client";
import { FormEvent, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { apiPath, hasSession, storeAuthResponse } from "@/lib/api";

function slugify(v: string) {
  return v.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 50);
}

const CSS = `
.rf{padding:32px;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;box-sizing:border-box}
.rf *{box-sizing:border-box}
.rf-hd{margin-bottom:24px}
.rf-h2{font-size:20px;font-weight:700;color:#111827;margin:0 0 4px;line-height:1.3}
.rf-sub{font-size:13px;color:#6b7280;margin:0}
.rf-form{display:flex;flex-direction:column;gap:14px}
.rf-field{display:flex;flex-direction:column;gap:5px}
.rf-label{font-size:13px;font-weight:500;color:#374151;display:block}
.rf-inp{width:100%;border:1.5px solid #d1d5db;border-radius:8px;padding:10px 12px;font-size:14px;color:#111827;background:#fff;outline:none}
.rf-inp:focus{border-color:#6366f1;box-shadow:0 0 0 3px rgba(99,102,241,.15)}
.rf-hint{font-size:11px;color:#9ca3af;margin:2px 0 0}
.rf-err{font-size:13px;color:#dc2626;background:#fef2f2;border-radius:8px;padding:10px 12px;margin:0}
.rf-btn{width:100%;background:#4f46e5;color:#fff;font-weight:600;font-size:15px;border:none;border-radius:9px;padding:12px 20px;cursor:pointer;letter-spacing:-.1px}
.rf-btn:hover{background:#4338ca}
.rf-btn:disabled{opacity:.5;cursor:not-allowed}
.rf-foot{font-size:12px;color:#9ca3af;text-align:center;margin:4px 0 0}
.rf-foot a{color:#4f46e5;text-decoration:none;font-weight:500}
.rf-foot a:hover{text-decoration:underline}
.rf-ok{padding:40px 32px;text-align:center;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
.rf-ok-icon{width:56px;height:56px;background:#d1fae5;border-radius:50%;display:flex;align-items:center;justify-content:center;margin:0 auto 16px}
.rf-ok-h{font-size:17px;font-weight:700;color:#111827;margin:0 0 6px}
.rf-ok-s{font-size:13px;color:#6b7280;margin:0}
`;

export default function RegisterForm() {
  const router = useRouter();
  const [tenantName, setTenantName] = useState("");
  const [tenantSlug, setTenantSlug] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  useEffect(() => {
    if (hasSession()) router.replace("/dashboard");
  }, [router]);

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
          msg = body?.detail ?? body?.error ?? body?.message ?? msg;
        } catch { /* no body */ }
        setError(msg);
        return;
      }
      storeAuthResponse(await res.json());
      setDone(true);
      setTimeout(() => router.replace("/dashboard"), 1200);
    } catch {
      setError("Cannot reach the server. Please try again.");
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <>
        <style dangerouslySetInnerHTML={{ __html: CSS }} />
        <div className="rf-ok">
          <div className="rf-ok-icon">
            <svg width="28" height="28" fill="none" stroke="#059669" strokeWidth="2.5" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
            </svg>
          </div>
          <h3 className="rf-ok-h">Account created!</h3>
          <p className="rf-ok-s">Redirecting to your dashboard…</p>
        </div>
      </>
    );
  }

  return (
    <>
      <style dangerouslySetInnerHTML={{ __html: CSS }} />
      <div className="rf">
        <div className="rf-hd">
          <h2 className="rf-h2">Create your free account</h2>
          <p className="rf-sub">No credit card required. Start in under 2 minutes.</p>
        </div>
        <form onSubmit={submit} className="rf-form">
          <div className="rf-field">
            <label className="rf-label">Company name</label>
            <input
              required
              placeholder="Acme Corp"
              value={tenantName}
              onChange={(e) => {
                setTenantName(e.target.value);
                if (!tenantSlug || tenantSlug === slugify(tenantName))
                  setTenantSlug(slugify(e.target.value));
              }}
              className="rf-inp"
            />
          </div>
          <div className="rf-field">
            <label className="rf-label">Account slug</label>
            <input
              required
              minLength={3}
              maxLength={50}
              placeholder="acme-corp"
              value={tenantSlug}
              onChange={(e) => setTenantSlug(e.target.value)}
              className="rf-inp"
            />
            <p className="rf-hint">Used in your landing page URLs</p>
          </div>
          <div className="rf-field">
            <label className="rf-label">Work email</label>
            <input
              type="email"
              required
              placeholder="you@company.com"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="rf-inp"
            />
          </div>
          <div className="rf-field">
            <label className="rf-label">Password</label>
            <input
              type="password"
              required
              minLength={8}
              placeholder="Min. 8 characters"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="rf-inp"
            />
          </div>
          {error && <p className="rf-err">{error}</p>}
          <button type="submit" disabled={busy} className="rf-btn">
            {busy ? "Creating account…" : "Start for free →"}
          </button>
          <p className="rf-foot">
            Already have an account?{" "}
            <Link href="/login">Sign in</Link>
          </p>
        </form>
      </div>
    </>
  );
}
