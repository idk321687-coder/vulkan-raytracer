#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required for the current bootstrap. Install Docker Compose, then run: docker compose up --build" >&2
  exit 1
fi
exec docker compose up --build "$@"
