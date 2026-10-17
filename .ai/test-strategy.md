# Marketing AI Platform — Test Strategy

> Version 1.0 — 2026-10-14
> Scope: platform-api (Java/Spring Boot), ml-service (Python/FastAPI/PyMC), frontend (Next.js)
> Guiding rule: test behaviour, not implementation. Tests should catch regressions, not just prove "code runs".

---

## 1. Mandatory Testing Rule

Per CLAUDE.md: **after every feature or bug fix, tests MUST be written and pass before the roadmap is updated.**

Minimum bar per change type:

| Change type | Minimum test |
|-------------|--------------|
| Pure math / algorithm | Unit test with known-value assertions |
| Business logic (service method) | Unit test with mocked dependencies |
| API endpoint | Integration test or curl smoke test |
| Database schema | Flyway migration runs clean + rollback check |
| ML model (PyMC) | `prepare_dataset` unit test + `@pytest.mark.slow` fit test |

---

## 2. Project Structure

```
platform-api/
  src/test/java/com/marketingai/
    service/          ← pure-logic unit tests (no Spring context)
    integration/      ← @SpringBootTest tests (need DB)
    controller/       ← MockMvc tests

ml-service/
  tests/
    test_*.py         ← pytest unit tests
  conftest.py         ← custom marks (slow, integration)

frontend/
  __tests__/          ← Jest / React Testing Library
```

---

## 3. Java / Spring Boot Tests

### 3.1 Unit tests (no Spring context — preferred for pure logic)

Use plain JUnit 5 + AssertJ. No `@SpringBootTest`, no DB, no mocks needed for static/package-private math.

**Pattern:**
```java
class GateEvaluationServiceTest {
    @Test
    void probBetter_equalPriors_nearHalf() {
        double p = GateEvaluationService.probBetter(1, 1, 1, 1);
        assertThat(p).isBetween(0.42, 0.58);
    }
}
```

**Key rules:**
- Monte Carlo tests (probBetter, Thompson sampling) are stochastic — use wide enough bounds (`±0.08`) to not flake on unlucky seeds. Never use exact equality.
- Floating-point budget comparisons: always use `.doubleValue()` before comparing `Float` objects; `0.2f` widens to `0.20000000298...` which breaks `> 0.20` guards.
- Test package: `com.marketingai.service` (same package as source) to access package-private static methods without reflection.

**Already written (Phase 3):**
- `GateEvaluationServiceTest` — probBetter, sampleBeta, sampleGamma (9 tests)
- `CircuitBreakerServiceTest` — erf, bayesianProbCpaTooHigh, bayesianProbRoasBelowBreakeven (9 tests)
- `TwoContourBudgetGuardTest` — validateBudgetStep ≤20% rule (7 tests)

### 3.2 Integration tests (need Spring context + DB)

Use `@SpringBootTest` + Testcontainers (PostgreSQL) when testing:
- Flyway migrations (all V1..Vn run cleanly)
- JPA repositories
- Full request→response cycles via MockMvc

**Not yet written — target for Phase 4.**

Example:
```java
@SpringBootTest
@Testcontainers
class MetaCampaignRepositoryTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    ...
}
```

### 3.3 Run command
```bash
cd platform-api
./gradlew test                             # all tests
./gradlew test --tests "com.marketingai.service.*"  # service unit tests only
```

---

## 4. Python / FastAPI Tests

### 4.1 Fast unit tests (no PyMC, instant)

Test pure data-preparation and business logic that only uses numpy/pandas.
PyMC and ArviZ are imported **lazily** (inside functions), so the module loads without them installed.

**Pattern:**
```python
def test_prepare_dataset_filters_low_impressions():
    df = pd.DataFrame({"creative_id": ["low","ok1","ok2"], "impressions": [10,200,300], ...})
    result = prepare_dataset(df)
    assert result.n_obs == 2
    assert "low" not in result.creative_ids
```

**Key rules:**
- All `@pytest.mark.slow` tests require PyMC + ArviZ installed. Skip in CI until PyMC is in Docker image.
- Register custom marks in `conftest.py` at project root to avoid `PytestUnknownMarkWarning`.
- Use `pandas` + `numpy` only in fast tests — no DB, no HTTP.
- **Phase 4 pattern**: Extract pure logic to `app/services/<name>_service.py` (no FastAPI imports) — test the service module directly. Avoids Pydantic version issues in local Python 3.10 env.

**Already written (Phase 3-1):**
- `tests/test_bayesian_glm.py::TestPrepareDataset` (14 tests, no PyMC needed)
  - Filtering, encoding, clamping, revenue flatten, CVR mask, default fill

**Already written (Phase 4):**
- `tests/test_video.py` (32 tests, no FastAPI needed — imports from `app.services.video_service`)
  - `TestVariationMatrix` (8): 3×1×3=9, 2×1×2=4, subtitle styles, multi-format, required fields, empty inputs
  - `TestMontagePlanStructure` (8): required segments, hook≤3s, duration, scenes recommended_for, validate_montage_plan
  - `TestFfmpegCmdBuilding` (10): ffmpeg first arg, input/output, scale 9:16/4:5, list not string, injection escaping, libx264, duration
  - `TestParseJsonList` (6): plain JSON, markdown fences, no-lang fences, text before array, raises on no array, multi-element

### 4.2 Slow integration tests (real PyMC MCMC, ~20-60s)

Marked `@pytest.mark.slow`. Validate that the model:
1. Compiles without shape errors (pytensor graph)
2. Samples without NaN/Inf
3. Returns finite posteriors

