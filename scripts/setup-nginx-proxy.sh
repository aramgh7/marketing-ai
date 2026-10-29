#!/bin/bash
# Sets up nginx reverse proxy for marketing-ai on the shared Lightsail server.
# Must run as root: sudo bash scripts/setup-nginx-proxy.sh
set -e

NGINX_CONF=/home/ubuntu/app/implementation/nginx/nginx.conf
ENV_FILE=/home/ubuntu/marketing-ai/.env.prod
MAI_HOST=mai.100.62.250.209.nip.io

echo "=== 1. Patching nginx.conf ==="
if grep -q "$MAI_HOST" "$NGINX_CONF"; then
    echo "nginx block already present, skipping"
else
    python3 - << 'PYEOF'
import sys
conf = '/home/ubuntu/app/implementation/nginx/nginx.conf'
host = 'mai.100.62.250.209.nip.io'
block = """
    server {
        listen 80;
        server_name """ + host + """;
        location /api/ {
            proxy_pass         http://mai-platform-api:8080;
            proxy_http_version 1.1;
            proxy_set_header   Host              $host;
            proxy_set_header   X-Real-IP         $remote_addr;
            proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
            proxy_set_header   X-Forwarded-Proto $scheme;
            proxy_read_timeout 300s;
        }
        location / {
            proxy_pass         http://mai-frontend:3000;
            proxy_http_version 1.1;
            proxy_set_header   Host      $host;
            proxy_set_header   X-Real-IP $remote_addr;
        }
    }
"""
t = open(conf).read().rstrip()
if not t.endswith('}'):
    sys.exit('ERROR: nginx.conf does not end with }')
open(conf, 'w').write(t[:-1] + block + '}\n')
print('nginx.conf patched')
PYEOF
fi

echo "=== 2. Reloading nginx ==="
docker kill -s HUP sales-ai-nginx
sleep 1

echo "=== 3. Updating .env.prod ==="
NEW_API_URL="http://${MAI_HOST}/api/v1"
NEW_FE_URL="http://${MAI_HOST}"
sed -i "s|NEXT_PUBLIC_API_URL=.*|NEXT_PUBLIC_API_URL=${NEW_API_URL}|" "$ENV_FILE"
sed -i "s|CORS_ALLOWED_ORIGINS=.*|CORS_ALLOWED_ORIGINS=${NEW_FE_URL}|" "$ENV_FILE"
sed -i "s|PLATFORM_HOSTS=.*|PLATFORM_HOSTS=${MAI_HOST}|" "$ENV_FILE"
grep -E "NEXT_PUBLIC_API_URL|CORS_ALLOWED_ORIGINS|PLATFORM_HOSTS" "$ENV_FILE"

echo "=== 4. Rebuilding frontend (bakes NEXT_PUBLIC_API_URL at build time) ==="
cd /home/ubuntu/marketing-ai
docker compose --env-file .env.prod \
    -f docker-compose.yml \
    -f docker-compose.override.yml \
    up -d --build frontend platform-api

echo "=== Done. Open: http://${MAI_HOST}/ ==="
