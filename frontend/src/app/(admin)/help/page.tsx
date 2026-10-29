export const metadata = { title: "Help — Marketing AI" };

const TOC = [
  { id: "getting-started",  label: "Getting Started" },
  { id: "dashboard",        label: "Dashboard" },
  { id: "creatives",        label: "Creatives & Atoms" },
  { id: "media-library",    label: "Media Library" },
  { id: "templates",        label: "Templates" },
  { id: "landings",         label: "Landing Pages" },
  { id: "meta-ads",         label: "Meta Ads" },
  { id: "campaigns",        label: "Campaigns" },
  { id: "ab-results",       label: "A/B Results" },
  { id: "quality",          label: "Quality Score" },
  { id: "briefs",           label: "Briefs" },
  { id: "pixels",           label: "Pixels & CAPI" },
  { id: "product-facts",    label: "Product Facts" },
  { id: "video",            label: "Video" },
  { id: "autonomy",         label: "Autonomy" },
  { id: "settings",         label: "Settings" },
  { id: "other-tools",      label: "Other Tools" },
];

function Section({ id, title, children }: { id: string; title: string; children: React.ReactNode }) {
  return (
    <section id={id} className="scroll-mt-8 mb-16">
      <h2 className="text-xl font-bold text-gray-900 mb-4 pb-2 border-b-2 border-indigo-100">{title}</h2>
      <div className="space-y-4 text-sm text-gray-700 leading-relaxed">{children}</div>
    </section>
  );
}

function SubSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mt-6">
      <h3 className="font-semibold text-gray-900 mb-3 text-base">{title}</h3>
      <div className="space-y-2">{children}</div>
    </div>
  );
}

function Step({ n, children }: { n: number; children: React.ReactNode }) {
  return (
    <div className="flex gap-3 items-start">
      <span className="mt-0.5 flex-shrink-0 w-6 h-6 bg-indigo-600 text-white text-xs font-bold rounded-full flex items-center justify-center">{n}</span>
      <p className="flex-1">{children}</p>
    </div>
  );
}

function Tip({ children }: { children: React.ReactNode }) {
  return (
    <div className="bg-amber-50 border border-amber-200 rounded-lg px-4 py-3 text-amber-800 text-sm">
      <span className="font-semibold">💡 Tip: </span>{children}
    </div>
  );
}

function Note({ children }: { children: React.ReactNode }) {
  return (
    <div className="bg-blue-50 border border-blue-200 rounded-lg px-4 py-3 text-blue-800 text-sm">
      <span className="font-semibold">ℹ️ Note: </span>{children}
    </div>
  );
}

function Warning({ children }: { children: React.ReactNode }) {
  return (
    <div className="bg-red-50 border border-red-200 rounded-lg px-4 py-3 text-red-800 text-sm">
      <span className="font-semibold">⚠️ Important: </span>{children}
    </div>
  );
}

function Concept({ term, children }: { term: string; children: React.ReactNode }) {
  return (
    <div className="bg-gray-50 rounded-lg px-4 py-3 border border-gray-200">
      <span className="font-semibold text-indigo-700">{term} — </span>
      <span className="text-gray-700">{children}</span>
    </div>
  );
}

function Badge({ color, children }: { color: string; children: React.ReactNode }) {
  return (
    <span className={`inline-block px-2 py-0.5 rounded text-xs font-medium ${color}`}>{children}</span>
  );
}

