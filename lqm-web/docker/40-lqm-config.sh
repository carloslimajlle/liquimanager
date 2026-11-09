#!/bin/sh
# Runs when the container starts (the official Nginx image runs everything in /docker-entrypoint.d): writes the runtime configuration the
# SPA reads before it starts. Without an identity provider (no LQM_OIDC_AUTHORITY) the app runs as in local development.
set -eu

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

authority="$(json_escape "${LQM_OIDC_AUTHORITY:-}")"
client="$(json_escape "${LQM_OIDC_CLIENT_ID:-lqm-web}")"

cat > /usr/share/nginx/html/config.js <<EOF
window.__LQM_CONFIG__ = { VITE_OIDC_AUTHORITY: "${authority}", VITE_OIDC_CLIENT_ID: "${client}" };
EOF
