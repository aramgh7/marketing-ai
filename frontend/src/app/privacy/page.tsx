import Link from "next/link";

export const metadata = {
  title: "Privacy Policy | Marketing AI",
};

export default function PrivacyPage() {
  return (
    <main className="min-h-screen bg-gray-50 py-16 px-4">
      <div className="max-w-3xl mx-auto bg-white rounded-2xl border border-gray-200 shadow-sm p-10">
        {/* Header */}
        <div className="mb-10">
          <Link href="/" className="text-indigo-600 text-sm font-medium hover:underline">← Back</Link>
          <h1 className="text-3xl font-bold text-gray-900 mt-4">Privacy Policy</h1>
          <p className="text-gray-500 text-sm mt-2">Last updated: January 2026</p>
        </div>

        <div className="prose prose-gray max-w-none space-y-8 text-gray-700 text-sm leading-relaxed">

          <section>
            <h2 className="text-lg font-semibold text-gray-900 mb-2">1. Data We Collect</h2>
            <p>
              We collect behavioral data from site visitors (page views, clicks, scroll events)
              only when the user provides explicit consent (<code className="bg-gray-100 px-1 rounded">consent_level = full</code>).
              Without consent, no behavioral data is transmitted to our servers.
            </p>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-gray-900 mb-2">2. How We Use Your Data</h2>
            <p>
              Data collected is used solely to optimize advertising campaigns for the Marketing AI
              platform client. We do not sell personal data to third parties, and we do not use it
              for any purpose other than improving the client's marketing outcomes.
            </p>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-gray-900 mb-2">3. Your Rights</h2>
            <p>Depending on your location, you may have the following rights under applicable law (including GDPR, CCPA/CPRA, and similar regulations):</p>
            <ul className="list-disc pl-5 mt-2 space-y-1">
              <li>Access the personal data we hold about you</li>
              <li>Request correction of inaccurate data</li>
              <li>Request deletion of your data (right to be forgotten / erasure)</li>
              <li>Opt out of the sale or sharing of personal information</li>
              <li>Withdraw consent at any time without penalty</li>
              <li>Non-discrimination for exercising your privacy rights</li>
            </ul>
            <p className="mt-2">
              To exercise any of these rights, please contact us via the email address below.
            </p>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-gray-900 mb-2">4. Cookies & Tracking</h2>
            <p>
              This site uses cookies and tracking technologies only with your explicit consent.
              You can manage your preferences through the consent banner shown on your first visit,
              or update them at any time by clicking the "Cookie Settings" link in the footer.
            </p>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-gray-900 mb-2">5. Data Retention</h2>
            <p>
              Behavioral data is retained for no more than 90 days. After this period, data is
              automatically deleted or anonymized. Account data is retained for as long as the
              account remains active or as required by law.
            </p>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-gray-900 mb-2">6. Security</h2>
            <p>
              We implement technical and organizational measures to protect your data, including
              TLS encryption for data in transit, row-level security (RLS) for tenant data isolation,
              and role-based access controls. We do not store sensitive credentials in plaintext.
            </p>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-gray-900 mb-2">7. California Residents (CCPA/CPRA)</h2>
            <p>
              California residents have additional rights under the California Consumer Privacy Act
              (CCPA) and California Privacy Rights Act (CPRA), including the right to know what
              personal information is collected, the right to delete, and the right to opt out of
              the sale of personal information. We do not sell personal information.
            </p>
          </section>

          <section>
            <h2 className="text-lg font-semibold text-gray-900 mb-2">8. Contact</h2>
            <p>
              For privacy inquiries or to exercise your rights, please contact us at:
              <br />
              <a href="mailto:privacy@marketing-ai.io" className="text-indigo-600 hover:underline font-medium">
                privacy@marketing-ai.io
              </a>
            </p>
          </section>
        </div>
      </div>
    </main>
  );
}
