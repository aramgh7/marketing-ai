import Link from "next/link";
import RegisterFormLoader from "./_components/RegisterFormLoader";

const FEATURES = [
  { emoji: "⚡", bg: "#eef2ff", title: "AI-Generated Creatives", desc: "Produce scroll-stopping ad copy and visuals in seconds — no designer required." },
  { emoji: "📊", bg: "#f5f3ff", title: "Real-Time Analytics",    desc: "Track ROAS, CPL, and conversions across every campaign in one live dashboard." },
  { emoji: "🎯", bg: "#eff6ff", title: "Automated A/B Testing",  desc: "Thompson Sampling auto-shifts budget to winning variants — no manual guessing." },
  { emoji: "🔒", bg: "#ecfdf5", title: "Enterprise Security",    desc: "Multi-tenant isolation, row-level security, and role-based access built in." },
];

export const metadata = {
  title: "Marketing AI — Smarter campaigns. Better results.",
  description: "AI-powered ad platform that turns your marketing data into measurable growth.",
};

const CSS = `
*{box-sizing:border-box}
.lp{min-height:100vh;background:#fff;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif}
.lp-nav{position:fixed;top:0;left:0;right:0;z-index:9999;background:#fff;border-bottom:1px solid #e5e7eb;box-shadow:0 1px 4px rgba(0,0,0,.08)}
.lp-nav-in{max-width:1152px;margin:0 auto;padding:0 24px;height:64px;display:flex;align-items:center;justify-content:space-between}
.lp-logo{display:flex;align-items:center;gap:10px;text-decoration:none}
.lp-mark{width:36px;height:36px;background:#4f46e5;border-radius:10px;display:flex;align-items:center;justify-content:center;color:#fff;font-weight:800;font-size:16px;flex-shrink:0}
.lp-name{font-size:17px;font-weight:700;color:#111827;letter-spacing:-.3px}
.lp-navr{display:flex;align-items:center;gap:8px}
.lp-signin{font-size:14px;font-weight:500;color:#6b7280;text-decoration:none;padding:8px 14px;border-radius:8px}
.lp-signin:hover{color:#111827;background:#f9fafb}
.lp-hero-btn{font-size:14px;font-weight:600;color:#fff;text-decoration:none;padding:9px 20px;border-radius:9px;background:#4f46e5}
.lp-hero-btn:hover{background:#4338ca}
.lp-hero{padding:112px 24px 80px;background:linear-gradient(140deg,#312e81 0%,#4338ca 45%,#6d28d9 100%)}
.lp-hero-in{max-width:1152px;margin:0 auto;display:grid;grid-template-columns:1fr 1fr;gap:56px;align-items:center}
.lp-badge{display:inline-flex;align-items:center;gap:8px;background:rgba(255,255,255,.15);border-radius:999px;padding:6px 16px;font-size:13px;font-weight:500;color:#e0e7ff;margin-bottom:24px}
.lp-dot{width:8px;height:8px;background:#34d399;border-radius:50%;display:inline-block;flex-shrink:0}
.lp-h1{font-size:clamp(1.9rem,3.5vw,3rem);font-weight:800;line-height:1.15;color:#fff;margin:0 0 20px;letter-spacing:-.5px}
.lp-acc{color:#a5b4fc}
.lp-sub{font-size:17px;line-height:1.7;color:#c7d2fe;max-width:440px;margin:0 0 40px}
.lp-stats{display:flex;gap:40px}
.lp-snum{font-size:2.2rem;font-weight:800;color:#fff;line-height:1}
.lp-slbl{font-size:13px;color:#a5b4fc;margin-top:4px}
.lp-card{background:#fff;border-radius:20px;box-shadow:0 20px 60px rgba(0,0,0,.25),0 4px 16px rgba(0,0,0,.12);overflow:hidden}
.lp-feats{padding:80px 24px;background:#f9fafb}
.lp-feats-in{max-width:1152px;margin:0 auto}
.lp-feats-hd{text-align:center;margin-bottom:52px}
.lp-feats-h2{font-size:2rem;font-weight:800;color:#111827;margin:0 0 12px}
.lp-feats-sub{font-size:16px;color:#6b7280;max-width:480px;margin:0 auto}
.lp-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:20px}
.lp-fc{background:#fff;border-radius:16px;border:1px solid #e5e7eb;padding:24px}
.lp-fi{width:48px;height:48px;border-radius:12px;display:flex;align-items:center;justify-content:center;font-size:22px;margin-bottom:16px}
.lp-ft{font-size:15px;font-weight:700;color:#111827;margin:0 0 8px}
.lp-fd{font-size:14px;color:#6b7280;line-height:1.6;margin:0}
.lp-cta{padding:80px 24px;text-align:center;background:linear-gradient(140deg,#312e81 0%,#4338ca 60%,#6d28d9 100%)}
.lp-cta-h{font-size:2rem;font-weight:800;color:#fff;margin:0 0 12px}
.lp-cta-s{font-size:16px;color:#c7d2fe;margin:0 0 32px}
.lp-cta-btn{display:inline-block;background:#fff;color:#4338ca;font-weight:700;font-size:16px;text-decoration:none;padding:14px 32px;border-radius:12px}
.lp-cta-btn:hover{background:#eef2ff}
.lp-foot{padding:28px 24px;border-top:1px solid #e5e7eb;background:#fff}
.lp-foot-in{max-width:1152px;margin:0 auto;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px;font-size:13px;color:#9ca3af}
.lp-flinks{display:flex;gap:20px}
.lp-flinks a{color:#9ca3af;text-decoration:none}
.lp-flinks a:hover{color:#6b7280}
@media(max-width:900px){.lp-hero-in{grid-template-columns:1fr}.lp-grid{grid-template-columns:1fr 1fr}}
@media(max-width:520px){.lp-grid{grid-template-columns:1fr}.lp-stats{gap:24px}}
`;

