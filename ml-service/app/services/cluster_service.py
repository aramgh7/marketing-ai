"""
Behavioral Cluster Service (Phase 5-1, §7.3 Этап 1).

Pure-logic functions: feature extraction, K-means fitting, online assignment.
No FastAPI imports — safe to unit-test without Pydantic.

Archetypes (5–8 per §7.3):
  price_seeker      — скроллит прямо к прайсу
  proof_seeker      — первым делом к отзывам
  ready_to_buy      — быстро к CTA, мало времени тратит
  researcher        — долго читает всё
  casual_bounce     — уходит < 10 сек, почти нет взаимодействия
  form_abandoner    — начинает форму, но не отправляет
  engaged_returner  — повторный визит, высокое вовлечение
"""
import json
from typing import Any

import numpy as np


# ──────────────────────────────────────────────────────────────────────────────
# Feature names (order must be stable — centroid serialisation depends on it)
# ──────────────────────────────────────────────────────────────────────────────

FEATURE_NAMES = [
    "session_duration_sec",   # total session length
    "scroll_depth_max",       # 0-1 max scroll fraction
    "scroll_velocity",        # scroll_depth / session_duration (fast = price_seeker)
    "block_price_time",       # seconds hovering / viewing pricing block
    "block_proof_time",       # seconds hovering / viewing proof/testimonials block
    "block_cta_time",         # seconds on CTA block
    "form_started",           # 0/1 started a form field
    "form_submitted",         # 0/1 submitted form
    "is_mobile",              # 0/1 mobile device
    "is_repeat_visit",        # 0/1 has prior session
    "event_count",            # total number of events in session
    "ticket_bucket_ord",      # roadmap 9-9 / arch §16.19: 0..4 ordinal of sessions.ticket_bucket
    "self_accredited",        # 0/1 sessions.self_accredited
]
N_FEATURES = len(FEATURE_NAMES)

_TICKET_ORDINAL = {"LT25K": 0.0, "25_50K": 1.0, "50_100K": 2.0, "100_250K": 3.0, "GT250K": 4.0}


def ticket_bucket_ordinal(bucket) -> float:
    """Ordinal 0..4 for the five investor ticket buckets; unknown/None → 0."""
    return _TICKET_ORDINAL.get(str(bucket).upper(), 0.0) if bucket else 0.0


def _investor_features(session: dict) -> tuple[float, float]:
    accredited = session.get("self_accredited")
    acc = 1.0 if accredited is True or str(accredited).lower() in ("1", "true", "t", "yes") else 0.0
    return ticket_bucket_ordinal(session.get("ticket_bucket")), acc

# Rule-based archetype labels (used when k-means has no trained model yet)
RULE_ARCHETYPES = {
    "casual_bounce":   "Случайный — ушёл в первые 10 секунд",
    "price_seeker":    "Ищущий цену — сразу скроллит к прайсу",
    "proof_seeker":    "Ищущий доказательства — фокус на отзывах",
    "ready_to_buy":    "Готовый купить — быстро к CTA",
    "researcher":      "Изучающий — долго читает всё",
    "form_abandoner":  "Начал форму, но ушёл",
    "engaged_returner": "Повторный визит с высоким вовлечением",
}


# ──────────────────────────────────────────────────────────────────────────────
# Feature extraction from raw event list
# ──────────────────────────────────────────────────────────────────────────────

def extract_features(events: list[dict], session: dict) -> np.ndarray:
    """
    Extract a fixed-length feature vector from a session's events.

    Args:
        events: list of event dicts (event_type, event_data, event_time, block_event_id)
        session: session dict (device, started_at, last_event_at, converted)

    Returns:
        np.ndarray of shape (N_FEATURES,) — float32
    """
    feat: dict[str, float] = {name: 0.0 for name in FEATURE_NAMES}
    feat["ticket_bucket_ord"], feat["self_accredited"] = _investor_features(session or {})

    if not events:
        return np.array([feat[n] for n in FEATURE_NAMES], dtype=np.float32)

    # Session duration
    try:
        from datetime import datetime
        start = _parse_ts(session.get("started_at", ""))
        end   = _parse_ts(session.get("last_event_at", ""))
        if start and end:
            feat["session_duration_sec"] = max(0.0, (end - start).total_seconds())
    except Exception:
        pass

    # Scroll signals
    scroll_events = [e for e in events if e.get("event_type") == "SCROLL"]
    if scroll_events:
        depths = [float(e.get("event_data", {}).get("scroll_depth", 0)) for e in scroll_events]
        feat["scroll_depth_max"] = max(depths) if depths else 0.0
        dur = feat["session_duration_sec"]
        feat["scroll_velocity"] = feat["scroll_depth_max"] / dur if dur > 0 else 0.0

    # Block-time signals (BLOCK_VIEW event with duration_sec in event_data)
    for ev in events:
        if ev.get("event_type") != "BLOCK_VIEW":
            continue
        data     = ev.get("event_data", {}) or {}
        dur_sec  = float(data.get("duration_sec", 0))
        block_id = str(data.get("block_type", ev.get("block_event_id", ""))).lower()
        if any(k in block_id for k in ("pric", "plan")):
            feat["block_price_time"] += dur_sec
        elif any(k in block_id for k in ("proof", "test", "review", "social")):
            feat["block_proof_time"] += dur_sec
        elif "cta" in block_id:
            feat["block_cta_time"] += dur_sec

    # Form signals
    feat["form_started"]   = 1.0 if any(e.get("event_type") == "FORM_START"   for e in events) else 0.0
    feat["form_submitted"] = 1.0 if any(e.get("event_type") == "FORM_SUBMIT"  for e in events) else 0.0

    # Device
    device = str(session.get("device", "")).lower()
    feat["is_mobile"] = 1.0 if device == "mobile" else 0.0

    # Repeat visit (crm_user_id set = returning contact)
    feat["is_repeat_visit"] = 1.0 if session.get("crm_user_id") else 0.0

    # Event count
    feat["event_count"] = float(len(events))

    return np.array([feat[n] for n in FEATURE_NAMES], dtype=np.float32)


