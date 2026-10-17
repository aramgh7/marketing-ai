from app.services.form_check_service import should_resume_after_passes, check_form_health


def test_should_resume_after_3():
    assert should_resume_after_passes(3) is True


def test_should_not_resume_after_2():
    assert should_resume_after_passes(2) is False


def test_should_resume_custom_threshold():
    assert should_resume_after_passes(5, required_passes=5) is True
    assert should_resume_after_passes(4, required_passes=5) is False


def test_form_health_unreachable_url():
    result = check_form_health("http://localhost:19999/nonexistent", timeout_seconds=1.0)
    assert result["ok"] is False
    assert result["status"] is None or isinstance(result["status"], int)
    assert result["error"] is not None
    assert isinstance(result["latency_ms"], float)
