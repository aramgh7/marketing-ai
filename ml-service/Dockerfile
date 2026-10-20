FROM python:3.12-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Roadmap 3-13: headless-browser form check. Optional (~400 MB): build with
#   docker compose build --build-arg INSTALL_PLAYWRIGHT=1 ml-service
# Without it /analyze/form-health mode=browser falls back to the HTTP check.
ARG INSTALL_PLAYWRIGHT=0
RUN if [ "$INSTALL_PLAYWRIGHT" = "1" ]; then \
      pip install --no-cache-dir playwright==1.47.0 && playwright install --with-deps chromium; \
    fi

COPY . .

EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