def _parse_ts(ts: str):
    """Parse ISO timestamp string, returns datetime or None."""
    if not ts:
        return None
    try:
        from datetime import datetime, timezone
        return datetime.fromisoformat(str(ts).replace("Z", "+00:00"))
    except Exception:
        return None


# ──────────────────────────────────────────────────────────────────────────────
# K-means fitting
# ──────────────────────────────────────────────────────────────────────────────

def fit_clusters(
    feature_matrix: np.ndarray,
    n_clusters: int = 7,
    random_state: int = 42,
) -> list[dict[str, Any]]:
    """
    Fit K-means on session feature matrix.

    Args:
        feature_matrix: shape (n_sessions, N_FEATURES)
        n_clusters: 5–8 per §7.3
        random_state: for reproducibility

    Returns:
        List of centroid dicts: [{feature_name: value, ...}, ...]
    """
    from sklearn.cluster import KMeans
    from sklearn.preprocessing import StandardScaler

    if len(feature_matrix) < n_clusters:
        raise ValueError(
            f"Need at least {n_clusters} sessions to fit {n_clusters} clusters, "
            f"got {len(feature_matrix)}"
        )

    scaler = StandardScaler()
    X_scaled = scaler.fit_transform(feature_matrix)

    km = KMeans(n_clusters=n_clusters, random_state=random_state, n_init=10)
    labels = km.fit_predict(X_scaled)

    # Unscale centroids back to interpretable values
    centroids_raw = scaler.inverse_transform(km.cluster_centers_)

    centroid_dicts = []
    for i, centroid in enumerate(centroids_raw):
        c = {name: float(val) for name, val in zip(FEATURE_NAMES, centroid)}
        c["_cluster_idx"] = i
        c["_session_count"] = int(np.sum(labels == i))
        centroid_dicts.append(c)

    return centroid_dicts


# ──────────────────────────────────────────────────────────────────────────────
# Online cluster assignment
# ──────────────────────────────────────────────────────────────────────────────

def assign_cluster(centroid_dicts: list[dict], features: np.ndarray) -> int:
    """
    Assign a single session to nearest centroid (Euclidean distance).

    Args:
        centroid_dicts: list from fit_clusters()
        features: (N_FEATURES,) array for new session

    Returns:
        cluster index (0-based)
    """
    if not centroid_dicts:
        return 0

    best_idx = 0
    best_dist = float("inf")
    for cd in centroid_dicts:
        centroid_vec = np.array(
            [cd.get(name, 0.0) for name in FEATURE_NAMES], dtype=np.float32
        )
        # Normalise by feature std to avoid scale dominance
        diff  = features - centroid_vec
        scale = np.maximum(np.abs(centroid_vec), 1.0)
        dist  = float(np.sum((diff / scale) ** 2))
        if dist < best_dist:
            best_dist = dist
            best_idx = cd.get("_cluster_idx", 0)

    return int(best_idx)


# ──────────────────────────────────────────────────────────────────────────────
# Rule-based cluster label (fallback when no trained model)
# ──────────────────────────────────────────────────────────────────────────────

def rule_based_label(features: np.ndarray) -> str:
    """Assign archetype label from rules (used pre-model or for validation)."""
    f = dict(zip(FEATURE_NAMES, features.tolist()))

    dur       = f["session_duration_sec"]
    scroll    = f["scroll_depth_max"]
    price_t   = f["block_price_time"]
    proof_t   = f["block_proof_time"]
    cta_t     = f["block_cta_time"]
    form_s    = f["form_started"]
    form_sub  = f["form_submitted"]
    repeat    = f["is_repeat_visit"]

    if dur < 10 and scroll < 0.1:
        return "casual_bounce"
    if form_s > 0 and form_sub < 1:
        return "form_abandoner"
    if repeat > 0 and dur > 60:
        return "engaged_returner"
    if price_t > proof_t and price_t > cta_t:
        return "price_seeker"
    if proof_t > price_t and proof_t > cta_t:
        return "proof_seeker"
    if cta_t > 0 and dur < 30 and scroll > 0.5:
        return "ready_to_buy"
    return "researcher"


