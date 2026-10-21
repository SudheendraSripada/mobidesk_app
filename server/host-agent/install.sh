#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this installer with sudo." >&2
  exit 1
fi

SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="/opt/mobidesk-host-agent"
ENV_FILE="/etc/mobidesk/host-agent.env"
SERVICE_FILE="/etc/systemd/system/mobidesk-host-agent.service"
HELPER="/usr/local/sbin/mobidesk-vm-control"
SUDOERS_FILE="/etc/sudoers.d/mobidesk-api"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${ENV_FILE}; create it securely before installing." >&2
  exit 1
fi
if [[ "$(stat -c '%U:%a' "${ENV_FILE}")" != "root:600" ]]; then
  echo "${ENV_FILE} must be owned by root with mode 0600." >&2
  exit 1
fi
for key in SUPABASE_URL SUPABASE_ANON_KEY MOBIDESK_PILOT_VM_ID MOBIDESK_GUAC_BASE_URL MOBIDESK_GUAC_USERNAME MOBIDESK_GUAC_PASSWORD; do
  if ! grep -Eq "^(${key})=.+$" "${ENV_FILE}"; then
    echo "Required non-empty ${key} entry is missing from ${ENV_FILE}." >&2
    exit 1
  fi
done

if ! id -u mobidesk-api >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /usr/sbin/nologin mobidesk-api
fi

if command -v apt-get >/dev/null 2>&1; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y python3-venv curl
fi

install -d -o root -g root -m 0755 "${APP_DIR}"
install -o root -g root -m 0644 "${SOURCE_DIR}/app.py" "${APP_DIR}/app.py"
install -o root -g root -m 0644 "${SOURCE_DIR}/requirements.txt" "${APP_DIR}/requirements.txt"
python3 -m venv "${APP_DIR}/venv"
"${APP_DIR}/venv/bin/pip" install --disable-pip-version-check --requirement "${APP_DIR}/requirements.txt"

install -o root -g root -m 0755 "${SOURCE_DIR}/mobidesk-vm-control" "${HELPER}"
install -o root -g root -m 0440 "${SOURCE_DIR}/mobidesk-api.sudoers.example" "${SUDOERS_FILE}"
visudo -cf "${SUDOERS_FILE}"
install -o root -g root -m 0644 "${SOURCE_DIR}/mobidesk-host-agent.service.example" "${SERVICE_FILE}"

systemctl daemon-reload
systemctl enable --now mobidesk-host-agent.service
sleep 2
curl --fail --silent --show-error http://127.0.0.1:8765/healthz
echo
echo "Installed. API is bound to loopback only; no Tailscale Serve or public listener was changed."