export default function LandingPage() {
  return (
    <>
      {/* Styles injected directly — React never removes this */}
      <style dangerouslySetInnerHTML={{ __html: CSS }} />

      <div className="lp">
        {/* Nav */}
        <header className="lp-nav">
          <div className="lp-nav-in">
            <Link href="/" className="lp-logo">
              <div className="lp-mark">M</div>
              <span className="lp-name">Marketing AI</span>
            </Link>
            <nav className="lp-navr">
              <Link href="/login" className="lp-signin">Sign in</Link>
              <Link href="#register" className="lp-hero-btn">Get started free</Link>
            </nav>
          </div>
        </header>

        {/* Hero */}
        <section className="lp-hero">
          <div className="lp-hero-in">
            <div>
              <div className="lp-badge">
                <span className="lp-dot"></span>
                AI-powered platform for growth teams
              </div>
              <h1 className="lp-h1">
                Smarter campaigns.<br />
                <span className="lp-acc">Better results.</span>
              </h1>
              <p className="lp-sub">
                The all-in-one platform that turns your ad data into measurable growth —
                automatically. Launch, test, and scale faster than ever.
              </p>
              <div className="lp-stats">
                <div><div className="lp-snum">3×</div><div className="lp-slbl">faster launch</div></div>
                <div><div className="lp-snum">40%</div><div className="lp-slbl">avg. ROAS improvement</div></div>
                <div><div className="lp-snum">24/7</div><div className="lp-slbl">auto-optimization</div></div>
              </div>
            </div>
            <div id="register" className="lp-card">
              <RegisterFormLoader />
            </div>
          </div>
        </section>

        {/* Features */}
        <section className="lp-feats">
          <div className="lp-feats-in">
            <div className="lp-feats-hd">
              <h2 className="lp-feats-h2">Everything you need to grow</h2>
              <p className="lp-feats-sub">From creative generation to budget optimization — one platform, zero guesswork.</p>
            </div>
            <div className="lp-grid">
              {FEATURES.map((f) => (
                <div key={f.title} className="lp-fc">
                  <div className="lp-fi" style={{ background: f.bg }}>{f.emoji}</div>
                  <p className="lp-ft">{f.title}</p>
                  <p className="lp-fd">{f.desc}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* CTA */}
        <section className="lp-cta">
          <h2 className="lp-cta-h">Ready to scale your campaigns?</h2>
          <p className="lp-cta-s">Join marketing teams already growing with Marketing AI.</p>
          <Link href="#register" className="lp-cta-btn">Get started free →</Link>
        </section>

        {/* Footer */}
        <footer className="lp-foot">
          <div className="lp-foot-in">
            <span>© 2026 Marketing AI. All rights reserved.</span>
            <div className="lp-flinks">
              <Link href="/privacy">Privacy Policy</Link>
              <Link href="/login">Sign in</Link>
            </div>
          </div>
        </footer>
      </div>
    </>
  );
}