# ──────────────────────────────────────────────────────────────────────────────
# Block-order personalisation by cluster (§5.4.1 Process 3)
# ──────────────────────────────────────────────────────────────────────────────

CLUSTER_BLOCK_RULES: dict[str, list[str]] = {
    "price_seeker":     ["pricing", "proof", "hero", "faq", "cta"],
    "proof_seeker":     ["proof", "hero", "pricing", "faq", "cta"],
    "ready_to_buy":     ["hero", "cta", "pricing", "proof", "faq"],
    "researcher":       ["hero", "problem", "proof", "pricing", "faq", "cta"],
    "casual_bounce":    ["hero", "proof", "pricing", "faq", "cta"],
    "form_abandoner":   ["hero", "proof", "pricing", "cta", "faq"],
    "engaged_returner": ["cta", "proof", "pricing", "hero", "faq"],
}


def personalise_block_order(
    default_order: list[str],
    cluster_label: str,
    allowed_block_ids: list[str] | None = None,
) -> list[str]:
    """
    Return a personalised block order based on cluster label.

    Constraints (§5.4.1):
    - Only re-orders blocks already in default_order (no new blocks generated)
    - If allowed_block_ids is provided, respects that set
    - Unknown cluster label → return default_order unchanged

    Args:
        default_order: the variant's default block_order list (event_ids OR type names)
        cluster_label: e.g. "price_seeker"
        allowed_block_ids: optional whitelist of block IDs that may be reordered

    Returns:
        Reordered list of block IDs (same length as default_order)
    """
    if not default_order or cluster_label not in CLUSTER_BLOCK_RULES:
        return default_order

    preferred_types = CLUSTER_BLOCK_RULES[cluster_label]
    blocks = list(default_order)
    if allowed_block_ids:
        blocks = [b for b in blocks if b in allowed_block_ids]
        fixed  = [b for b in default_order if b not in allowed_block_ids]
    else:
        fixed = []

    # Sort blocks by preferred type order; blocks whose type isn't in preferred list stay at end
    def sort_key(block_id: str) -> int:
        block_lower = block_id.lower()
        for i, ptype in enumerate(preferred_types):
            if ptype in block_lower:
                return i
        return len(preferred_types)

    reordered = sorted(blocks, key=sort_key)

    # Merge fixed (non-reorderable) blocks back at their original positions
    if fixed:
        result = list(default_order)
        reorder_positions = [i for i, b in enumerate(default_order) if b not in fixed]
        for pos, block in zip(reorder_positions, reordered):
            result[pos] = block
        return result

    return reordered


# ──────────────────────────────────────────────────────────────────────────────
# IPS weights for off-policy evaluation (§12.5, §7.3 Этап 3)
# ──────────────────────────────────────────────────────────────────────────────

def compute_ips_weight(
    chosen_propensity: float,
    clipping_max: float = 10.0,
    clipping_min: float = 0.1,
) -> float:
    """
    Compute clipped Inverse Propensity Score weight for off-policy evaluation.

    Formula: w = 1 / propensity, clipped to [min, max] to reduce variance.
    Required before contextual bandit goes live with real budget (§12.5).

    Args:
        chosen_propensity: P(chosen arm selected) at decision time (stored in decision_log)
        clipping_max: upper clip bound (default 10× baseline)
        clipping_min: lower clip bound (prevents division by near-zero)

    Returns:
        Clipped IPS weight
    """
    if chosen_propensity <= 0:
        return clipping_max
    raw = 1.0 / max(chosen_propensity, clipping_min)
    return float(min(raw, clipping_max))


def validate_ips_coverage(
    propensities: list[float],
    min_effective_n: int = 100,
) -> dict[str, Any]:
    """
    Validate that off-policy evaluation has sufficient coverage.

    Returns dict with: effective_n, max_weight, mean_weight, ready (bool)
    """
    if not propensities:
        return {"effective_n": 0, "max_weight": 0.0, "mean_weight": 0.0, "ready": False}

    weights = [compute_ips_weight(p) for p in propensities]
    w_arr = np.array(weights, dtype=np.float64)

    # Effective sample size: ESS = (Σw)² / Σw²
    ess = float(w_arr.sum() ** 2 / (w_arr ** 2).sum()) if (w_arr ** 2).sum() > 0 else 0.0

    return {
        "effective_n":  round(ess),
        "max_weight":   float(w_arr.max()),
        "mean_weight":  float(w_arr.mean()),
        "ready":        ess >= min_effective_n,
    }
