"""
MLflow integration wrapper (vision §15.10).
Falls back gracefully to no-op when MLFLOW_TRACKING_URI is not set.
Pure logic — mlflow is an optional dependency (not in base requirements.txt).
"""
from __future__ import annotations
import os
from typing import Any

MLFLOW_TRACKING_URI = os.getenv("MLFLOW_TRACKING_URI", "")
_mlflow = None


def _get_mlflow():
    global _mlflow
    if _mlflow is None and MLFLOW_TRACKING_URI:
        try:
            import mlflow as _m
            _m.set_tracking_uri(MLFLOW_TRACKING_URI)
            _mlflow = _m
        except ImportError:
            pass
    return _mlflow


def log_model_metrics(
    run_name: str,
    metrics: dict[str, float],
    params: dict[str, Any] | None = None,
    tags: dict[str, str] | None = None,
) -> str | None:
    """Log metrics to MLflow. Returns run_id or None when MLflow not configured."""
    ml = _get_mlflow()
    if ml is None:
        return None
    with ml.start_run(run_name=run_name, tags=tags or {}):
        if params:
            ml.log_params(params)
        ml.log_metrics(metrics)
        run = ml.active_run()
        return run.info.run_id if run else None


def log_calibration_result(ece: float, n_total: int, tenant_id: str) -> str | None:
    return log_model_metrics(
        run_name="calibration_monthly",
        metrics={"ece": ece, "n_total": float(n_total)},
        tags={"tenant_id": tenant_id, "type": "calibration"},
    )


def log_model_quality(
    tenant_id: str,
    log_loss: float,
    brier: float,
    ece: float,
    rmse: float,
) -> str | None:
    return log_model_metrics(
        run_name="model_quality",
        metrics={"log_loss": log_loss, "brier": brier, "ece": ece, "rmse": rmse},
        tags={"tenant_id": tenant_id, "type": "model_quality"},
    )