Use minimal data: `draws=50, tune=50, chains=1`.

```bash
pytest -m slow                # run PyMC fit tests (requires pymc installed)
pytest -m "not slow"          # fast tests only (default in CI)
```

**Already written:**
- `tests/test_bayesian_glm.py::TestFitAll` (8 tests, requires pymc + arviz)

### 4.3 API smoke tests (HTTP)

Use `httpx.AsyncClient` with FastAPI's `TestClient` for endpoint testing.
Not yet written — target for Phase 4.

```python
from fastapi.testclient import TestClient
from main import app

def test_glm_fit_returns_run_id(monkeypatch):
    monkeypatch.setattr("app.services.model_updater.run_fit", mock_run_fit)
    client = TestClient(app)
    resp = client.post("/glm/fit", json={"tenant_id": "..."})
    assert resp.status_code == 200
    assert "run_id" in resp.json()
```

### 4.4 Run command
```bash
cd ml-service
python -m pytest tests/ -m "not slow" -v     # fast only (CI)
python -m pytest tests/ -m slow -v           # PyMC tests (requires pymc)
python -m pytest tests/ -v                   # all
```

---

## 5. Simulation Tests

For validating the Thompson Sampling + Gate logic end-to-end without real Meta API:

**Scenario: "Obvious winner should be found within N batches"**
```
Setup:
  - 5 fake ad sets; arm 1 has true_ctr=0.08, others have true_ctr=0.02
  - Simulate N=3 batch cycles: impressions drawn from Poisson(500)
  - clicks drawn from Binomial(impressions, true_ctr)
  
Assert:
  - After 3 cycles, GateEvaluationService kills all arms except arm 1
  - selectWinner() returns arm 1
  - shrunk estimate > group mean
```

Not yet written — target for Phase 5 (after simulation framework exists).

---

## 6. What NOT to Test

- Meta Graph API v20.0 responses — mock at the `MetaApiService` boundary; never hit real API in tests
- Telegram alerts (CircuitBreaker) — mock `RestTemplate` in tests
- PyMC convergence quality — R-hat monitoring is a production concern, not a test assertion
- Exact MCMC samples — they're stochastic; assert properties (finite, bounded), not values

---

## 7. Known Issues and Lessons Learned

### Float→double widening (§6.5 budget guard)
**Bug found**: `(120f - 100f) / 100f = 0.2f` widens to `0.20000000298...` when stored as `double`, causing exactly-20% budgets to fail validation.
**Fix**: call `.doubleValue()` explicitly on `Float` objects before arithmetic; add `1e-7` epsilon to threshold.
**Rule**: Never compare `Float` unboxed results directly against `double` literals. Use `Double.compare()` or explicit casting.

### Monte Carlo test bounds
**Issue**: `probBetter(50,50, 30,70)` has theoretical P≈0.998; asserting `< 0.99` was wrong.
**Rule**: For Monte Carlo assertions, use one-sided bounds (`isGreaterThan`, `isLessThan`) with at least 3σ headroom. Never use two-sided bounds tighter than ±0.05 for stochastic tests.

### PyMC lazy imports
**Issue**: `import pymc as pm` at module level prevents the module from loading without PyMC installed, breaking all 14 fast unit tests.
**Fix**: Move `import pymc as pm` and `import arviz as az` inside the functions that use them (`_ctr_model`, `_cvr_model`, `_aov_model`, `_sample`, `_rhat_max`, `_extract_*`).
**Rule**: Heavy ML libraries (PyMC, TensorFlow, torch) must always be lazily imported inside functions.

### FastAPI / Pydantic test isolation (Phase 4)
**Issue**: Local Python 3.10 environment has old Pydantic installed. Importing `from app.routers.video import ...` triggers FastAPI → Pydantic, which fails with `ImportError: cannot import name 'Undefined' from 'pydantic.fields'`.
**Fix**: Extract all pure logic (FFmpeg cmd building, variation matrix, JSON parsing, stubs) into `app/services/video_service.py` that has zero FastAPI imports. Tests import only from the service module.
**Rule**: Any logic that needs to be unit-tested must live in a service module, not in the router file. Router files are permitted to import from services, never the other way around.

---

## 8. Coverage Targets (Phase 3+)

| Component | Target | Status |
|-----------|--------|--------|
| GateEvaluationService math | 100% core math | ✅ Phase 3 |
| CircuitBreakerService math | 100% core math | ✅ Phase 3 |
| TwoContourService budget guard | 100% | ✅ Phase 3 |
| bayesian_glm.prepare_dataset | 100% | ✅ Phase 3-1 |
| bayesian_glm.fit_all (slow) | shape + finite | ✅ Phase 3-1 |
| PixelPollutionService drift logic | 100% | ✅ Phase 3-11 (14 Java + 8 Python) |
| competitive.py parsing + prompts | 100% | ✅ Phase 3-10 (13 Python) |
| video_service variation matrix | 100% | ✅ Phase 4 (8 tests) |
| video_service FFmpeg cmd building | 100% | ✅ Phase 4 (10 tests) |
| video_service parse_json_list | 100% | ✅ Phase 4 (6 tests) |
| video_service montage plan validation | 100% | ✅ Phase 4 (8 tests) |
| MetaCampaignService CRUD | integration | ☐ Phase 5 |
| ExplorationBudgetService.rankByEvi | unit | ☐ Phase 5 |
| thompson_service.sample_arms | unit | ☐ Phase 5 |
| GateEvaluationService.evaluateCampaign | simulation | ☐ Phase 5 |
