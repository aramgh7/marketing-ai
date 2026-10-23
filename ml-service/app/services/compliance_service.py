"""
Compliance check service — no FastAPI/Pydantic imports.
Checks landing page text against Meta policies, product facts, trademark watchlist,
and securities-specific rulebases (vision §19.5 / arch §16.5).
"""
from __future__ import annotations

import json
import re
from typing import Any


def check_meta_policy(text: str, llm_client: Any, model: str = "claude-haiku-5-5") -> dict:
    """
    Check text against Meta Advertising Policies.
    Returns {result: PASS|WARN|FAIL, findings: [{rule, excerpt, severity, suggestion}]}
    """
    if llm_client is None:
        return {
            "result": "FAIL",
            "findings": [{"rule": "ML_UNAVAILABLE", "excerpt": "", "severity": "FAIL",
                          "suggestion": "Manual policy review required — LLM unavailable, cannot confirm compliance"}],
        }

    prompt = (
        "Review this marketing text for Meta Advertising Policy violations.\n\n"
        f"Text:\n{text}\n\n"
        "Check for these violations:\n"
        "1. Before/after claims (prohibited): 'lost 20 lbs', 'made $10k'\n"
        "2. Health outcome guarantees: 'guaranteed to cure/treat/heal'\n"
        "3. Financial outcome guarantees: 'guaranteed returns/profits'\n"
        "4. Personal attribute targeting: implies the reader has a negative characteristic\n"
        "5. Misleading claims: false urgency, hidden fees, bait-and-switch\n"
        "6. Prohibited content: weapons, drugs, discrimination\n\n"
        "Respond ONLY with JSON:\n"
        '{"result": "PASS", "findings": []}\n'
        "or with violations:\n"
        '{"result": "FAIL", "findings": [{"rule": "before_after_claims", '
        '"excerpt": "exact text", "severity": "FAIL", "suggestion": "Rephrase as..."}]}'
    )
    try:
        response = llm_client.complete(prompt, model=model)
        data = json.loads(response)
        return {"result": data.get("result", "PASS"), "findings": data.get("findings", [])}
    except Exception:
        return {
            "result": "WARN",
            "findings": [{"rule": "PARSE_ERROR", "excerpt": "", "severity": "WARN",
                          "suggestion": "Manual review required"}],
        }


def check_claims_vs_facts(text: str, product_facts: list[str], llm_client: Any, model: str = "claude-haiku-5-5") -> dict:
    """
    Check factual claims in text against the product fact base.
    Unsupported → WARN, contradicted → FAIL.
    """
    if not product_facts:
        return {
            "result": "PASS",
            "findings": [{"rule": "NO_FACTS", "excerpt": "", "severity": "WARN",
                          "suggestion": "Add product facts to enable claims verification"}],
        }

    if llm_client is None:
        return {
            "result": "FAIL",
            "findings": [{"rule": "ML_UNAVAILABLE", "excerpt": "", "severity": "FAIL",
                          "suggestion": "Manual claims verification required — LLM unavailable, cannot confirm compliance"}],
        }

    facts_str = "\n".join(f"- {f}" for f in product_facts)

    prompt = (
        "Compare the claims in this marketing text against the verified product facts.\n\n"
        f"Marketing text:\n{text}\n\n"
        f"Verified product facts:\n{facts_str}\n\n"
        "Rules:\n"
        "- Claim directly supported by facts → OK\n"
        "- Claim not mentioned in facts → WARN (UNSUPPORTED_CLAIM)\n"
        "- Claim contradicts facts → FAIL (CONTRADICTED_CLAIM)\n\n"
        "Respond ONLY with JSON:\n"
        '{"result": "PASS", "findings": []}\n'
        "or with issues:\n"
        '{"result": "WARN", "findings": [{"rule": "UNSUPPORTED_CLAIM", '
        '"excerpt": "exact claim", "severity": "WARN", '
        '"suggestion": "Add supporting fact or rephrase"}]}'
    )
    try:
        response = llm_client.complete(prompt, model=model)
        data = json.loads(response)
        return {"result": data.get("result", "PASS"), "findings": data.get("findings", [])}
    except Exception:
        return {"result": "WARN", "findings": []}


def check_trademarks(text: str, trademark_terms: list[str]) -> dict:
    """Exact + case-insensitive match of trademark terms in text."""
    if not trademark_terms:
        return {"result": "PASS", "findings": []}

    findings = []
    text_lower = text.lower()
    for term in trademark_terms:
        if term.lower() in text_lower:
            findings.append({
                "rule": "TRADEMARK_DETECTED",
                "excerpt": term,
                "severity": "WARN",
                "suggestion": f"Remove or obtain licence for '{term}'",
            })

    return {"result": "WARN" if findings else "PASS", "findings": findings}


