import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app.services.calibration_service import compute_calibration_report


def test_empty_input():
    result = compute_calibration_report([])
    assert result["ece"] is None
    assert result["buckets"] == []
    assert result["n_total"] == 0


def test_perfect_calibration():
    # score=0.2, converted with prob ~0.2 → ECE should be near 0
    data = [{"score": 0.2, "converted": True}] * 20 + \
           [{"score": 0.2, "converted": False}] * 80
    result = compute_calibration_report(data)
    assert result["ece"] is not None
    assert result["ece"] < 0.05, f"Expected near-zero ECE, got {result['ece']}"
    assert result["n_total"] == 100


def test_poor_calibration():
    # All predict 0.9 but only 50% convert → ECE = ~0.4
    data = [{"score": 0.9, "converted": True}] * 50 + \
           [{"score": 0.9, "converted": False}] * 50
    result = compute_calibration_report(data)
    assert result["ece"] > 0.1, f"Expected high ECE, got {result['ece']}"


def test_bucket_count():
    data = [{"score": i * 0.1, "converted": False} for i in range(10)]
    result = compute_calibration_report(data, n_buckets=5)
    # With n_buckets=5, bucket_size=0.2
    # scores 0.0,0.1 → bucket 0; 0.2,0.3 → bucket 1; etc.
    assert len(result["buckets"]) <= 5


def test_calibrated_flag():
    # score=0.5, converted 50% → gap < 0.05 → calibrated=True
    data = [{"score": 0.5, "converted": True}] * 50 + \
           [{"score": 0.5, "converted": False}] * 50
    result = compute_calibration_report(data)
    assert len(result["buckets"]) == 1
    bucket = result["buckets"][0]
    assert bucket["calibrated"] is True
    assert bucket["gap"] < 0.05


def test_note_field():
    data = [{"score": 0.3, "converted": True}]
    result = compute_calibration_report(data)
    assert "ECE < 0.05" in result["note"]
    assert "vision §6.3a" in result["note"]
