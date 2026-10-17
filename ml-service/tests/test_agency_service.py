from app.services.agency_service import (
    aggregate_agency_kpis,
    rank_tenants_by_performance,
    compare_creative_sources,
)


SNAPSHOTS = [
    {"tenant_id": "t1", "tenant_name": "Alpha", "lpv": 1000, "held_calls": 50,  "committed": 10},
    {"tenant_id": "t2", "tenant_name": "Beta",  "lpv": 500,  "held_calls": 20,  "committed": 5},
    {"tenant_id": "t3", "tenant_name": "Gamma", "lpv": 200,  "held_calls": 8,   "committed": 2, "cph_usd": 150.0},
]


def test_aggregate_kpis():
    agg = aggregate_agency_kpis(SNAPSHOTS)
    assert agg["tenant_count"]     == 3
    assert agg["total_lpv"]        == 1700
    assert agg["total_held_calls"] == 78
    assert agg["total_committed"]  == 17
    assert agg["commit_rate"]      == round(17/78, 4)
    assert agg["avg_cph_usd"]      == 150.0


def test_aggregate_kpis_no_cph():
    snaps = [
        {"lpv": 100, "held_calls": 10, "committed": 2},
        {"lpv": 200, "held_calls": 20, "committed": 5},
    ]
    agg = aggregate_agency_kpis(snaps)
    assert agg["avg_cph_usd"] is None
    assert agg["total_held_calls"] == 30


def test_rank_tenants():
    ranked = rank_tenants_by_performance(SNAPSHOTS, metric="held_calls")
    assert ranked[0]["tenant_name"] == "Alpha"
    assert ranked[0]["rank"] == 1
    assert ranked[-1]["tenant_name"] == "Gamma"


def test_rank_by_lpv():
    ranked = rank_tenants_by_performance(SNAPSHOTS, metric="lpv")
    assert ranked[0]["tenant_name"] == "Alpha"
    assert ranked[1]["tenant_name"] == "Beta"


def test_empty_snapshots():
    agg = aggregate_agency_kpis([])
    assert agg["total_lpv"] == 0
    assert agg["commit_rate"] == 0.0

    ranked = rank_tenants_by_performance([])
    assert ranked == []


def test_compare_creative_sources():
    sources = [
        {"source_id": "s1", "source_name": "AgencyA", "kind": "AGENCY",  "lpv": 1000, "held_calls": 80},
        {"source_id": "s2", "source_name": "InHouse",  "kind": "INHOUSE", "lpv": 500,  "held_calls": 30},
    ]
    result = compare_creative_sources(sources)
    assert len(result["sources"]) == 2
    # AgencyA has higher hold rate
    assert result["sources"][0]["source_name"] == "AgencyA"
    assert len(result["pairwise"]) == 1
    pairwise = result["pairwise"][0]
    assert pairwise["source_a"] == "AgencyA"
    assert 0 < pairwise["p_a_better_than_b"] <= 1
