# MobiDesk private host agent

Private-only lifecycle API for the single nested pilot VM. It must never be exposed directly to the public internet. Do not bind to `0.0.0.0`, open a public Azure NSG port, or enable Tailscale Funnel.

## Configuration

Set these in a root-owned environment file outside the repository:

- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `MOBIDESK_PILOT_VM_ID`: actual UUID from `public.vms`, not a sample ID
- `MOBIDESK_GUEST_IP=192.168.122.21`
- `MOBIDESK_GUEST_RDP_PORT=3389`
- `MOBIDESK_GUAC_HOST=127.0.0.1`
- `MOBIDESK_GUAC_PORT=8080`
- `MOBIDESK_GUAC_BASE_URL=http://127.0.0.1:8080/guacamole`
- `MOBIDESK_GUAC_USERNAME`: dedicated, non-admin Guacamole session account
- `MOBIDESK_GUAC_PASSWORD`: password for that dedicated account

The dedicated account must have **READ access only to the pilot connection**, no system permissions, no user-management permissions, and no access to other connections. Never use `guacadmin` for these settings. Keep its credentials in the root-owned `/etc/mobidesk/host-agent.env` file (mode 0600); never place them in Flutter, GitHub, or student-visible configuration.

The service verifies the bearer token with Supabase Auth, then checks the student's assignment using that same JWT so RLS applies. It fails closed if the actual pilot VM row and exactly one assignment are missing. Earlier audit found zero VM rows and zero assignments, so lifecycle calls must remain blocked until those records are verified.

## Host privilege boundary

The API runs as the unprivileged `mobidesk-api` account. Install
`mobidesk-vm-control` as a root-owned executable at
`/usr/local/sbin/mobidesk-vm-control` (mode 0755), and install
`mobidesk-api.sudoers.example` as `/etc/sudoers.d/mobidesk-api` only after
checking it with `visudo -cf`. The helper fixes the domain to
`mobidesk-test-01`, accepts exactly one action, and permits only
`domstate`, `start`, and `shutdown`. Do not grant the API user direct
membership in broad libvirt administration groups.

Copy `mobidesk-host-agent.service.example` to
`/etc/systemd/system/mobidesk-host-agent.service`. Create the environment
file at `/etc/mobidesk/host-agent.env` with root ownership and mode 0600.
Create the service account and virtual environment before enabling the unit.
The service binds only to `127.0.0.1:8765`; route it through the existing
private Tailscale Serve setup only after validating the local service.

## API

- `GET /healthz`: minimal health status only
- `POST /api/v1/cloud-pc/session`: validates the Supabase JWT and pilot assignment, then obtains a Guacamole token using the dedicated restricted server-side account
- `GET /api/v1/cloud-pc/status`: authenticated assignment-checked status
- `POST /api/v1/cloud-pc/start`: authenticated idempotent start and readiness polling
- `POST /api/v1/cloud-pc/stop`: authenticated graceful shutdown

The API accepts no VM ID, hostname, port, shell command, or libvirt action from callers. It uses only a fixed domain and allowlisted `virsh` operations. It returns generic errors without host output or infrastructure details. Readiness checks require both the host-local Guacamole TCP port and guest RDP port to accept connections; that does not replace a real end-to-end desktop login test.

## Install on the Azure host

Create `/etc/mobidesk/host-agent.env` with root ownership and mode 0600 before running the installer. It must contain `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `MOBIDESK_PILOT_VM_ID`, `MOBIDESK_GUAC_BASE_URL`, `MOBIDESK_GUAC_USERNAME`, and `MOBIDESK_GUAC_PASSWORD` set to real values. The guest IP/port and Guacamole host/port can use the documented defaults or be explicitly configured. Never commit this environment file.

From this directory, run `sudo ./install.sh`. The installer creates the unprivileged service account, installs the root-owned fixed-domain helper and restricted sudoers rules, installs runtime dependencies, and starts a systemd unit bound to `127.0.0.1:8765`. It does not change Tailscale Serve or any public firewall rules. Inspect `systemctl status mobidesk-host-agent` and `journalctl -u mobidesk-host-agent` if local health verification fails.

## Local validation

Use a dedicated virtual environment, install `requirements-dev.txt`, then run `pytest -q`. For local-only manual inspection, bind Uvicorn to `127.0.0.1` with one worker. Before deployment, configure a least-privilege system service that can access only the required libvirt operations, and add it behind the existing private Tailscale Serve route without replacing the working Guacamole route.
