#!/bin/sh
set -eu
: "${NOERIVA_API_UPSTREAM:=http://control:8080}"
export NOERIVA_API_UPSTREAM
envsubst '${NOERIVA_API_UPSTREAM}' < /app/nginx.conf.template > /tmp/nginx.conf
exec nginx -c /tmp/nginx.conf -g 'daemon off;'
