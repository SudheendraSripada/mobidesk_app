#!/usr/bin/env bash
# ==============================================================================
# Apache Guacamole Database Schema Initializer
# Generates the PostgreSQL schema from the official Guacamole image
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INIT_DIR="${SCRIPT_DIR}/init"

mkdir -p "${INIT_DIR}"

echo "Extracting Guacamole PostgreSQL initialization SQL..."
docker run --rm guacamole/guacamole:1.5.5 /opt/guacamole/bin/initdb.sh --postgresql > "${INIT_DIR}/01-initdb.sql"

echo "Initialization SQL generated at: ${INIT_DIR}/01-initdb.sql"
echo "You can now run: docker compose up -d"