export default function HelpPage() {
  return (
    <div className="max-w-4xl mx-auto">
      <div className="mb-10">
        <h1 className="text-3xl font-bold text-gray-900 mb-2">Help Center</h1>
        <p className="text-gray-500 text-base">
          Complete guide to every feature of the Marketing AI platform — how it works, why it works that way, and how to get the most out of it.
        </p>
      </div>

      <div className="flex gap-10">
        {/* Sticky TOC */}
        <nav className="hidden lg:block w-52 flex-shrink-0">
          <div className="sticky top-6 bg-gray-50 rounded-xl p-4 border border-gray-200">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">Sections</p>
            <ul className="space-y-0.5">
              {TOC.map((t) => (
                <li key={t.id}>
                  <a href={`#${t.id}`} className="block text-sm text-gray-600 hover:text-indigo-600 py-0.5 transition-colors">
                    {t.label}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        </nav>

        <div className="flex-1 min-w-0">

          {/* ── Getting Started ──────────────────────────────────────────── */}
          <Section id="getting-started" title="🚀 Getting Started">
            <p>
              Marketing AI is an end-to-end platform that closes the loop between creative production and ad performance.
              It tracks every visitor on your landing pages, runs Bayesian A/B tests automatically, extracts what works in your creatives,
              and — depending on your autonomy level — can manage your Meta campaigns on its own.
            </p>
            <p>
              The platform is built around a single insight: <strong>the only way to know what converts is to test it on real traffic with a real statistical model</strong>.
              Every feature feeds data back into that model — creative atoms, landing variants, quality scores, GHL CRM events, and CAPI conversions all flow into Thompson Sampling posteriors that determine which variant gets more traffic tomorrow.
            </p>

            <SubSection title="Setup checklist (do these in order)">
              <Step n={1}><strong>Settings → Meta integration</strong>: enter your Facebook Page ID and Instagram Actor ID. Without these, you cannot publish any ads.</Step>
              <Step n={2}><strong>Pixels</strong>: add at least one Meta Pixel with a Conversions API (CAPI) token. This enables server-side conversion tracking — more reliable than browser pixels alone because it bypasses ad blockers and iOS restrictions.</Step>
              <Step n={3}><strong>Product Facts</strong>: enter the core claims about your product (yield improvements, guarantees, proof points). The compliance engine validates every generated landing against these before allowing approval.</Step>
              <Step n={4}><strong>Briefs</strong>: create your first creative brief. It tells the AI your target audience, tone, key message, and offer structure.</Step>
              <Step n={5}><strong>Media Library</strong>: upload your images and videos. The AI will auto-tag them — you confirm or correct the tags.</Step>
              <Step n={6}><strong>Templates</strong>: pick one of the 5 landing templates that best fits your funnel stage. Fork and customise it if needed.</Step>
              <Step n={7}><strong>Landing Pages</strong>: generate 3–5 landing variants from your brief. Review, approve, and let Thompson Sampling route traffic between them.</Step>
              <Step n={8}><strong>Campaigns</strong>: create a TEST campaign and launch your first ad batch. Monitor gate status and promote winners to SCALE.</Step>
            </SubSection>

            <Tip>Go through settings before touching anything else. Many features will silently fail or show degraded data without Meta credentials and at least one pixel.</Tip>
          </Section>

          {/* ── Dashboard ────────────────────────────────────────────────── */}
          <Section id="dashboard" title="📊 Dashboard">
            <p>
              The Dashboard gives you a real-time summary of all your Meta campaigns and the AI&apos;s current read on performance.
              It is intentionally a <strong>summary view</strong> — deep-dive analysis lives in A/B Results, Quality Score, and the Funnel sections.
            </p>

            <SubSection title="Stat tiles">
              <p><strong>Total Campaigns</strong> — all campaigns across TEST and SCALE contours, regardless of status.</p>
              <p><strong>Active</strong> — campaigns currently running and spending budget. The AI is actively routing traffic and updating posteriors.</p>
              <p><strong>Paused</strong> — campaigns on hold. Posteriors are frozen; no new data flows in.</p>
              <p><strong>Daily Budget</strong> — sum of all active campaigns&apos; daily budgets. Useful to monitor against your monthly ceiling.</p>
            </SubSection>

            <SubSection title="Campaigns table">
              <p>Shows all campaigns with name, status, objective, budget, and creation date. Campaigns are created and managed in the <strong>Campaigns</strong> section — the dashboard table is read-only.</p>
            </SubSection>

            <SubSection title="Campaign statuses">
              <p><Badge color="bg-green-100 text-green-800">ACTIVE</Badge> — running, spending budget, posteriors updating.</p>
              <p><Badge color="bg-yellow-100 text-yellow-800">PAUSED</Badge> — no spend, posteriors frozen. Use this between test batches.</p>
              <p><Badge color="bg-gray-100 text-gray-800">DRAFT</Badge> — created locally, not yet pushed to Meta.</p>
              <p><Badge color="bg-red-100 text-red-800">KILLED</Badge> — stopped by the circuit breaker (e.g. CPA too high, or a gate failed). Requires manual review before reactivating.</p>
            </SubSection>

            <Note>The dashboard shows Meta campaign data synced every 30 minutes. If you just launched a campaign, refresh in 30 minutes to see updated spend and impression counts.</Note>
          </Section>

          {/* ── Creatives & Atoms ────────────────────────────────────────── */}
          <Section id="creatives" title="🎨 Creatives & Atoms">
            <p>
              A <strong>creative</strong> is any image or video you upload to the platform — the raw material for your Meta ads.
              After upload, the platform&apos;s AI analysis pipeline extracts a structured <strong>Creative DNA</strong>: a set of atoms that describe <em>why</em> a creative performs, not just <em>that</em> it performs.
            </p>

            <SubSection title="The atom model — Creative DNA">
              <p>Every creative is broken down into typed atoms. These are the building blocks the Bayesian GLM uses to learn which attributes drive conversions:</p>
              <div className="grid grid-cols-2 gap-2 mt-2">
                <Concept term="HOOK">The opening technique — curiosity gap, bold claim, question, story opener, or pattern interrupt.</Concept>
                <Concept term="STRUCTURE">Content flow — problem/solution, testimonial first, authority lead, before/after, list.</Concept>
                <Concept term="TONE">Emotional register — professional, empathetic, urgent, aspirational, conversational.</Concept>
                <Concept term="PROOF_TYPE">Type of social proof — case study, statistic, expert endorsement, certification, before/after photo.</Concept>
                <Concept term="CTA">Call to action style — soft (learn more), medium (book a call), hard (invest now), scarcity-driven.</Concept>
                <Concept term="FACE">Whether a recognisable human face is prominent — affects trust and CPM in Meta&apos;s algorithm.</Concept>
                <Concept term="EDIT_PACE">Video edit speed — static / slow / medium / fast. Detected from scene cut frequency.</Concept>
                <Concept term="MUSIC">Background audio — none / background / dominant. Affects mood and ad policy compliance.</Concept>
                <Concept term="PRODUCTION_STYLE">Visual production quality — UGC, polished studio, screen recording, animation, talking head.</Concept>
                <Concept term="DURATION">Video length bucket — 15s / 30s / 60s / 60s+. Short-form and long-form behave differently in Meta&apos;s delivery system.</Concept>
              </div>
            </SubSection>

            <SubSection title="How analysis works">
              <Step n={1}>Upload a creative (image or video). The platform sends it to the ML service.</Step>
              <Step n={2}>For images: Claude Vision analyses the frame directly. For video: Whisper transcribes the audio, scene detection measures edit pace, and ffprobe extracts audio characteristics.</Step>
              <Step n={3}>Atoms are stored with a <strong>confidence score</strong> (0–1). Vision-extracted atoms score ~0.80, transcript-based ~0.75, metadata-based (duration) 1.0.</Step>
              <Step n={4}>Each atom is aligned to the platform-wide taxonomy using embedding similarity — so &quot;bold claim&quot; and &quot;strong opener&quot; from different tenants map to the same canonical atom value.</Step>
            </SubSection>

            <SubSection title="Creative statuses">
              <p><Badge color="bg-blue-100 text-blue-700">UPLOADED</Badge> — file stored, analysis not yet run.</p>
              <p><Badge color="bg-yellow-100 text-yellow-700">ANALYZING</Badge> — ML pipeline running (takes 15–60 seconds).</p>
              <p><Badge color="bg-green-100 text-green-700">ANALYZED</Badge> — atoms extracted and available for the GLM and brief generation.</p>
              <p><Badge color="bg-red-100 text-red-700">FAILED</Badge> — analysis error. Check that the file format is supported (MP4, MOV, JPG, PNG, WebP).</p>
            </SubSection>

            <SubSection title="Viewing and editing atoms">
              <p>Click any creative row to open its detail page, then navigate to the Atoms tab. You can:</p>
              <p>• <strong>Confirm</strong> auto-extracted atoms (high confidence ones are auto-confirmed)</p>
              <p>• <strong>Correct</strong> wrong atoms — your correction is stored as an RLHA signal and improves future extractions</p>
              <p>• <strong>Add</strong> atoms manually — useful for subjective attributes the AI can&apos;t reliably detect</p>
              <p>• <strong>Delete</strong> atoms — only auto-extracted ones; manually added atoms are protected</p>
            </SubSection>

            <SubSection title="Anchor creatives">
              <p>Mark one or two creatives as <strong>anchors</strong> using the &quot;Set anchor&quot; button. Anchors are the baseline against which new creatives are tested — the platform always keeps anchor ad sets running at a fixed budget alongside test cells. If anchors and test cells diverge at the same time (divergence score &gt; threshold), this signals a market shift rather than a creative effect, and the gate evaluation is paused automatically.</p>
            </SubSection>

            <Tip>The Bayesian GLM learns at the <em>atom level</em>, not the creative level. A new creative with the same atoms as a high-performer gets an optimistic prior from day one — so strong atom choices compound over time.</Tip>
          </Section>

          {/* ── Media Library ────────────────────────────────────────────── */}
          <Section id="media-library" title="🖼️ Media Library">
            <p>
              The Media Library is where you manage all your visual assets — images and videos — that get placed into landing page blocks and ad creatives.
              Unlike the Creatives section (which manages finished ad creatives), the Media Library is about <strong>raw source material</strong>: the pool of assets the auto-fill pipeline draws from when assembling landing page variants.
            </p>

            <SubSection title="3-level tag system">
              <p>Every asset is tagged on three levels. The AI suggests tags automatically; you confirm or correct them — your corrections train the tagging model (RLHA signal stored in <code>tag_rlha_log</code>):</p>
              <Concept term="L1 — Object type">What the asset <em>is</em>. Examples: founder_portrait, property_aerial, team_photo, chart, product_demo, testimonial_video.</Concept>
              <Concept term="L2 — Role / slot">Where in a landing page this asset <em>belongs</em>. Examples: hero_background, proof_image, trust_logo, cta_thumbnail.</Concept>
              <Concept term="L3 — Mood / tone">The emotional register the asset conveys. Examples: professional, warm, urgent, aspirational, documentary.</Concept>
            </SubSection>

            <SubSection title="How auto-fill uses tags">
              <p>When you generate a landing page variant, the auto-fill pipeline matches template slot requirements against your library. A HERO slot that expects an L2 tag of <code>hero_background</code> will be filled with the highest-scoring asset carrying that tag. Scores are based on past performance: if a <code>founder_portrait</code> asset correlates with higher CVR across previous landings, it gets a higher prior for the HERO slot.</p>
            </SubSection>

            <SubSection title="Asset detail and placement history">
              <p>Click any asset card to see its full detail: all three tag levels, which landing pages and ad creatives are currently using it (<strong>placement history</strong>), and the cumulative reward signal — a running score reflecting how well this asset has performed across every context it has appeared in.</p>
            </SubSection>

            <SubSection title="Uploading assets">
              <Step n={1}>Drag and drop files onto the upload panel, or click to browse. Accepted formats: JPG, PNG, WebP, GIF, MP4, MOV.</Step>
              <Step n={2}>Add a title (used in the library grid and placement history).</Step>
              <Step n={3}>After upload, click <strong>Auto-tag</strong> to trigger Claude Vision analysis. Suggested tags appear as chips — click the green ✓ to confirm or the red ✗ to reject and type a correction.</Step>
              <Step n={4}>Assets with at least one confirmed L1 + L2 tag are eligible for auto-fill in landing generation.</Step>
            </SubSection>

            <Tip>Tag your assets thoroughly before generating landings — the auto-fill pipeline can only place assets it knows the role of. Untagged assets are ignored during generation.</Tip>
          </Section>

          {/* ── Templates ────────────────────────────────────────────────── */}
          <Section id="templates" title="📐 Templates">
            <p>
              Templates define the <strong>structure</strong> of a landing page: which blocks appear, in what order, and in what layout variant.
              They are separate from content — the same template can generate dozens of different-looking landing pages depending on the brief and the media library.
            </p>

            <SubSection title="The 5 default templates">
              <div className="space-y-3">
                <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                  <h4 className="font-semibold text-gray-900">Authority</h4>
                  <p className="text-sm text-gray-600 mt-1">Leads with credentials, press mentions, and expert positioning. Best for high-ticket offers where trust must be established before the pitch. Block order: Hero → Proof → Problem → Solution → Social proof → CTA.</p>
                </div>
                <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                  <h4 className="font-semibold text-gray-900">Transparency</h4>
                  <p className="text-sm text-gray-600 mt-1">Leads with full disclosure — numbers, track record, risks acknowledged. Best for regulated industries (financial, medical) where credibility requires honesty. Block order: Hero → Data/stats → Process → FAQ → Risk disclaimer → CTA.</p>
                </div>
                <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                  <h4 className="font-semibold text-gray-900">Story</h4>
                  <p className="text-sm text-gray-600 mt-1">Narrative arc: founder or customer journey. Builds emotional connection before the logical case. Best for audiences that are cold to the category. Block order: Hook story → Problem → Journey → Turning point → Result → CTA.</p>
                </div>
                <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                  <h4 className="font-semibold text-gray-900">Fast Track</h4>
                  <p className="text-sm text-gray-600 mt-1">Minimal, high-speed: one big claim, one proof point, one CTA. Designed for retargeting or warm audiences who already know the category. Block order: Hero (bold claim) → Single proof → CTA.</p>
                </div>
                <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                  <h4 className="font-semibold text-gray-900">Education First</h4>
                  <p className="text-sm text-gray-600 mt-1">Teaches the reader about the problem before introducing the solution. Best for complex or misunderstood categories where the audience doesn&apos;t yet recognise their own need. Block order: Hook question → Problem education → Market context → Solution → Proof → CTA.</p>
                </div>
              </div>
            </SubSection>

            <SubSection title="Block layout variants">
              <p>Each block in a template supports up to 4 layout variants. You can change these per-block when forking a template:</p>
              <p><strong>centered</strong> — text and media stacked vertically, centred. Clean, mobile-first.</p>
              <p><strong>left-text-right-image</strong> — two-column: copy on left, visual on right. Best for proof and feature blocks.</p>
              <p><strong>full-bleed-video</strong> — video fills the full width of the viewport behind overlaid text. High impact, requires a strong hero video asset.</p>
              <p><strong>split-screen</strong> — equal halves, no overlap. Best for before/after comparisons or two distinct claims.</p>
            </SubSection>

            <SubSection title="Forking and customising">
              <Step n={1}>Click <strong>Fork &amp; customise</strong> on any template card. A tenant copy is created — changes to your fork never affect the default templates.</Step>
              <Step n={2}>Use the up/down arrows to reorder blocks. The order determines what a visitor sees first — the hook block should almost always be first.</Step>
              <Step n={3}>Change layout variants per block using the dropdown on each block card.</Step>
              <Step n={4}>Save and set as default. Future &quot;Generate landing&quot; calls will use this template unless you specify otherwise.</Step>
            </SubSection>

            <Warning>Changing the block order of a template that is currently in use by active landing variants will NOT retroactively change those variants. The template change only affects new generations.</Warning>
          </Section>

          {/* ── Landing Pages ────────────────────────────────────────────── */}
          <Section id="landings" title="🧩 Landing Pages">
            <p>
              Landing pages are the centrepiece of the testing loop. The platform generates multiple variants from a brief, serves them with Thompson Sampling, tracks every scroll, block view, and form interaction, and continuously shifts traffic toward the variant that is most likely to produce a real conversion — not just a form fill.
            </p>

            <SubSection title="Generation flow">
              <Step n={1}><strong>Choose a brief and a template.</strong> The brief provides the audience, tone, and offer context. The template provides the structure.</Step>
              <Step n={2}><strong>Set the number of variants (1–5).</strong> For a fresh audience with no data, generate 3–5 to explore. For a warm audience you&apos;ve been testing for 30+ days, 2 variants (one control, one challenger) is usually enough.</Step>
              <Step n={3}><strong>The AI fills slots.</strong> Each block has text slots (headline, subheadline, body) and media slots. Text is generated by the LLM using your brief, product facts, and the best-performing atom patterns from your existing data. Media slots are filled from your library using the tag system.</Step>
              <Step n={4}><strong>Compliance pre-check runs automatically.</strong> Every generated variant is checked against Meta&apos;s advertising policies, your product facts, and your registered rulebases (e.g. SEC 506(c) for investment offers). A FAIL result blocks approval.</Step>
              <Step n={5}><strong>Review and compare.</strong> The comparison view shows variants side by side with their message match score — how well the landing&apos;s headline promise aligns with the ad that drove the click. High message match improves both conversion rate and Meta&apos;s Quality Score for your ad.</Step>
              <Step n={6}><strong>Approve chosen variants.</strong> Approved variants move to ACTIVE status and become eligible for Thompson Sampling. The arm is initialised with an informative prior based on the atoms it contains — a new variant with a proven HOOK atom starts with a higher prior than a blank slate.</Step>
            </SubSection>

            <SubSection title="Inline editing and RLHA">
              <p>
                You can edit any text block directly in the comparison view. <strong>Always provide a reason for your edit</strong> — the reason is stored as a Reinforcement Learning from Human Feedback (RLHA) signal.
                The platform uses these corrections as few-shot examples the next time it generates content for your tenant — so the model learns your brand voice, forbidden phrases, and preferred formulations over time.
                Track progress in the <strong>Learning (RLHA)</strong> section: the target metric is % of generations approved without edits, which typically improves from ~30% to 70%+ over 3 months of consistent use.
              </p>
            </SubSection>

            <SubSection title="Message Match Score">
              <p>A score from 0–100 measuring how closely the landing page headline and offer match the ad that brought the visitor there. Scored by the LLM comparing your ad copy (from Meta Writes) against the landing&apos;s hero block text. Low message match (&lt;50) is one of the top causes of high bounce rate on the landing even when the ad has good CTR.</p>
            </SubSection>

            <SubSection title="Variant statuses">
              <p><Badge color="bg-gray-100 text-gray-700">DRAFT</Badge> — generated, not yet submitted for review.</p>
              <p><Badge color="bg-blue-100 text-blue-700">REVIEW</Badge> — submitted; compliance check either pending or passed, awaiting marketer approval.</p>
              <p><Badge color="bg-green-100 text-green-700">ACTIVE</Badge> — approved, in the Thompson Sampling pool, receiving real traffic.</p>
              <p><Badge color="bg-yellow-100 text-yellow-700">PAUSED</Badge> — temporarily removed from the pool without losing its posterior data. Use this to pause a variant during a budget freeze without losing the accumulated α/β statistics.</p>
              <p><Badge color="bg-red-100 text-red-700">ARCHIVED</Badge> — permanently retired. Its data remains in the GLM history but it will never receive traffic again.</p>
            </SubSection>

            <Note>Only ACTIVE variants participate in Thompson Sampling. Pausing a variant does not reset its Beta distribution — when you reactivate it, it resumes from where it left off.</Note>
          </Section>

          {/* ── Meta Ads ─────────────────────────────────────────────────── */}
          <Section id="meta-ads" title="📣 Meta Ads">
            <p>
              The Meta Ads page is the bridge between your landing pages and Meta&apos;s ad system. All ad creation and management flows through this platform — publishing directly in Meta Ads Manager bypasses attribution tracking, A/B assignment, and compliance gating.
            </p>

            <SubSection title="Linkage Health">
              <p>The green/red banner at the top of the page shows whether the platform can talk to Meta on your behalf. It checks four things:</p>
              <p>• <strong>Page ID configured</strong> — required for ad creation</p>
              <p>• <strong>Instagram Actor ID configured</strong> — required for Instagram placement</p>
              <p>• <strong>Access token valid</strong> — checked against the Meta Graph API (token expiry is monitored daily)</p>
              <p>• <strong>Default pixel set</strong> — required for conversion tracking on published ads</p>
              <p>All four must be green before you can publish.</p>
            </SubSection>

            <SubSection title="Publishing an ad">
              <Step n={1}>Select an ACTIVE landing variant from the list.</Step>
              <Step n={2}>Click <strong>Publish to Meta</strong>. The platform creates the ad in Meta with status PAUSED — no spend starts until you explicitly activate it. This gives you a chance to review the creative in Meta Ads Manager.</Step>
              <Step n={3}>Once satisfied, click <strong>Activate</strong>. The ad starts spending and the platform begins tracking its creative ID in the click URL for attribution.</Step>
            </SubSection>

            <SubSection title="Ad Map">
              <p>The Ad Map reconciles every Meta ad with what the platform knows about it. There are three categories:</p>
              <p><strong>Platform-managed</strong> — created through this platform, fully tracked.</p>
              <p><strong>Reconciled</strong> — created outside the platform but linked to a landing variant. Attribution and testing work but with lower confidence.</p>
              <p><strong>Manual (unmanaged)</strong> — detected in your Meta account but not linked. Their conversion data is not used in the Bayesian model. To link, click &quot;Link to variant&quot; and select the matching landing.</p>
            </SubSection>

            <SubSection title="Configuration Drift">
              <p>If anyone changes an ad&apos;s targeting, budget, or creative directly in Meta Ads Manager without going through the platform, the system detects the divergence on the next 30-minute sync and shows a <strong>Configuration Drift</strong> warning. Review the change in Meta and click <strong>Acknowledge</strong> — this logs that you are aware of the divergence and prevents it from triggering a false circuit-breaker alert.</p>
            </SubSection>

            <Warning>Never change ad budgets directly in Meta Ads Manager. The platform enforces a &le;20% budget change rule (to protect Meta&apos;s learning phase) — bypassing it resets the learning phase and wastes 7–14 days of data.</Warning>
          </Section>

          {/* ── Campaigns ────────────────────────────────────────────────── */}
          <Section id="campaigns" title="🏹 Campaigns">
            <p>
              Campaigns implement the <strong>two-contour testing architecture</strong> — the core methodology that separates the platform from a standard Meta campaign setup.
              The idea is to never let an unproven creative scale, and never kill a creative that is actually good but just needed more data.
            </p>

            <SubSection title="The two-contour model">
              <Concept term="TEST contour">A set of isolated ad sets, each carrying one creative variant, all with equal budgets and the same audience spec. No ad set can influence another&apos;s delivery. This is where you find winners.</Concept>
              <Concept term="SCALE contour">The confirmed winner runs here at full budget, with a 1.5× budget confirmation run as a final gate before full scale. This is where you extract ROI.</Concept>
              <p className="mt-2">The platform manages the full lifecycle: create TEST → run gates → select winner → confirmation run → transfer to SCALE.</p>
            </SubSection>

            <SubSection title="Gate system (how winners are selected)">
              <p>Each ad set in the TEST contour passes through three gates before a winner can be declared:</p>
              <p><Badge color="bg-gray-100 text-gray-700">GATE 1</Badge> — &ge;5,000 impressions and &ge;2 days running. Checks CTR vs group mean. This eliminates creatives with fundamentally bad thumbnails before spending too much.</p>
              <p><Badge color="bg-blue-100 text-blue-700">GATE 2</Badge> — &ge;300 clicks. Checks CTR more precisely. Ensures you have enough data to trust the click-through signal.</p>
              <p><Badge color="bg-indigo-100 text-indigo-700">GATE 3</Badge> — &ge;50 conversions. Checks CVR vs anchor. This is the real gate — only real conversions count, not just clicks.</p>
              <p><Badge color="bg-green-100 text-green-700">PASSED</Badge> — all three gates cleared. Eligible for promotion to SCALE.</p>
              <p><Badge color="bg-red-100 text-red-700">KILLED</Badge> — failed a gate (P(metric &gt; threshold) &lt; 10%) or hit the per-ad-set spend cap. Stops spending immediately.</p>
            </SubSection>

            <SubSection title="James-Stein winner selection">
              <p>When a batch completes and multiple ad sets pass all gates, the platform does <strong>not</strong> simply pick the one with the highest observed CVR. Raw winners overfit — the best performer in a small test is often just the luckiest, not the genuinely best. Instead, it applies James-Stein shrinkage: all winner estimates are pulled toward the group mean by an amount proportional to uncertainty. The result is a winner estimate that is less extreme and more likely to replicate at scale.</p>
            </SubSection>

            <SubSection title="Recommended batch (EVI)">
              <p>Before launching a test batch, click <strong>Recommend batch</strong>. The platform ranks your creatives by <strong>Expected Value of Information (EVI)</strong> — how much we expect to learn from testing each one. New creatives with little data rank high (high exploration value). Proven creatives with consistent posteriors rank lower. The recommendation balances exploitation (run the known-good arms) with exploration (run the promising unknowns).</p>
            </SubSection>

            <SubSection title="Learning phase protection">
              <p>Meta&apos;s algorithm needs 50 conversions per ad set to exit the learning phase. During learning, delivery is volatile and CPA is unreliable. The platform monitors learning phase status and will not change budgets by more than 20% in a single step — a larger change resets the learning phase counter.</p>
            </SubSection>

            <Tip>Keep test batch sizes at 3–9 creatives. Smaller batches finish faster and waste less budget on losers. Larger batches can run simultaneously if budget allows — each creative gets its own ad set and they don&apos;t compete with each other.</Tip>
          </Section>

          {/* ── A/B Results ──────────────────────────────────────────────── */}
          <Section id="ab-results" title="🧪 A/B Results">
            <p>
              A/B Results shows the output of the platform&apos;s Bayesian A/B testing engine, powered by <strong>Thompson Sampling</strong> with a Beta-Binomial model.
              Unlike frequentist tests that wait for a fixed sample size, Thompson Sampling is an online algorithm — it updates after every conversion and shifts traffic allocation in real time.
            </p>

            <SubSection title="How Thompson Sampling works">
              <p>Each landing variant has a <strong>Beta distribution</strong> over its true conversion rate, parameterised by α (successes) and β (failures):</p>
              <p>• When a visitor converts: α increases by 1. The distribution shifts right — we think the CVR is higher.</p>
              <p>• When a visitor does <em>not</em> convert after 7 days: β increases by 1. The distribution shifts left.</p>
              <p>• At each routing decision, the system draws one sample from each variant&apos;s Beta distribution and sends the visitor to the variant with the highest draw. This means the variant with the highest <em>posterior probability of being best</em> gets the most traffic — not just the one with the highest raw CVR.</p>
              <p>Daily decay (α = 1 + 0.99×(α−1)) gently pulls posteriors back toward the uninformative prior, ensuring recent data matters more than old data from months ago.</p>
            </SubSection>

            <SubSection title="Reading the results table">
              <p><strong>Posterior mean CVR</strong> — the Bayesian estimate of the variant&apos;s true conversion rate (α / (α + β)). More stable than the raw observed rate, especially with small samples.</p>
              <p><strong>P(best)</strong> — estimated via 10,000 Monte Carlo draws. The probability that this variant has the highest true CVR among all currently active variants. Once a variant reaches P(best) &gt; 90%, the testing question is effectively answered.</p>
              <p><strong>Confidence interval</strong> — the 95% credible interval from the Beta distribution. Narrow CI = lots of data, high certainty. Wide CI = few conversions, still uncertain.</p>
              <p><strong>Win bar</strong> — visual representation of P(best). The longest bar is the current leader.</p>
            </SubSection>

            <SubSection title="Time window filter">
              <p>Choose 7, 30, or 90 days. The 7-day view is noisy but catches recent changes quickly. The 90-day view is stable but may include data from campaigns or audiences that are no longer relevant. For most decisions, use 30 days with at least 100 total conversions across all variants.</p>
            </SubSection>

            <SubSection title="When to act on results">
              <p>Do <em>not</em> act when P(best) is below 70% — the data is still ambiguous and stopping early would give you a false winner. At P(best) &gt; 90% with &ge;50 conversions on the winner, you have a result worth acting on: pause the losers, archive underperformers, or promote the winner to the Campaigns TEST batch as the new anchor.</p>
            </SubSection>

            <Note>Thompson Sampling manages traffic allocation automatically — you do not need to manually adjust variant weights. The only action required from you is approving new variants (to add them to the pool) and archiving clear losers (to stop wasting budget).</Note>
          </Section>

          {/* ── Quality Score ────────────────────────────────────────────── */}
          <Section id="quality" title="🎯 Quality Score">
            <p>
              Quality Score is a <strong>protective metric</strong>, not a primary optimisation target.
              Its job is to catch creatives and landing pages that are gaming CVR in ways that do not represent real business value — clickbait hooks that inflate CTR but produce poor-quality leads, or landing pages that trick users into submitting forms.
            </p>

            <SubSection title="How the composite score is computed">
              <p>The score is a weighted average of four components, computed at session close (the 15-minute idle window):</p>
              <p><strong>Match Score (w1)</strong> — how well the landing headline matches the ad that brought the visitor. Computed by the LLM at variant creation time. A low match score (below 60) reliably predicts high bounce rate and poor lead quality.</p>
              <p><strong>1 − Bounce Rate (w2)</strong> — inverse of the bounce rate. A bounce is defined as a session with exactly one PAGE_VIEW event and no scroll deeper than 20% of the page, within 8 seconds.</p>
              <p><strong>Scroll Depth (w3)</strong> — average maximum scroll depth across non-bounced sessions. A landing page that causes visitors to stop reading halfway through its content blocks likely has a persuasion problem.</p>
              <p><strong>Form Completion (w4)</strong> — percentage of sessions that progressed from FORM_START to FORM_SUBMIT. A high drop-off in the form itself indicates friction: too many fields, a confusing layout, or a trust gap at the final step.</p>
              <p>Weights are equal by default (0.25 each) but can be adjusted per tenant in Settings.</p>
            </SubSection>

            <SubSection title="Clickbait detection">
              <p>A creative is flagged as a <strong>Clickbait Suspect</strong> when:</p>
              <p>• P(CTR &gt; anchor CTR) &gt; 90% — the creative significantly outperforms anchors on click-through rate, <em>AND</em></p>
              <p>• P(CVR &lt; anchor CVR) &gt; 90% <em>OR</em> P(bounce rate &gt; anchor bounce rate) &gt; 90% — the clicks are not converting and/or bouncing at high rates.</p>
              <p>This pattern means the ad is promising something the landing page does not deliver. Continuing to run it inflates your reported CTR while degrading lead quality and training Meta&apos;s algorithm on the wrong signals.</p>
            </SubSection>

            <SubSection title="Using Quality Score in decisions">
              <p>Quality Score is shown alongside CVR on the A/B Results page as a <em>sanity check</em>. If Variant A has higher CVR but lower Quality Score than Variant B, investigate before declaring Variant A the winner — the CVR difference may be driven by low-quality conversions (e.g. bot traffic that passed fraud filtering, or misleading copy that triggered impulsive form submits that never convert in CRM).</p>
            </SubSection>

            <Tip>Aim for Quality Score &gt; 65 before scaling any variant. Below 50 is a signal to rewrite the landing, not increase budget.</Tip>
          </Section>

          {/* ── Briefs ───────────────────────────────────────────────────── */}
          <Section id="briefs" title="📝 Briefs">
            <p>
              A brief is the structured creative specification that drives AI content generation. Think of it as the creative brief you&apos;d give a copywriter — except the AI actually reads and follows it, and it gets richer over time as the platform learns your product.
            </p>

            <SubSection title="What goes into a brief">
              <p><strong>Target audience</strong> — who the creative is for. Be specific: &quot;Accredited investors aged 45–65 in the US, interested in alternative assets and frustrated with low bond yields&quot; produces better output than &quot;investors&quot;.</p>
              <p><strong>Key message</strong> — the single most important thing the viewer should take away. One sentence.</p>
              <p><strong>Offer structure</strong> — what action you&apos;re asking for and what they get in return. For investor funnels: &quot;book a 30-minute call, receive the full offering memorandum&quot;.</p>
              <p><strong>Tone</strong> — professional, empathetic, urgent, educational, etc. This maps directly to the TONE atom used in generation.</p>
              <p><strong>Recommended atoms</strong> — auto-populated from your last 90 days of performance data. The platform identifies which atom values (e.g. HOOK=authority_claim, PROOF_TYPE=statistic) have the highest CVR lift and includes them as recommendations. You can override.</p>
              <p><strong>Avoid atoms</strong> — automatically populated with the worst-performing atom values from your history. The AI is instructed not to use these in generated content.</p>
              <p><strong>Voice of Customer (VoC)</strong> — if you have imported customer quotes, the top 5 objections appear in the brief export and are used to generate objection-handling blocks in landings.</p>
            </SubSection>

            <SubSection title="Data-driven brief generation">
              <p>Click <strong>+ Generate</strong> to have the platform auto-build a brief from your current data. It pulls the best-performing atoms from the last 90 days, the current VoC objection list, the highest-CVR audience segments from your session data, and the offer structure from your active variant offers. The generated brief is a DRAFT — you review, edit, and approve it before it can be used for landing generation.</p>
            </SubSection>

            <SubSection title="Brief types">
              <p><strong>LANDING</strong> — used to generate landing page variants (most common).</p>
              <p><strong>VIDEO</strong> — used to generate a montage plan for the Video section.</p>
              <p><strong>TEXT</strong> — used to generate ad copy for Meta Writes.</p>
            </SubSection>

            <Tip>Import Voice of Customer quotes (customer reviews, sales call transcripts, objections heard by your sales team) before generating your brief — the platform&apos;s LLM will cluster them into objection categories and inject them into the brief as persuasion guidance.</Tip>
          </Section>

          {/* ── Pixels & CAPI ────────────────────────────────────────────── */}
          <Section id="pixels" title="📡 Pixels & CAPI">
            <p>
              Accurate conversion tracking is the foundation of everything. Without it, Thompson Sampling optimises on form submits — not on whether those form submits became actual clients.
              The platform uses <strong>Conversions API (CAPI)</strong> — server-side event sending — as the primary tracking mechanism, with the browser pixel as a secondary signal.
            </p>

            <SubSection title="Why server-side matters">
              <p>Browser pixels are blocked by approximately 40–60% of Safari users (iOS Intelligent Tracking Prevention) and 15–25% of Chrome users (ad blockers). CAPI bypasses these blocks entirely by sending conversion events directly from the platform&apos;s server to Meta&apos;s servers — no browser involvement. This typically lifts reported conversion counts by 20–40% and dramatically improves Meta&apos;s ability to target users similar to your actual converters.</p>
            </SubSection>

            <SubSection title="The CAPI quality gate">
              <p>The platform sends CAPI events only when a conversion passes the quality gate you configure per pixel:</p>
              <p><strong>Min Quality Score</strong> — a conversion session with a Quality Score below this threshold is not forwarded to CAPI. This prevents low-quality, likely-fraudulent form submits from corrupting Meta&apos;s lookalike model.</p>
              <p><strong>CRM match required</strong> — if enabled, the conversion is only forwarded to CAPI after the GHL webhook confirms the contact exists in CRM. This eliminates typo submits.</p>
            </SubSection>

            <SubSection title="Advanced Matching">
              <p>When a visitor submits a form with a valid email address, the platform hashes it (SHA-256) and includes it as <code>external_id</code> in the CAPI payload. Meta uses this for <strong>Advanced Matching</strong> — attributing conversions to users even when the click cookie has expired or the user switched devices. This significantly improves attribution in long sales cycles.</p>
            </SubSection>

            <SubSection title="Multi-pixel setup">
              <p>You can have multiple pixels configured simultaneously:</p>
              <p><Badge color="bg-purple-100 text-purple-700">PRIMARY</Badge> — receives all quality-passing conversions. Used for campaign optimisation.</p>
              <p><Badge color="bg-blue-100 text-blue-700">TEST</Badge> — used during pixel validation. Events sent here do not affect delivery optimisation.</p>
              <p><Badge color="bg-green-100 text-green-700">PRODUCT</Badge> — for product-specific conversion events, separate from the main funnel.</p>
            </SubSection>

            <Note>After configuring a new pixel, use Meta Events Manager to verify events are arriving. It typically takes 20–30 minutes for the first CAPI event to appear there after a test form submission.</Note>
          </Section>

          {/* ── Product Facts ─────────────────────────────────────────────── */}
          <Section id="product-facts" title="📋 Product Facts">
            <p>
              Product Facts is your <strong>source-of-truth knowledge base</strong> — the authoritative claims your product can back up with evidence.
              Every piece of copy generated by the AI is checked against this list before it can be approved. A claim in a landing page that contradicts or lacks support from your Product Facts will trigger a compliance FAIL.
            </p>

            <SubSection title="Why this matters">
              <p>AI language models are fluent and persuasive — but they hallucinate. Without grounding, generated copy might claim a yield improvement your product has never achieved, a guarantee your legal team hasn&apos;t approved, or a regulatory status you haven&apos;t obtained. Product Facts prevents this by giving the AI explicit boundaries.</p>
            </SubSection>

            <SubSection title="Fact categories">
              <p><strong>YIELD</strong> — quantifiable performance claims (&quot;Average 14.2% annualised return over 5 years on invested capital&quot;).</p>
              <p><strong>PROOF</strong> — evidence and social proof (&quot;47 investors in the 2023 Fund I raise — 100% received expected distributions&quot;).</p>
              <p><strong>GUARANTEE</strong> — any guarantee or commitment (&quot;Capital return guarantee on principal for accredited investors under Reg D 506(c)&quot; — only add this if your legal documents actually say this).</p>
              <p><strong>REGULATORY</strong> — legal and regulatory status (&quot;Offering conducted under SEC Regulation D 506(c) — available to verified accredited investors only&quot;).</p>
              <p><strong>OTHER</strong> — anything that doesn&apos;t fit the above categories.</p>
            </SubSection>

            <SubSection title="Best practices">
              <p>• Include a <strong>source</strong> for each fact (a URL, a document name, or a date). The compliance reviewer uses this to assess whether the claim is verifiable.</p>
              <p>• Keep facts <strong>specific and falsifiable</strong>. &quot;We perform well&quot; is not a fact the compliance engine can check. &quot;14.2% annualised return, 2019–2024, Fund I&quot; is.</p>
              <p>• After adding a new fact or deleting an outdated one, click <strong>Re-check active landings</strong> to run a fresh compliance scan on all currently live variants. This catches cases where an old landing used a fact you have since removed.</p>
            </SubSection>

            <Warning>Product Facts are used by the compliance engine — but they do not replace legal review. Always have qualified counsel review claims before publishing, especially for financial or medical offers.</Warning>
          </Section>

          {/* ── Video ─────────────────────────────────────────────────────── */}
          <Section id="video" title="🎬 Video">
            <p>
              The Video section manages the full pipeline from raw footage to approved ad-ready video renditions.
              You upload your raw clips; the platform handles transcription, scene analysis, montage planning, and rendering across all Meta-required aspect ratios.
            </p>

            <SubSection title="Upload and transcription">
              <Step n={1}>Upload your raw footage (MP4 or MOV, up to 500MB). Add a title and optional description.</Step>
              <Step n={2}>Whisper (OpenAI&apos;s speech recognition model) automatically transcribes the audio. The transcript is stored and used both for montage planning and for the TONE/STRUCTURE atom extraction if you later add this clip as a creative.</Step>
              <Step n={3}>Scene detection runs automatically — the system identifies visual cut points and classifies edit pace (static / slow / medium / fast).</Step>
            </SubSection>

            <SubSection title="Montage plan">
              <p>Click <strong>Generate montage plan</strong> on a footage item. The platform&apos;s LLM reads the transcript and scene data, then generates a structured plan following the proven 5-act ad structure:</p>
              <div className="grid grid-cols-1 gap-2 mt-2">
                <Concept term="Hook (0–3s)">The opening moment that stops the scroll. Often the highest-impact lever — the first 3 seconds determine whether the viewer stays.</Concept>
                <Concept term="Problem (3–8s)">Establish the pain or opportunity. The viewer needs to recognise themselves before they&apos;ll listen to the solution.</Concept>
                <Concept term="Solution (8–20s)">Introduce your product or service as the answer. Should flow directly from the problem — no pivots.</Concept>
                <Concept term="Proof (20–35s)">Social proof, data, testimonial, or demonstration. This is where sceptical viewers decide whether to trust you.</Concept>
                <Concept term="CTA (35–45s)">The ask. Single, clear, with urgency if possible. Match the ask level to the awareness stage — cold traffic should be asked for a low-commitment action.</Concept>
              </div>
              <p className="mt-2">You can edit each act&apos;s text before rendering.</p>
            </SubSection>

            <SubSection title="Rendering">
              <p>Click <strong>Render</strong> and select aspect ratios. Meta requires three formats for full placement coverage:</p>
              <p><strong>9:16</strong> — vertical (Reels, Stories). This is increasingly the dominant placement by impression volume.</p>
              <p><strong>4:5</strong> — square-ish (Feed). Works well on desktop feed where full vertical is cut off.</p>
              <p><strong>1:1</strong> — square (legacy feed, some placements). Safe fallback if you only have one crop to manage.</p>
              <p>Each render job runs in the background via FFmpeg. Status updates every 30 seconds: QUEUED → RENDERING → DONE / FAILED.</p>
            </SubSection>

            <SubSection title="Approving renders">
              <p>Before a render can be published to Meta, it must be approved in the <strong>Renders</strong> tab. This is your last manual check — verify the crop is correct, the subtitles (if any) render properly, and the CTA text is visible. Approved renders appear in the Media Library as VIDEO assets available for landing page slots.</p>
            </SubSection>

            <Tip>Generate 3–5 variation combinations from a single piece of footage (different hook + different CTA) before rendering all of them. Review the montage plans first — this is faster and cheaper than rendering every combination.</Tip>
          </Section>

          {/* ── Autonomy ──────────────────────────────────────────────────── */}
          <Section id="autonomy" title="🤖 Autonomy">
            <p>
              The Autonomy section controls how much decision-making the AI agent handles independently.
              The four levels represent a progressive handover of control — from pure observation to full autopilot.
              Each level upgrade requires passing an <strong>Off-Policy Evaluation (OPE)</strong> gate to verify the model&apos;s decisions would have been beneficial on historical data.
            </p>

            <SubSection title="The four levels">
              <div className="space-y-3">
                <div className="flex gap-3 items-start">
                  <Badge color="bg-gray-100 text-gray-700">OBSERVER</Badge>
                  <p className="flex-1">The AI watches, collects data, and runs posteriors — but takes zero automated actions. Everything is manual. Best during onboarding when you want to see how the model behaves before trusting it. All platforms start here.</p>
                </div>
                <div className="flex gap-3 items-start">
                  <Badge color="bg-blue-100 text-blue-700">ADVISOR</Badge>
                  <p className="flex-1">The AI generates recommendations (pause this variant, promote this winner, launch this batch) but you approve each one via the admin panel before it executes. Keeps a human in the loop while eliminating the need to continuously monitor metrics yourself.</p>
                </div>
                <div className="flex gap-3 items-start">
                  <Badge color="bg-yellow-100 text-yellow-700">SEMI_AUTOPILOT</Badge>
                  <p className="flex-1">The AI autonomously executes <strong>low-risk actions</strong>: adjusting Thompson Sampling traffic weights, pausing variants with P(best) &lt; 5% after sufficient data, triggering weekly OPE. High-risk actions (budget changes, campaign pauses, new batch launches) still require your approval. Most clients operate here long-term.</p>
                </div>
                <div className="flex gap-3 items-start">
                  <Badge color="bg-green-100 text-green-700">AUTOPILOT</Badge>
                  <p className="flex-1">Full autonomy. The AI manages budgets, pauses and activates campaigns, promotes winners, and handles circuit-breaker responses without any human intervention. Reserved for tenants with 60+ days of clean data history and a passed OPE gate. You remain notified of every action via Telegram/Slack.</p>
                </div>
              </div>
            </SubSection>

            <SubSection title="Off-Policy Evaluation (OPE)">
              <p>Before upgrading to any level above OBSERVER, you must run OPE. The OPE process:</p>
              <Step n={1}>Replays the last 30 days of your decision log through the AI&apos;s current policy.</Step>
              <Step n={2}>Uses Importance Sampling with Propensity Weights (IPS) to estimate what the cumulative reward would have been if the AI had been making all the decisions during that period.</Step>
              <Step n={3}>Compares the estimated AI reward against your actual achieved reward. If the AI would have done better (or at worst, not materially worse), the OPE PASSES.</Step>
              <p>A PASS result is valid for 7 days. The weekly OPE runs automatically at SEMI_AUTOPILOT and AUTOPILOT levels — if it fails, the autonomy level is automatically downgraded to ADVISOR until you investigate and re-run manually.</p>
            </SubSection>

            <Warning>Upgrading to AUTOPILOT requires ADMIN role permissions and a valid OPE PASS from the last 7 days. Downgrade is immediate and non-destructive — you can always step back to a lower level without losing any data or posteriors.</Warning>
          </Section>

          {/* ── Settings ──────────────────────────────────────────────────── */}
          <Section id="settings" title="⚙️ Settings">
            <p>Account-level configuration. Changes here affect the entire platform for your tenant — all users and all campaigns.</p>

            <SubSection title="Meta integration">
              <p><strong>Facebook Page ID</strong> — found in your Facebook Page settings under &quot;Page transparency&quot; or &quot;About&quot; section. Required for creating ads.</p>
              <p><strong>Instagram Actor ID</strong> — found in Meta Business Manager: Business Settings → Instagram Accounts → select your account → the numeric ID in the URL or properties panel. Required for Instagram placement.</p>
              <p><strong>Access token</strong> — a long-lived system user token from Meta Business Manager with <code>ads_management</code>, <code>ads_read</code>, and <code>business_management</code> permissions. The platform monitors expiry and sends an alert 7 days before it expires.</p>
            </SubSection>

            <SubSection title="Notifications">
              <p><strong>Weekly Report Email</strong> — receives the Monday 08:00 UTC performance digest: top 3 creatives, atom-level insights, funnel anomaly flags, and the explainability narrative from the model.</p>
              <p><strong>Alert Channel</strong> — real-time alerts for circuit breaker triggers, token expiry, high fraud rate, site health incidents, and OPE failures. Options: TELEGRAM (fastest), SLACK, or BOTH.</p>
              <p>Configure your Telegram chat ID and bot token (or Slack webhook URL) before going live — circuit breaker alerts are time-sensitive.</p>
            </SubSection>

            <SubSection title="Creative download mode">
              <p><strong>FINAL</strong> — team members can download final approved creative files directly from the platform. Convenient but may be a compliance risk if the download could be used outside the platform&apos;s approval workflow.</p>
              <p><strong>PREVIEW_ONLY</strong> — files can be previewed in the browser but not downloaded. Enforces that all creative distribution goes through the platform&apos;s approval and publish workflow.</p>
            </SubSection>

            <SubSection title="Custom landing domains">
              <p>By default, your landing pages are served from the platform&apos;s domain. To serve from your own domain (e.g. <code>invest.yourcompany.com</code>):</p>
              <Step n={1}>Add your domain in Settings → Landing Domains.</Step>
              <Step n={2}>Add a CNAME DNS record: <code>invest.yourcompany.com → [platform-provided hostname]</code>.</Step>
              <Step n={3}>Wait for DNS propagation (typically 5–30 minutes) and click <strong>Verify</strong>. The platform will provision a TLS certificate automatically.</Step>
              <p>Custom domains improve brand trust on landing pages and prevent domain reputation issues from shared platform domains.</p>
            </SubSection>
          </Section>

          {/* ── Other Tools ──────────────────────────────────────────────── */}
          <Section id="other-tools" title="🔧 Other Tools">
            <div className="grid grid-cols-1 gap-5">

              <div className="bg-gray-50 rounded-xl p-5 border border-gray-200">
                <h3 className="font-semibold text-gray-900 mb-2">🔻 Funnel — Conversion Funnel Configuration</h3>
                <p>Shows your configured funnel stage ladder (e.g. SLOT_SELECTED → FORM_SUBMIT → CALL_HELD → ACCRED_VERIFIED → INVEST_COMMITTED) with weekly counters and Δ% vs prior week. Each stage maps to a Meta CAPI event — this table is the source of truth for Thompson Sampling&apos;s reward signals. The <strong>Onboarding Checklist</strong> widget shows whether your pixel, GHL webhook, and CAPI chain are wired correctly before you go live.</p>
              </div>

              <div className="bg-gray-50 rounded-xl p-5 border border-gray-200">
                <h3 className="font-semibold text-gray-900 mb-2">📅 Month 1 — First-Month Launch Plan</h3>
                <p>A structured checklist for the first 30 days on the platform: pixel setup, first creative upload and analysis, first landing generation, first test batch launch, first OPE run. Each item has a status indicator and detail note. The platform also shows two data quality widgets: <strong>Quality Gate savings</strong> (how many low-quality sessions were filtered from CAPI) and <strong>Date to significance</strong> (estimated days until your active A/B test will have a statistical result).</p>
              </div>

              <div className="bg-gray-50 rounded-xl p-5 border border-gray-200">
                <h3 className="font-semibold text-gray-900 mb-2">⚖️ Compliance — Checks & Rulebase Editor</h3>
                <p>Two tabs: <strong>Checks</strong> shows compliance check results for any entity (creative, variant, content) with override capability; <strong>Rulebases &amp; Phrases</strong> (admin only) lets your legal team manage the registered rulebases (META_FINANCIAL, META_PERSONAL_ATTR, SEC_506C, CLIENT_LEGAL) and the pre-approved phrase library. A pre-approved phrase is a specific formulation your legal team has signed off on — the compliance engine accepts it even if the raw LLM check would flag it. Use this for regulatory boilerplate and offer descriptions.</p>
              </div>

              <div className="bg-gray-50 rounded-xl p-5 border border-gray-200">
                <h3 className="font-semibold text-gray-900 mb-2">🔗 GHL — GoHighLevel CRM Integration</h3>
                <p>Configure and monitor the GoHighLevel webhook integration. Leads from landing pages are passed to GHL via webhook, linked to their original session and ad attribution, and GHL&apos;s lifecycle events (AppointmentCreate, AppointmentUpdate, OpportunityStatusUpdate) feed back into the platform as upper-funnel reward signals for Thompson Sampling. This closes the loop between ad spend and actual CRM outcomes.</p>
              </div>

              <div className="bg-gray-50 rounded-xl p-5 border border-gray-200">
                <h3 className="font-semibold text-gray-900 mb-2">🛡️ Meta Writes — Ad Copy Generator</h3>
                <p>Generates Meta ad copy (primary text, headline, description) from your brief. The generated copy is paired with your landing page during generation — the pairing ensures message match by construction (the landing headline is written to continue the promise made in the ad copy). Compliance pre-check also runs on ad copy before it can be used, including SEC 506(c) rules for investment offers.</p>
              </div>

              <div className="bg-gray-50 rounded-xl p-5 border border-gray-200">
                <h3 className="font-semibold text-gray-900 mb-2">🧠 Learning — RLHA Model Training</h3>
                <p>
                  Shows the Reinforcement Learning from Human Feedback metrics for your tenant. The key metric is <strong>% of generations approved without edits</strong> — a proxy for how well the AI has learned your brand voice. Starts around 25–30% and should reach 65–70%+ after 3 months of consistent use (typically 40–60 generation + review cycles). Also shows the correction delta log: every time you edited generated content, what type of change was made (TONE / LENGTH / FORBIDDEN_WORD / STRUCTURE / BRAND_CONTEXT). Use this to see where the model most often misses.
                </p>
              </div>

            </div>
          </Section>

          <div className="mt-12 pt-6 border-t border-gray-200 text-sm text-gray-400 text-center">
            Have a question not covered here? Reach out — we&apos;ll help you configure the platform for your specific funnel and objectives.
          </div>

        </div>
      </div>
    </div>
  );
}
