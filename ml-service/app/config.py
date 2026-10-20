from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str = "postgresql+asyncpg://mai:mai_local@localhost:5432/marketingai"
    redis_url: str = "redis://localhost:6379/0"
    anthropic_api_key: str = ""
    # Claude model ids (one place to bump; audit §16: no stale ids scattered across routers)
    llm_model_fast: str = "claude-haiku-5-5"       # classification, sentiment, VoC extraction
    llm_model_default: str = "claude-sonnet-5-5"   # content generation, briefs
    llm_model_vision: str = "claude-opus-5-5"      # Creative DNA: image frames + transcript
    openai_api_key: str = ""
    platform_api_url: str = "http://localhost:8080"
    internal_api_key: str = "local-internal-key"
    # vision §16.1: for B2B clients with GHL integration, FORM_SUBMIT should not reward Thompson
    # (reward comes from call_answered/meeting_attended webhooks instead).
    # Set REWARD_ON_FORM_SUBMIT=false in production for B2B tenants.
    reward_on_form_submit: bool = True
    # Phase 4: video pipeline
    whisper_model: str = "whisper-1"
    video_output_dir: str = "/tmp/marketing-ai/renders"
    # R2 storage (optional — if empty, saves to video_output_dir)
    r2_account_id: str = ""
    r2_access_key_id: str = ""
    r2_secret_access_key: str = ""
    r2_bucket: str = "marketing-ai-assets"
    r2_public_url: str = ""
    # Roadmap 9-16: AI video providers (empty key → MOCK provider; see services/ai_video_client.py)
    heygen_api_key: str = ""
    synthesia_api_key: str = ""
    runway_api_key: str = ""
    ai_video_poll_interval_sec: float = 15.0
    ai_video_max_polls: int = 80          # 80 × 15 s = 20 min before a job is marked FAILED (timeout)
    # Roadmap 9-14: text-to-speech. Provider "openai" uses openai_api_key (tts-1); "elevenlabs"
    # needs elevenlabs_api_key; without keys the stub writes a silent WAV.
    tts_provider: str = "openai"
    tts_voice: str = "alloy"
    elevenlabs_api_key: str = ""

    class Config:
        env_file = ".env"


settings = Settings()
