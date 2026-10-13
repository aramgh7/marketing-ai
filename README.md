# Marketing AI Platform

AI-powered Meta Ads optimization: Thompson Sampling, Creative DNA, Proxy Value Bidding.

## Services

| Service | Tech | Port |
|---------|------|------|
| `platform-api` | Spring Boot 3.3 / Java 21 | 8080 |
| `ml-service` | FastAPI / Python 3.12 | 8000 |
| `frontend` | Next.js 15 App Router | 3000 |

## Quick start

```bash
# 1. Copy env templates
cp ml-service/.env.example ml-service/.env
cp frontend/.env.local.example frontend/.env.local

# 2. Fill in secrets (JWT_SECRET, API keys, etc.)

# 3. Start all services
docker-compose up --build
```

Services available at:
- Platform API: http://localhost:8080/api/v1/health
- ML Service:   http://localhost:8000/health
- Frontend:     http://localhost:3000

## Local development (without Docker)

### Platform API
```bash
cd platform-api
gradle wrapper          # generate gradlew if needed
./gradlew bootRun
```

### ML Service
```bash
cd ml-service
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --reload
```

### Frontend
```bash
cd frontend
npm install
npm run dev
```

## Docs

Architecture, roadmap, and vision are in `.ai/`.
