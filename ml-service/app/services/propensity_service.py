"""
Propensity Model Service (Phase 5-2, §7.3 Этап 2).

Pure-logic functions: feature extraction, gradient boosting scoring, objection detection.
No FastAPI imports — safe to unit-test without Pydantic.

Model:
  - GradientBoostingClassifier — P(conversion)
  - Threshold-based objection: price | trust | urgency | understanding

Model serialisation (Phase 12-1):
  - Preferred: ONNX bytes (no arbitrary code execution on load)
    Format: b"ONNX\\x00" + onnx_protobuf_bytes
  - Fallback: SHA-256 hash-validated joblib pickle
    Format: b"SAFE\\x00" + 64-byte hex hash + b"\\x00" + pickle_bytes
  - Legacy (read-only, WARN on load): raw pickle starting with b"\\x80"
"""
import logging
from typing import Any

import numpy as np

_log = logging.getLogger(__name__)


# ──────────────────────────────────────────────────────────────────────────────
# Feature set for propensity model
# ──────────────────────────────────────────────────────────────────────────────

PROPENSITY_FEATURE_NAMES = [
    "session_duration_sec",
    "scroll_depth_max",
    "block_price_time",
    "block_proof_time",
    "block_cta_time",
    "form_started",
    "form_submitted",
    "is_mobile",
    "is_repeat_visit",
    "event_count",
    "cluster_id",            # 0-based cluster index (−1 = unknown → 0)
    "scroll_velocity",       # from cluster_service features
]
N_PROPENSITY_FEATURES = len(PROPENSITY_FEATURE_NAMES)

# Objection thresholds (rule-based, used without trained model)
OBJECTION_THRESHOLDS = {
    "price":         ("block_price_time",   20.0),   # >20 s on pricing = price concern
    "trust":         ("block_proof_time",   25.0),   # >25 s on proof = trust concern
    "urgency":       ("session_duration_sec", 15.0), # very short = urgency issue (bounce risk)
    "understanding": ("event_count",         3.0),   # very few events = confused
}


# ──────────────────────────────────────────────────────────────────────────────
# Feature extraction
# ──────────────────────────────────────────────────────────────────────────────

def extract_propensity_features(
    cluster_features: np.ndarray,       # from cluster_service.extract_features()
    cluster_id: int = -1,
) -> np.ndarray:
    """
    Build propensity feature vector by extending cluster features with cluster_id.

    Args:
        cluster_features: (N_CLUSTER_FEATURES,) array from cluster_service
        cluster_id: assigned cluster index (−1 if not assigned)

    Returns:
        np.ndarray of shape (N_PROPENSITY_FEATURES,) — float32
    """
    from app.services.cluster_service import FEATURE_NAMES

    cluster_feat_map = dict(zip(FEATURE_NAMES, cluster_features.tolist()))

    feat = [
        cluster_feat_map.get("session_duration_sec", 0.0),
        cluster_feat_map.get("scroll_depth_max",     0.0),
        cluster_feat_map.get("block_price_time",     0.0),
        cluster_feat_map.get("block_proof_time",     0.0),
        cluster_feat_map.get("block_cta_time",       0.0),
        cluster_feat_map.get("form_started",         0.0),
        cluster_feat_map.get("form_submitted",       0.0),
        cluster_feat_map.get("is_mobile",            0.0),
        cluster_feat_map.get("is_repeat_visit",      0.0),
        cluster_feat_map.get("event_count",          0.0),
        float(max(cluster_id, 0)),                         # −1 → 0
        cluster_feat_map.get("scroll_velocity",      0.0),
    ]

    return np.array(feat, dtype=np.float32)


# ──────────────────────────────────────────────────────────────────────────────
# Rule-based propensity score (fallback without trained model)
# ──────────────────────────────────────────────────────────────────────────────