def check_text_against_rules(text: str, rules: list[dict]) -> dict:
    """
    Regex-based rule matching against a list of rulebase rule objects.
    Each rule: {rule_id, severity, description, pattern, suggestion}.
    Returns {result: PASS|WARN|FAIL, findings: [...]}.
    """
    findings = []
    text_lower = text.lower()

    for rule in rules:
        pattern = rule.get("pattern", "")
        if not pattern:
            continue
        try:
            if re.search(pattern, text_lower, re.IGNORECASE):
                findings.append({
                    "rule_id": rule.get("rule_id", "UNKNOWN"),
                    "severity": rule.get("severity", "WARN"),
                    "description": rule.get("description", ""),
                    "suggestion": rule.get("suggestion", ""),
                })
        except re.error:
            # Fallback to plain string match if regex is malformed
            if pattern.lower() in text_lower:
                findings.append({
                    "rule_id": rule.get("rule_id", "UNKNOWN"),
                    "severity": rule.get("severity", "WARN"),
                    "description": rule.get("description", ""),
                    "suggestion": rule.get("suggestion", ""),
                })

    severities = [f["severity"] for f in findings]
    if "FAIL" in severities:
        result = "FAIL"
    elif "WARN" in severities:
        result = "WARN"
    else:
        result = "PASS"

    return {"result": result, "findings": findings}


def run_compliance_check(
    text: str,
    rulebases: dict[str, list[dict]],
) -> dict:
    """
    Run text against multiple named rulebases.
    rulebases: {rulebase_key: [rule, ...]}
    Returns {overall: PASS|WARN|FAIL, per_rulebase: {key: {result, findings}}}.
    """
    per_rulebase: dict[str, dict] = {}
    overall_severities: list[str] = []

    for key, rules in rulebases.items():
        check = check_text_against_rules(text, rules)
        per_rulebase[key] = check
        overall_severities.append(check["result"])

    if "FAIL" in overall_severities:
        overall = "FAIL"
    elif "WARN" in overall_severities:
        overall = "WARN"
    else:
        overall = "PASS"

    return {"overall": overall, "per_rulebase": per_rulebase}


# ─── Personal attribute check (vision §19.5: describe offer, not viewer) ─────

_PERSONAL_ATTR_PATTERNS = [
    (r"are you.{0,40}accredited", "FAIL", "PA001", "Replace with: 'Open to accredited investors'"),
    (r"you (are|must be).{0,40}accredited", "FAIL", "PA001b", "Replace with offer-description language"),
    (r"(if you|you have|struggling with).{0,60}(debt|financial|money problem)", "FAIL", "PA002",
     "Describe solution benefits, not viewer hardship"),
    (r"for (high.net.worth|wealthy|rich)", "FAIL", "PA003", "State eligibility criteria without targeting"),
    (r"do you (qualify|earn|make|have \$)", "WARN", "PA004", "Rephrase to describe offer eligibility"),
    (r"your (portfolio|investments).{0,60}(underperform|failing|losing)", "FAIL", "PA005",
     "Do not imply negative viewer situation"),
]


def check_meta_personal_attr(text: str) -> dict:
    """
    Check text for personal attribute targeting violations.
    Returns {result: PASS|WARN|FAIL, findings: [...]}.
    """
    text_lower = text.lower()
    findings = []

    for pattern, severity, rule_id, suggestion in _PERSONAL_ATTR_PATTERNS:
        try:
            if re.search(pattern, text_lower, re.IGNORECASE):
                findings.append({
                    "rule_id": rule_id,
                    "severity": severity,
                    "description": "Personal attribute targeting violation (§19.5)",
                    "suggestion": suggestion,
                })
        except re.error:
            pass

    severities = [f["severity"] for f in findings]
    result = "FAIL" if "FAIL" in severities else ("WARN" if "WARN" in severities else "PASS")
    return {"result": result, "findings": findings}


def is_from_approved_phrases(text: str, approved_phrases: list[str]) -> dict:
    """
    Check whether text is contained within or matches an approved phrase.
    Returns {approved: bool, matched_phrase: str|None}.
    """
    text_stripped = text.strip().lower()
    for phrase in approved_phrases:
        if phrase.strip().lower() in text_stripped or text_stripped in phrase.strip().lower():
            return {"approved": True, "matched_phrase": phrase}
    return {"approved": False, "matched_phrase": None}


def run_all_text_checks(
    text: str,
    product_facts: list[str],
    trademark_terms: list[str],
    llm_client: Any,
    model: str = "claude-haiku-5-5",
) -> dict:
    """
    Run META_POLICY + CLAIMS + TRADEMARK checks and return combined result.
    Severity: FAIL overrides WARN overrides PASS.
    """
    policy = check_meta_policy(text, llm_client, model)
    claims = check_claims_vs_facts(text, product_facts, llm_client, model)
    tm = check_trademarks(text, trademark_terms)

    all_findings = (
        [{"check_type": "META_POLICY", **f} for f in policy["findings"]]
        + [{"check_type": "CLAIMS", **f} for f in claims["findings"]]
        + [{"check_type": "TRADEMARK", **f} for f in tm["findings"]]
    )

    results = [policy["result"], claims["result"], tm["result"]]
    if "FAIL" in results:
        overall = "FAIL"
    elif "WARN" in results:
        overall = "WARN"
    else:
        overall = "PASS"

    return {
        "result": overall,
        "findings": all_findings,
        "checks": {
            "meta_policy": policy["result"],
            "claims": claims["result"],
            "trademark": tm["result"],
        },
    }
