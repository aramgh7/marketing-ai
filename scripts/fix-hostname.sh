#!/bin/bash
# Replace mai.100.62.250.209.nip.io with 100.62.250.209.nip.io everywhere
set -e

OLD=mai.100.62.250.209.nip.io
NEW=100.62.250.209.nip.io
NGINX_CONF=/home/ubuntu/app/implementation/nginx/nginx.conf
ENV_FILE=/home/ubuntu/marketing-ai/.env.prod

echo "=== Patching nginx.conf ==="
sudo sed -i "s|$OLD|$NEW|g" "$NGINX_CONF"
grep "server_name" "$NGINX_CONF" | grep -v "#"

echo "=== Reloading nginx ==="
docker exec sales-ai-nginx nginx -s reload

echo "=== Patching .env.prod ==="
sed -i "s|$OLD|$NEW|g" "$ENV_FILE"
grep -E "NEXT_PUBLIC_API_URL|CORS_ALLOWED_ORIGINS|PLATFORM_HOSTS" "$ENV_FILE"

echo "=== Rebuilding frontend ==="
cd /home/ubuntu/marketing-ai
docker compose --env-file .env.prod \
    -f docker-compose.yml \
    -f docker-compose.override.yml \
    up -d --build frontend

echo "=== Done. Open: http://${NEW}/ ==="
