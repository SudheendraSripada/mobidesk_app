# MobiDesk private host agent

Private-only lifecycle API for the single nested pilot VM. It must never be exposed directly to the public internet. Do not bind to `0.0.0.0`, open a public Azure NSG port, or enable Tailscale Funnel.

## Configuration

Set these in a root-owned environment file outside the repository:

- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `MOBIDESK_PILOT_VM_ID`: actual UUID from `public.vms`, not a sample ID
- `MOBIDESK_LIBVIRT_DOMAIN=mobidesk-test-01`
- `MOBIDESK_GUEST_IP=192.168.122.21`
- `MOBIDESK_GUEST_RDP_PORT=3389`
- `MOBIDESK_GUAC_HOST=127.0.0.1`
- `MOBIDESK_GUAC_PORT=8080`

The service verifies the bearer token with Supabase Auth, then checks the student's assignment using that same JWT so RLS applies. It fails closed if the actual pilot VM row and exactly one assignment are missing. Earlier audit found zero VM rows and zero assignments, so lifecycle calls must remain blocked until those records are verified.

## API

- `GET /healthz`: minimal health status only
- `GET /api/v1/cloud-pc/status`: authenticated assignment-checked status
- `POST /api/v1/cloud-pc/start`: authenticated idempotent start and readiness polling
- `POST /api/v1/cloud-pc/stop`: authenticated graceful shutdown

The API accepts no VM ID, hostname, port, shell command, or libvirt action from callers. It uses only a fixed domain and allowlisted `virsh` operations. It returns generic errors without host output or infrastructure details. Readiness checks require both the host-local Guacamole TCP port and guest RDP port to accept connections; that does not replace a real end-to-end desktop login test.

## Local validation

Use a dedicated virtual environment, install `requirements.txt`, then run `pytest -q`. For local-only manual inspection, bind Uvicorn to `127.0.0.1` with one worker. Before deployment, configure a least-privilege system service that can access only the required libvirt operations, and add it behind the existing private Tailscale Serve route without replacing the working Guacamole route.