def rule_based_score(features: np.ndarray) -> float:
    """
    Estimate P(conversion) from simple rules. Returns float in [0, 1].

    Used as fallback before a trained model exists.
    """
    f = dict(zip(PROPENSITY_FEATURE_NAMES, features.tolist()))

    score = 0.05  # base rate

    # Strong positive signals
    if f["form_submitted"] > 0:
        score += 0.50
    elif f["form_started"] > 0:
        score += 0.20

    if f["is_repeat_visit"] > 0:
        score += 0.10

    if f["block_cta_time"] > 5:
        score += 0.10

    # Negative signals
    dur = f["session_duration_sec"]
    if dur < 10:
        score -= 0.05
    if f["scroll_depth_max"] < 0.1:
        score -= 0.05

    return float(min(max(score, 0.0), 1.0))


# ──────────────────────────────────────────────────────────────────────────────
# Objection detection (rule-based, no LLM required in tests)
# ──────────────────────────────────────────────────────────────────────────────

def determine_objection(features: np.ndarray) -> str | None:
    """
    Identify primary objection from session behaviour.

    Returns one of: 'price', 'trust', 'urgency', 'understanding', or None.
    """
    f = dict(zip(PROPENSITY_FEATURE_NAMES, features.tolist()))

    # Understanding: very low event count means the visitor is confused
    if f["event_count"] <= OBJECTION_THRESHOLDS["understanding"][1]:
        return "understanding"

    # Urgency: extremely short session — didn't engage at all
    if f["session_duration_sec"] < 15 and f["form_started"] == 0:
        return "urgency"

    # Price vs trust — pick whichever has more time spent
    price_t = f["block_price_time"]
    proof_t = f["block_proof_time"]

    price_threshold = OBJECTION_THRESHOLDS["price"][1]
    trust_threshold = OBJECTION_THRESHOLDS["trust"][1]

    if price_t > price_threshold and price_t >= proof_t:
        return "price"
    if proof_t > trust_threshold and proof_t > price_t:
        return "trust"

    return None


# ──────────────────────────────────────────────────────────────────────────────
# Internal: safe model loading (Phase 12-1)
# ──────────────────────────────────────────────────────────────────────────────

def _load_and_score(model_bytes: bytes, features: np.ndarray) -> float:
    """
    Deserialise model bytes and return P(convert) for a single feature vector.

    Supported formats:
      b"ONNX\\x00"  — ONNX protobuf; loaded with onnxruntime (no pickle exec).
      b"SAFE\\x00"  — SHA-256-validated joblib pickle.
      b"\\x80..."   — Legacy raw pickle; WARN logged; rotate after next fit.
    """
    import io

    if model_bytes[:5] == b"ONNX\x00":
        import onnxruntime as rt
        sess = rt.InferenceSession(model_bytes[5:])
        input_name = sess.get_inputs()[0].name
        X_input = features.reshape(1, -1).astype(np.float32)
        outputs = sess.run(None, {input_name: X_input})
        prob_output = outputs[1]  # list[dict] or 2-D array
        if isinstance(prob_output, list):
            # list of {class: prob} dicts
            p = float(prob_output[0][1])
        else:
            p = float(prob_output[0][1])
        return p

    elif model_bytes[:5] == b"SAFE\x00":
        import hashlib
        import joblib
        # Format: b"SAFE\x00" + 64 ASCII hex chars + b"\x00" + pickle_bytes
        sha256_hex = model_bytes[5:69].decode("ascii")
        pickle_bytes = model_bytes[70:]
        actual_hex = hashlib.sha256(pickle_bytes).hexdigest()
        if actual_hex != sha256_hex:
            raise ValueError(
                f"Propensity model hash mismatch — possible tampering "
                f"(stored={sha256_hex[:8]}…, actual={actual_hex[:8]}…)"
            )
        model = joblib.load(io.BytesIO(pickle_bytes))
        X = features.reshape(1, -1)
        return float(model.predict_proba(X)[0][1])

    elif model_bytes[:1] == b"\x80":
        import joblib
        _log.warning(
            "Propensity model: legacy pickle model detected — "
            "arbitrary code execution risk; rotate after next fit"
        )
        model = joblib.load(io.BytesIO(model_bytes))
        X = features.reshape(1, -1)
        return float(model.predict_proba(X)[0][1])

    else:
        raise ValueError(
            f"Unknown propensity model format (magic={model_bytes[:5]!r})"
        )


