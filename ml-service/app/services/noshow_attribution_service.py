"""No-show attribution model (roadmap 8-3; vision §19.5; arch §16.6) — pure numpy, no I/O.

    logit(P(show)) = β₀ + Σ_j β_j · atom_j + γ · booking_distance_h + δ · tz_known + segment

Fitted by ridge-penalised IRLS (L2 on everything except the intercept) so that rare atoms do not
explode. Each coefficient comes with a Wald standard error from the observed information matrix.

Inputs are plain dicts so the scheduler can post rows straight from SQL:
    {"atoms": {"HOOK": "question", "CTA": "book_call"}, "booking_distance_h": 36.0,
     "tz_known": True, "segment": "GT250K", "showed": True}

Outputs feed two consumers (roadmap 8-3):
  - creator brief (4-4): atoms with a strongly negative β are "no-show drivers" → avoid list
  - clickbait detector (3-14): a hook that wins clicks but drives no-shows is clickbait-like
"""
from __future__ import annotations

import math
from typing import Any

import numpy as np

MIN_ROWS = 30
MIN_ATOM_SUPPORT = 5          # an atom value must appear in >= 5 bookings to get its own β
DRIVER_BETA_THRESHOLD = -0.30  # β below this (and |z| >= 1.64) = no-show driver
DISTANCE_SCALE_H = 72.0        # booking_distance_h is scaled to [0, ~1] by this constant


def encode_features(rows: list[dict]) -> tuple[np.ndarray, np.ndarray, list[str]]:
    """One-hot atoms (TYPE=value, most frequent value per type = reference) + covariates. Returns (X, y, names)."""
    counts: dict[str, int] = {}
    per_type: dict[str, dict[str, int]] = {}
    for r in rows:
        for t, v in (r.get("atoms") or {}).items():
            if v is None or str(v).strip() == "":
                continue
            counts[f"{t}={v}"] = counts.get(f"{t}={v}", 0) + 1
            per_type.setdefault(str(t), {})[str(v)] = per_type.get(str(t), {}).get(str(v), 0) + 1
    # The most frequent value of each atom type is the reference level (not encoded), so every
    # β reads "vs the tenant's usual hook/CTA" and the design is not collinear with the intercept.
    reference = {t: max(vals.items(), key=lambda kv: (kv[1], kv[0]))[0] for t, vals in per_type.items()}
    atom_feats = sorted(k for k, c in counts.items()
                        if c >= MIN_ATOM_SUPPORT and reference.get(k.split("=", 1)[0]) != k.split("=", 1)[1])

    segments = sorted({str(r.get("segment")) for r in rows if r.get("segment")})
    seg_feats = [f"segment={s}" for s in segments[1:]]  # first segment is the reference level

    names = atom_feats + ["booking_distance", "tz_known"] + seg_feats
    idx = {n: i for i, n in enumerate(names)}

    X = np.zeros((len(rows), len(names)), dtype=float)
    y = np.zeros(len(rows), dtype=float)
    for i, r in enumerate(rows):
        for t, v in (r.get("atoms") or {}).items():
            key = f"{t}={v}"
            if key in idx:
                X[i, idx[key]] = 1.0
        dist = r.get("booking_distance_h")
        X[i, idx["booking_distance"]] = (float(dist) / DISTANCE_SCALE_H) if dist is not None else 0.0
        X[i, idx["tz_known"]] = 1.0 if r.get("tz_known") else 0.0
        seg = f"segment={r.get('segment')}" if r.get("segment") else None
        if seg in idx:
            X[i, idx[seg]] = 1.0
        y[i] = 1.0 if r.get("showed") else 0.0
    return X, y, names


def _sigmoid(z: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-np.clip(z, -30, 30)))


