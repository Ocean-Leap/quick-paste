#!/usr/bin/env bash
#
# quick-paste guided setup.
#
# Run it from the project folder:   ./setup.sh
#
# It checks Docker, generates the secrets for you, asks how you want to sign in,
# writes .env, and starts quick-paste. No prior config knowledge needed.
#
# Env hooks (for testing / advanced use):
#   QP_SETUP_NO_START=1   configure only; don't run `docker compose up`

set -euo pipefail
cd "$(dirname "$0")"

# ---- pretty output ----
if [ -t 1 ]; then
  b=$(printf '\033[1m'); grn=$(printf '\033[32m'); ylw=$(printf '\033[33m')
  red=$(printf '\033[31m'); rst=$(printf '\033[0m')
else b=""; grn=""; ylw=""; red=""; rst=""; fi
step() { printf '\n%s==>%s %s\n' "$b" "$rst" "$*"; }
ok()   { printf '  %s✓%s %s\n' "$grn" "$rst" "$*"; }
warn() { printf '  %s!%s %s\n' "$ylw" "$rst" "$*"; }
die()  { printf '\n%s✗ %s%s\n' "$red" "$*" "$rst" >&2; exit 1; }

step "quick-paste setup"
echo "This configures and starts quick-paste on this computer."

# ---- 1. preflight ----
step "Checking Docker"
command -v docker >/dev/null 2>&1 || die \
  "Docker isn't installed. Get Docker Desktop from https://www.docker.com/products/docker-desktop and run ./setup.sh again."
if docker compose version >/dev/null 2>&1; then COMPOSE="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then COMPOSE="docker-compose"
else die "Docker Compose isn't available. Update Docker Desktop and try again."; fi
docker info >/dev/null 2>&1 || die "Docker is installed but not running. Start Docker Desktop, wait for it to finish loading, and run ./setup.sh again."
ok "Docker is ready"

# ---- 2. reuse existing config? ----
CONFIGURE=1
if [ -f .env ]; then
  warn "A .env already exists."
  printf "  Reconfigure it (overwrite)? [y/N] "; read -r ans
  case "${ans:-}" in y|Y|yes) ;; *) CONFIGURE=0; echo "  Keeping your existing .env." ;; esac
fi