# ──────────────────────────────────────────────────────────────────────────────
# Gradient Boosting scoring (uses trained model bytes from caller)
# ──────────────────────────────────────────────────────────────────────────────

def score_session(
    features: np.ndarray,
    model_bytes: bytes | None = None,
) -> dict[str, Any]:
    """
    Score a single session.

    If model_bytes is None → falls back to rule-based score.
    If model_bytes provided → deserialise joblib model and predict.

    Returns:
        {p_convert: float, main_objection: str|None}
    """
    p_convert: float

    if model_bytes is not None:
        import io
        p_convert = _load_and_score(model_bytes, features)
    else:
        p_convert = rule_based_score(features)

    objection = determine_objection(features)

    return {
        "p_convert":       p_convert,
        "main_objection":  objection,
    }


# ──────────────────────────────────────────────────────────────────────────────
# Gradient Boosting model fitting
# ──────────────────────────────────────────────────────────────────────────────

def fit_propensity_model(
    X: np.ndarray,
    y: np.ndarray,
    n_estimators: int = 200,
    max_depth: int = 4,
    learning_rate: float = 0.05,
    random_state: int = 42,
) -> bytes:
    """
    Fit GradientBoostingClassifier and return serialised model bytes.

    Args:
        X: (n_samples, N_PROPENSITY_FEATURES) feature matrix
        y: (n_samples,) binary conversion labels (0/1)
        n_estimators / max_depth / learning_rate: model hyper-parameters

    Returns:
        joblib-serialised model bytes for storage
    """
    import io
    import joblib
    from sklearn.ensemble import GradientBoostingClassifier
    from sklearn.preprocessing import StandardScaler
    from sklearn.pipeline import Pipeline

    if len(X) < 30:
        raise ValueError(f"Need at least 30 labelled sessions, got {len(X)}")
    if len(X) != len(y):
        raise ValueError("X and y must have the same length")
    if len(np.unique(y)) < 2:
        raise ValueError("y must contain both classes (0 and 1)")

    pipe = Pipeline([
        ("scaler", StandardScaler()),
        ("gb", GradientBoostingClassifier(
            n_estimators=n_estimators,
            max_depth=max_depth,
            learning_rate=learning_rate,
            random_state=random_state,
        )),
    ])
    pipe.fit(X, y)

    # ── Phase 12-1: prefer ONNX (no pickle exec on load) ──────────────────────
    try:
        from skl2onnx import convert_sklearn
        from skl2onnx.common.data_types import FloatTensorType
        initial_type = [("float_input", FloatTensorType([None, N_PROPENSITY_FEATURES]))]
        onnx_proto = convert_sklearn(pipe, initial_types=initial_type)
        return b"ONNX\x00" + onnx_proto.SerializeToString()
    except Exception as exc:
        _log.warning(
            "ONNX conversion failed (%s) — falling back to SHA-256-validated joblib pickle",
            exc,
        )

    # ── Fallback: SHA-256 hash-validated joblib pickle ─────────────────────────
    import hashlib
    buf = io.BytesIO()
    joblib.dump(pipe, buf)
    pickle_bytes = buf.getvalue()
    sha256_hex = hashlib.sha256(pickle_bytes).hexdigest().encode("ascii")  # 64 bytes
    return b"SAFE\x00" + sha256_hex + b"\x00" + pickle_bytes


# ──────────────────────────────────────────────────────────────────────────────
# Uplift score (Phase 5-3)
# ──────────────────────────────────────────────────────────────────────────────

def compute_uplift_score(
    p_convert_personalised: float,
    p_convert_control: float,
) -> float:
    """
    Uplift = P(convert | personalised) − P(convert | control).
    Clamped to [−1, 1].
    """
    return float(min(max(p_convert_personalised - p_convert_control, -1.0), 1.0))
