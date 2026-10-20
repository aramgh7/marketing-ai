"""Roadmap 9-9 (arch §16.19): ticket_bucket and self_accredited as cluster features; old callers unchanged."""
from app.services.cluster_service import FEATURE_NAMES, N_FEATURES, extract_features, ticket_bucket_ordinal


def test_new_features_registered_last():
    assert FEATURE_NAMES[-2:] == ["ticket_bucket_ord", "self_accredited"]
    assert N_FEATURES == len(FEATURE_NAMES)


def test_ticket_bucket_ordinal_scale():
    assert ticket_bucket_ordinal(None) == 0.0
    assert ticket_bucket_ordinal("LT25K") == 0.0
    assert ticket_bucket_ordinal("25_50K") == 1.0
    assert ticket_bucket_ordinal("50_100K") == 2.0
    assert ticket_bucket_ordinal("100_250K") == 3.0
    assert ticket_bucket_ordinal("GT250K") == 4.0
    assert ticket_bucket_ordinal("weird") == 0.0


def test_extract_features_defaults_and_values():
    events = [{"event_type": "PAGE_VIEW", "event_data": {}, "event_time": "2026-10-20T10:00:00Z"}]
    base = extract_features(events, {"device": "mobile", "started_at": "2026-10-20T10:00:00Z",
                                     "last_event_at": "2026-10-20T10:01:00Z"})
    assert base.shape == (N_FEATURES,)
    assert base[-2] == 0.0 and base[-1] == 0.0
    rich = extract_features(events, {"device": "desktop", "started_at": "2026-10-20T10:00:00Z",
                                     "last_event_at": "2026-10-20T10:01:00Z",
                                     "ticket_bucket": "100_250K", "self_accredited": True})
    assert rich[-2] == 3.0 and rich[-1] == 1.0
    empty = extract_features([], {"ticket_bucket": "GT250K", "self_accredited": False})
    assert empty[-2] == 4.0 and empty[-1] == 0.0
