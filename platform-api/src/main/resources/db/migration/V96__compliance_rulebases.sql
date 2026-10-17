-- Securities compliance rulebases (vision §19.5 / arch §16.5)
-- Platform-wide rules: no tenant RLS (platform admins own these).
-- Tenant-scoped approved phrases: RLS applies.

CREATE TABLE compliance_rulebases (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rulebase_key VARCHAR(80) NOT NULL UNIQUE,
    description  VARCHAR(255),
    rules        JSONB NOT NULL DEFAULT '[]',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

GRANT SELECT ON compliance_rulebases TO mai_app;

-- Approved phrases per tenant (marketer-reviewed, pre-cleared copy)
CREATE TABLE approved_phrases (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    phrase     TEXT NOT NULL,
    context    VARCHAR(120),
    approved_by UUID,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, phrase)
);

ALTER TABLE approved_phrases ENABLE ROW LEVEL SECURITY;
CREATE POLICY rls_approved_phrases ON approved_phrases
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
ALTER TABLE approved_phrases FORCE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON approved_phrases TO mai_app;

-- ─── Seed platform-wide rulebases ──────────────────────────────────────────

-- META_FINANCIAL: financial promotion rules for Meta
INSERT INTO compliance_rulebases (rulebase_key, description, rules) VALUES (
    'META_FINANCIAL',
    'Meta Advertising Policy — financial products and services',
    '[
        {
            "rule_id": "MF001",
            "severity": "FAIL",
            "description": "Guaranteed return promises",
            "pattern": "guaranteed (return|profit|income|earning|yield|gain)",
            "suggestion": "Replace with realistic, non-guaranteed language"
        },
        {
            "rule_id": "MF002",
            "severity": "FAIL",
            "description": "Risk-free claims",
            "pattern": "risk.?free (investment|opportunity|return)",
            "suggestion": "All investments carry risk; remove risk-free claim"
        },
        {
            "rule_id": "MF003",
            "severity": "FAIL",
            "description": "Specific return promises",
            "pattern": "(earn|make|get|return) (\\d+%|\\$\\d+) (per|a|each) (month|year|week|day)",
            "suggestion": "Remove specific return figures or add material risk disclosure"
        },
        {
            "rule_id": "MF004",
            "severity": "WARN",
            "description": "Missing risk disclosure on financial ad",
            "pattern": "invest|investment|returns|yield|portfolio|fund",
            "suggestion": "Ensure risk disclosure is present in ad or landing copy"
        },
        {
            "rule_id": "MF005",
            "severity": "FAIL",
            "description": "Unrealistic urgency with financial promise",
            "pattern": "(limited time|act now|last chance).{0,80}(profit|return|invest)",
            "suggestion": "Remove artificial urgency combined with financial promise"
        }
    ]'
) ON CONFLICT (rulebase_key) DO UPDATE
    SET rules = EXCLUDED.rules, updated_at = now();

-- META_PERSONAL_ATTR: personal attribute targeting prohibition
-- Rule: describe the offer, never the viewer (vision §19.5)
INSERT INTO compliance_rulebases (rulebase_key, description, rules) VALUES (
    'META_PERSONAL_ATTR',
    'Meta Policy — personal attribute targeting prohibition (§19.5: describe offer, not viewer)',
    '[
        {
            "rule_id": "PA001",
            "severity": "FAIL",
            "description": "Directly addressing viewer as accredited investor",
            "pattern": "(are you|you are|you''re|you must be).{0,40}accredited",
            "suggestion": "Replace with offer description: ''Open to accredited investors''"
        },
        {
            "rule_id": "PA002",
            "severity": "FAIL",
            "description": "Implying viewer has financial problem",
            "pattern": "(if you|you have|struggling with|tired of).{0,60}(debt|financial|money problem)",
            "suggestion": "Describe your solution benefits without implying viewer hardship"
        },
        {
            "rule_id": "PA003",
            "severity": "FAIL",
            "description": "Targeting by implied net worth",
            "pattern": "(for (high.net.worth|wealthy|rich)|if you have \\$\\d+)",
            "suggestion": "State offer criteria without targeting implied wealth status"
        },
        {
            "rule_id": "PA004",
            "severity": "WARN",
            "description": "Second-person financial qualification questions",
            "pattern": "do you (qualify|meet the criteria|have \\$\\d+|earn|make)",
            "suggestion": "Rephrase to describe offer eligibility without viewer interrogation"
        },
        {
            "rule_id": "PA005",
            "severity": "FAIL",
            "description": "Implying viewer''s current investment failures",
            "pattern": "(your (portfolio|investments|stocks) (is|are|has|have)).{0,60}(underperform|failing|losing|down)",
            "suggestion": "Do not imply viewer''s investment situation is negative"
        }
    ]'
) ON CONFLICT (rulebase_key) DO UPDATE
    SET rules = EXCLUDED.rules, updated_at = now();

-- SEC_506C: Reg D 506(c) advertising guidance
INSERT INTO compliance_rulebases (rulebase_key, description, rules) VALUES (
    'SEC_506C',
    'SEC Reg D 506(c) — general solicitation compliance',
    '[
        {
            "rule_id": "SC001",
            "severity": "FAIL",
            "description": "Soliciting unverified accredited investors in 506(c)",
            "pattern": "(anyone can|open to (all|everyone|any))",
            "suggestion": "506(c) requires all investors to be verified accredited"
        },
        {
            "rule_id": "SC002",
            "severity": "WARN",
            "description": "Missing accreditation requirement disclosure",
            "pattern": "invest|investor|investment opportunity|raise",
            "suggestion": "Add: ''Only available to verified accredited investors (Reg D 506(c))''"
        },
        {
            "rule_id": "SC003",
            "severity": "FAIL",
            "description": "Equity crowdfunding language in 506(c) context",
            "pattern": "crowdfund|reg (a|cf)|regulation (a|crowdfunding)",
            "suggestion": "Do not reference RegCF/RegA frameworks in a 506(c) solicitation"
        }
    ]'
) ON CONFLICT (rulebase_key) DO UPDATE
    SET rules = EXCLUDED.rules, updated_at = now();

-- CLIENT_LEGAL: InteliGel-specific legal guardrails
INSERT INTO compliance_rulebases (rulebase_key, description, rules) VALUES (
    'CLIENT_LEGAL',
    'Client #1 (InteliGel) — product-specific legal guardrails',
    '[
        {
            "rule_id": "CL001",
            "severity": "FAIL",
            "description": "Medical claims for hydrogel product",
            "pattern": "(cures?|treats?|heals?|prevents?|FDA (approved|cleared)).{0,60}(wound|injury|skin|tissue)",
            "suggestion": "Use 510(k)-cleared language; do not make treatment claims"
        },
        {
            "rule_id": "CL002",
            "severity": "WARN",
            "description": "Unsubstantiated ROI claim",
            "pattern": "(\\d+x|\\d+%) (return|roi|profit|multiple)",
            "suggestion": "Add source citation or remove specific multiple claim"
        }
    ]'
) ON CONFLICT (rulebase_key) DO UPDATE
    SET rules = EXCLUDED.rules, updated_at = now();
