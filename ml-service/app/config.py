from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    database_url: str = "postgresql+asyncpg://mai:mai_local@localhost:5432/marketingai"
    redis_url: str = "redis://localhost:6379/0"
    anthropic_api_key: str = ""
    openai_api_key: str = ""
    platform_api_url: str = "http://localhost:8080"
    internal_api_key: str = "local-internal-key"

    class Config:
        env_file = ".env"


settings = Settings()
