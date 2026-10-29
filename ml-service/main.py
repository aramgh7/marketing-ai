from contextlib import asynccontextmanager
from fastapi import Depends, FastAPI

from app.security import require_internal_key

from app.routers import health, decide, analyze, diagnostics, brief, glm, simulate, competitive, video, cluster, propensity
from app.routers import burnout, saturation, predict, evolve, graveyard
from app.routers import mmm, attribution
from app.routers import content, quality, compliance, explain
from app.routers import anchors, signals, ope, mmf, persona
from app.routers import taxonomy, voc
from app.routers import media_compliance, cpm
from app.routers import ai_video, geo_lift, portfolio, entropy
from app.routers import circuit_breaker
from app.routers import offer_validity
from app.routers import niche_store, hierarchy, red_flags
from app.routers import model_quality
from app.routers import post_call, investor_archetype, agency, regcf
from app.routers import reward
from app.routers import evi
from app.routers import cvr_collapse
from app.routers import assets


@asynccontextmanager
async def lifespan(app: FastAPI):
    # startup
    yield
    # shutdown


app = FastAPI(
    title="Marketing AI — ML Service",
    version="0.1.0",
    lifespan=lifespan,
)

# Internal service: no browser ever calls it directly, so no CORS middleware (audit §3.4 — it was "*").
# Every router except /health requires X-Internal-API-Key (fail closed, single implementation).
_internal = [Depends(require_internal_key)]

app.include_router(health.router)
app.include_router(decide.router, prefix="/decide", dependencies=_internal)
app.include_router(analyze.router, prefix="/analyze", dependencies=_internal)
app.include_router(diagnostics.router, prefix="/diagnostics", dependencies=_internal)
app.include_router(brief.router, prefix="/brief", dependencies=_internal)
app.include_router(glm.router, prefix="/glm", dependencies=_internal)
app.include_router(simulate.router, prefix="/simulate", dependencies=_internal)
app.include_router(competitive.router, prefix="/competitive", dependencies=_internal)
app.include_router(video.router, prefix="/video", dependencies=_internal)
app.include_router(cluster.router, prefix="/cluster", dependencies=_internal)
app.include_router(propensity.router, prefix="/propensity", dependencies=_internal)
app.include_router(burnout.router, prefix="/burnout", dependencies=_internal)
app.include_router(saturation.router, prefix="/saturation", dependencies=_internal)
app.include_router(predict.router, prefix="/predict", dependencies=_internal)
app.include_router(evolve.router, prefix="/evolve", dependencies=_internal)
app.include_router(graveyard.router, prefix="/graveyard", dependencies=_internal)
app.include_router(mmm.router, prefix="/mmm", dependencies=_internal)
app.include_router(attribution.router, prefix="/attribution", dependencies=_internal)
app.include_router(content.router, prefix="/content", dependencies=_internal)
app.include_router(quality.router, prefix="/quality", dependencies=_internal)
app.include_router(compliance.router, prefix="/compliance", dependencies=_internal)
app.include_router(explain.router, prefix="/explain", dependencies=_internal)
app.include_router(anchors.router, prefix="/anchors", dependencies=_internal)
app.include_router(signals.router, prefix="/signals", dependencies=_internal)
app.include_router(ope.router, prefix="/ope", dependencies=_internal)
app.include_router(mmf.router, prefix="/mmf", dependencies=_internal)
app.include_router(persona.router, prefix="/persona", dependencies=_internal)
app.include_router(taxonomy.router, prefix="/taxonomy", dependencies=_internal)
app.include_router(voc.router, prefix="/voc", dependencies=_internal)
app.include_router(media_compliance.router, prefix="/compliance/media", dependencies=_internal)
app.include_router(cpm.router, prefix="/predict", dependencies=_internal)
app.include_router(ai_video.router, prefix="/ai-video", dependencies=_internal)
app.include_router(geo_lift.router, prefix="/geo-lift", dependencies=_internal)
app.include_router(portfolio.router, prefix="/portfolio", dependencies=_internal)
app.include_router(entropy.router, prefix="/entropy", dependencies=_internal)
app.include_router(circuit_breaker.router, dependencies=_internal)
app.include_router(offer_validity.router, dependencies=_internal)
app.include_router(niche_store.router, dependencies=_internal)
app.include_router(hierarchy.router, dependencies=_internal)
app.include_router(red_flags.router, dependencies=_internal)
app.include_router(model_quality.router, prefix="/model-quality", dependencies=_internal)
app.include_router(post_call.router, prefix="/post-call", dependencies=_internal)
app.include_router(investor_archetype.router, prefix="/investor", dependencies=_internal)
app.include_router(agency.router, prefix="/agency", dependencies=_internal)
app.include_router(regcf.router, dependencies=_internal)
app.include_router(reward.router, prefix="/reward", dependencies=_internal)
app.include_router(evi.router, prefix="/decide/evi", dependencies=_internal)   # roadmap 9-10
app.include_router(cvr_collapse.router, prefix="/signals", dependencies=_internal)   # roadmap 9-8
app.include_router(assets.router, prefix="/api/v1", dependencies=_internal)   # Phase 14 auto-tag