def fit_noshow_attribution(rows: list[dict], l2: float = 1.0, max_iter: int = 50, tol: float = 1e-6) -> dict:
    """Ridge logistic regression via IRLS. Returns coefficients with SE and z, plus no-show drivers."""
    n = len(rows)
    if n < MIN_ROWS:
        return {"fitted": False, "n": n, "reason": f"need >= {MIN_ROWS} resolved bookings, have {n}",
                "coefficients": [], "drivers": []}
    X, y, names = encode_features(rows)
    showed = int(y.sum())
    if showed == 0 or showed == n:
        return {"fitted": False, "n": n, "reason": "no variation in outcome",
                "coefficients": [], "drivers": []}

    Xd = np.hstack([np.ones((n, 1)), X])          # intercept first
    p = Xd.shape[1]
    penalty = np.full(p, l2)
    penalty[0] = 0.0                              # intercept unpenalised
    w = np.zeros(p)
    converged = False
    for _ in range(max_iter):
        mu = _sigmoid(Xd @ w)
        W = mu * (1.0 - mu)
        grad = Xd.T @ (y - mu) - penalty * w
        H = (Xd * W[:, None]).T @ Xd + np.diag(penalty)
        try:
            step = np.linalg.solve(H, grad)
        except np.linalg.LinAlgError:
            step = np.linalg.lstsq(H, grad, rcond=None)[0]
        w = w + step
        if np.max(np.abs(step)) < tol:
            converged = True
            break

    mu = _sigmoid(Xd @ w)
    W = mu * (1.0 - mu)
    H = (Xd * W[:, None]).T @ Xd + np.diag(penalty)
    try:
        cov = np.linalg.inv(H)
    except np.linalg.LinAlgError:
        cov = np.linalg.pinv(H)
    se = np.sqrt(np.clip(np.diag(cov), 0.0, None))

    coefs = []
    for j, name in enumerate(names, start=1):
        beta = float(w[j])
        s = float(se[j]) if se[j] > 0 else float("inf")
        z = beta / s if math.isfinite(s) and s > 0 else 0.0
        coefs.append({"feature": name, "beta": round(beta, 4), "se": round(s, 4) if math.isfinite(s) else None,
                      "z": round(z, 3), "odds_ratio": round(math.exp(beta), 4)})

    drivers = no_show_drivers(coefs)
    return {
        "fitted": True,
        "converged": converged,
        "n": n,
        "showed": showed,
        "show_rate": round(showed / n, 4),
        "intercept": round(float(w[0]), 4),
        "coefficients": coefs,
        "drivers": drivers,
        "model_version": "ridge_irls_v1",
    }


def no_show_drivers(coefficients: list[dict], beta_threshold: float = DRIVER_BETA_THRESHOLD,
                    z_threshold: float = 1.64) -> list[dict]:
    """Atoms whose presence lowers P(show) with at least 90 % one-sided confidence."""
    out = []
    for c in coefficients:
        if "=" not in c["feature"] or c["feature"].startswith("segment="):
            continue
        if c["beta"] <= beta_threshold and abs(c.get("z", 0.0)) >= z_threshold:
            atom_type, atom_value = c["feature"].split("=", 1)
            out.append({"atom_type": atom_type, "atom_value": atom_value, "beta": c["beta"],
                        "odds_ratio": c["odds_ratio"], "z": c["z"]})
    out.sort(key=lambda d: d["beta"])
    return out


def atom_noshow_beta(result: dict, atom_type: str, atom_value: str) -> float | None:
    """Look up an atom's β from a fit result (for the clickbait detector)."""
    key = f"{atom_type}={atom_value}"
    for c in result.get("coefficients", []):
        if c["feature"] == key:
            return c["beta"]
    return None


def predict_show_probability(result: dict, row: dict) -> float | None:
    """P(show) for one booking under the fitted model (None if not fitted)."""
    if not result.get("fitted"):
        return None
    betas: dict[str, float] = {c["feature"]: c["beta"] for c in result["coefficients"]}
    z = result["intercept"]
    for t, v in (row.get("atoms") or {}).items():
        z += betas.get(f"{t}={v}", 0.0)
    dist = row.get("booking_distance_h")
    z += betas.get("booking_distance", 0.0) * ((float(dist) / DISTANCE_SCALE_H) if dist is not None else 0.0)
    z += betas.get("tz_known", 0.0) * (1.0 if row.get("tz_known") else 0.0)
    if row.get("segment"):
        z += betas.get(f"segment={row['segment']}", 0.0)
    return float(_sigmoid(np.array([z]))[0])
