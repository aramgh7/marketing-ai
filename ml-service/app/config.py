from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str = "postgresql+asyncpg://mai:mai_local@localhost:5432/marketingai"
    redis_url: str = "redis://localhost:6379/0"
    anthropic_api_key: str = ""
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

    class Config:
        env_file = ".env"


settings = Settings()
