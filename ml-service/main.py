from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

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

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(health.router)
app.include_router(decide.router, prefix="/decide")
app.include_router(analyze.router, prefix="/analyze")
app.include_router(diagnostics.router, prefix="/diagnostics")
app.include_router(brief.router, prefix="/brief")
app.include_router(glm.router, prefix="/glm")
app.include_router(simulate.router, prefix="/simulate")
app.include_router(competitive.router, prefix="/competitive")
app.include_router(video.router, prefix="/video")
app.include_router(cluster.router, prefix="/cluster")
app.include_router(propensity.router, prefix="/propensity")
app.include_router(burnout.router, prefix="/burnout")
app.include_router(saturation.router, prefix="/saturation")
app.include_router(predict.router, prefix="/predict")
app.include_router(evolve.router, prefix="/evolve")
app.include_router(graveyard.router, prefix="/graveyard")
app.include_router(mmm.router, prefix="/mmm")
app.include_router(attribution.router, prefix="/attribution")
app.include_router(content.router, prefix="/content")
app.include_router(quality.router, prefix="/quality")
app.include_router(compliance.router, prefix="/compliance")
app.include_router(explain.router, prefix="/explain")
app.include_router(anchors.router, prefix="/anchors")
app.include_router(signals.router, prefix="/signals")
app.include_router(ope.router, prefix="/ope")
app.include_router(mmf.router, prefix="/mmf")
app.include_router(persona.router, prefix="/persona")
app.include_router(taxonomy.router, prefix="/taxonomy")
app.include_router(voc.router, prefix="/voc")
app.include_router(media_compliance.router, prefix="/compliance/media")
app.include_router(cpm.router, prefix="/predict")
app.include_router(ai_video.router, prefix="/ai-video")
app.include_router(geo_lift.router, prefix="/geo-lift")
app.include_router(portfolio.router, prefix="/portfolio")
app.include_router(entropy.router, prefix="/entropy")
app.include_router(circuit_breaker.router)
app.include_router(offer_validity.router)
app.include_router(niche_store.router)
app.include_router(hierarchy.router)
app.include_router(red_flags.router)
app.include_router(model_quality.router, prefix="/model-quality")
app.include_router(post_call.router, prefix="/post-call")
app.include_router(investor_archetype.router, prefix="/investor")
app.include_router(agency.router, prefix="/agency")
app.include_router(regcf.router)
app.include_router(reward.router, prefix="/reward")