# writes KEY=value into .env, replacing an existing (uncommented) line or appending.
# Escapes $ as $$ so Compose passes it through literally (a bare $ in a .env
# value is interpreted as a variable and would silently mangle a password).
set_kv() {
  local key="$1" val="$2" tmp found=0
  val=${val//\$/\$\$}
  tmp=$(mktemp)
  while IFS= read -r line || [ -n "$line" ]; do
    if [ "${line#\#}" = "$line" ] && [ "${line%%=*}" = "$key" ]; then
      printf '%s=%s\n' "$key" "$val" >>"$tmp"; found=1
    else printf '%s\n' "$line" >>"$tmp"; fi
  done <.env
  [ "$found" = 1 ] || printf '%s=%s\n' "$key" "$val" >>"$tmp"
  mv "$tmp" .env
}

gen_secret() {
  openssl rand -base64 24 2>/dev/null \
    || LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32
}

if [ "$CONFIGURE" = 1 ]; then
  [ -f .env.example ] || die "Run this from the quick-paste folder (.env.example not found)."

  step "How do you want to sign in?"
  echo "  1) Password  — easiest; for your home Wi-Fi, no accounts needed"
  echo "  2) Google    — for a public web address; needs a Google OAuth client"
  printf "  Choose [1]: "; read -r mode; mode="${mode:-1}"

  auth_mode="local"; local_pw=""; g_id=""; g_secret=""; allowed=""
  public_url="http://localhost:8080"; lan_https_only="true"

  if [ "$mode" = "2" ]; then
    auth_mode="google"; lan_https_only="false"
    step "Google sign-in"
    echo "  Create an OAuth client (Web application) at https://console.cloud.google.com,"
    echo "  with the redirect URI  <your address>/api/auth/callback."
    printf "  Your public address (e.g. https://paste.example.com): "; read -r public_url
    printf "  Google client ID: "; read -r g_id
    printf "  Google client secret: "; read -r g_secret
    printf "  Your email (becomes the admin): "; read -r allowed
  else
    step "Set a password"
    while [ -z "$local_pw" ]; do
      printf "  Choose a password: "; read -rs local_pw; echo
      [ -n "$local_pw" ] || warn "Password can't be empty."
    done
  fi

  step "Writing configuration"
  cp .env.example .env
  set_kv QP_DB_PASS "$(gen_secret)"
  set_kv QP_SESSION_SECRET "$(gen_secret)"
  set_kv QP_AUTH_MODE "$auth_mode"
  set_kv QP_LOCAL_PASSWORD "$local_pw"
  set_kv QP_GOOGLE_CLIENT_ID "$g_id"
  set_kv QP_GOOGLE_CLIENT_SECRET "$g_secret"
  set_kv QP_ALLOWED_EMAILS "$allowed"
  set_kv QP_PUBLIC_URL "$public_url"
  set_kv QP_LAN_HTTPS_ONLY "$lan_https_only"
  ok "Secrets generated and saved to .env (you never had to touch them)"
fi

# ---- 3. start ----
if [ -n "${QP_SETUP_NO_START:-}" ]; then
  step "Configured. Skipping start (QP_SETUP_NO_START set)."; exit 0
fi

step "Starting quick-paste"
if grep -q '^QP_SERVER_IMAGE=' .env 2>/dev/null && [ -n "$(grep '^QP_SERVER_IMAGE=' .env | cut -d= -f2-)" ]; then
  echo "  Pulling the prebuilt image…"
  $COMPOSE up -d
else
  echo "  First run builds the app — this can take a few minutes. ☕"
  $COMPOSE up -d --build
fi

# ---- 4. wait for health ----
http_port=$(grep -E '^QP_HTTP_PORT=' .env | cut -d= -f2-); http_port="${http_port:-8080}"
https_port=$(grep -E '^QP_HTTPS_PORT=' .env | cut -d= -f2-); https_port="${https_port:-8443}"
printf "  Waiting for it to come up"
up=0
for _ in $(seq 1 90); do
  if curl -fsS -o /dev/null "http://localhost:${http_port}/health" 2>/dev/null; then up=1; break; fi
  printf '.'; sleep 2
done
echo
[ "$up" = 1 ] && ok "quick-paste is running" || warn "It didn't answer yet — check '$COMPOSE logs -f server'."

# ---- 5. next steps ----
auth=$(grep -E '^QP_AUTH_MODE=' .env | cut -d= -f2-)
lan_ip=$( { ipconfig getifaddr en0 || ipconfig getifaddr en1 || hostname -I | awk '{print $1}'; } 2>/dev/null || true)
[ -n "$lan_ip" ] || lan_ip="<this-computer's-IP>"

step "Done 🎉"
if [ "$auth" = "google" ]; then
  echo "  Open your address and sign in with Google:"
  echo "      $(grep -E '^QP_PUBLIC_URL=' .env | cut -d= -f2-)"
  echo "  (Make sure it points at this machine — e.g. via a Cloudflare Tunnel:"
  echo "   COMPOSE_PROFILES=tunnel $COMPOSE up -d  — see README.)"
else
  echo "  On this computer:   http://localhost:${http_port}"
  echo
  echo "  From your phone / other devices on the same Wi-Fi, use HTTPS:"
  echo "      1. Trust the certificate once — open this and install it:"
  echo "             http://${lan_ip}:${http_port}/qp-ca.crt"
  echo "         (iOS also: Settings → General → About → Certificate Trust Settings)"
  echo "      2. Then open:   https://${lan_ip}:${https_port}"
  echo "      3. Sign in with the password you chose."
fi
echo
echo "  Stop:     $COMPOSE down"
echo "  Restart:  $COMPOSE up -d"
