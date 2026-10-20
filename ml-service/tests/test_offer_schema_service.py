"""Roadmap 8-8: investor offer atom schema — validator + one-factor explorer."""
from app.services.offer_schema_service import (
    INVESTOR_RAISE_SCHEMA,
    generate_offers_from_schema,
    offer_text_from_atoms,
    validate_offer_atoms,
)

BASE = {"first_touch_format": "call", "entry_threshold_shown": "from_25k", "proof_type": "founder",
        "urgency": "none", "founder_presence": True, "followup_offer": "agenda"}


def test_valid_investor_offer():
    assert validate_offer_atoms(BASE, INVESTOR_RAISE_SCHEMA) == []


def test_required_field_missing():
    atoms = {k: v for k, v in BASE.items() if k != "proof_type"}
    errs = validate_offer_atoms(atoms, INVESTOR_RAISE_SCHEMA)
    assert any("proof_type is required" in e for e in errs)


def test_enum_value_outside_schema():
    errs = validate_offer_atoms({**BASE, "first_touch_format": "dinner"}, INVESTOR_RAISE_SCHEMA)
    assert any("first_touch_format='dinner' is not allowed" in e for e in errs)


def test_invented_urgency_is_rejected_but_real_urgency_passes():
    fake = validate_offer_atoms({**BASE, "urgency": "round_close_date"}, INVESTOR_RAISE_SCHEMA)
    assert any("must be backed by a real source" in e for e in fake)
    real = validate_offer_atoms({**BASE, "urgency": "round_close_date"}, INVESTOR_RAISE_SCHEMA,
                                urgency_source="crm_deadline")
    assert real == []


def test_unknown_atom_rejected_and_urgency_source_allowed():
    assert any("unknown atom" in e for e in validate_offer_atoms({**BASE, "discount": "10%"}, INVESTOR_RAISE_SCHEMA))
    assert validate_offer_atoms({**BASE, "urgency_source": "crm_deadline"}, INVESTOR_RAISE_SCHEMA) == []


def test_bool_field_type_checked():
    errs = validate_offer_atoms({**BASE, "founder_presence": "maybe"}, INVESTOR_RAISE_SCHEMA)
    assert any("founder_presence must be a boolean" in e for e in errs)


def test_explorer_changes_one_atom_per_candidate_and_only_valid_ones():
    cands = generate_offers_from_schema(INVESTOR_RAISE_SCHEMA, BASE, max_variants=50)
    assert cands[0]["label"] == "baseline" and cands[0]["atoms"] == BASE
    for c in cands[1:]:
        diff = [k for k in BASE if c["atoms"].get(k) != BASE[k]]
        assert diff == [c["changed"]]
        assert validate_offer_atoms(c["atoms"], INVESTOR_RAISE_SCHEMA) == []
    # no real urgency source -> no urgency candidates can be proposed
    assert all(c["changed"] != "urgency" for c in cands)


def test_explorer_offers_urgency_only_with_real_source():
    cands = generate_offers_from_schema(INVESTOR_RAISE_SCHEMA, BASE, max_variants=50, urgency_source="round_close_date")
    assert any(c["changed"] == "urgency" for c in cands)


def test_explorer_respects_max_variants():
    cands = generate_offers_from_schema(INVESTOR_RAISE_SCHEMA, BASE, max_variants=3)
    assert len(cands) == 4   # baseline + 3


def test_offer_text_for_compliance_gate():
    text = offer_text_from_atoms(BASE, INVESTOR_RAISE_SCHEMA)
    assert "First-touch format: call" in text and "Entry threshold shown: from 25k" in text
    assert "Urgency" not in text   # "none" values are omitted
