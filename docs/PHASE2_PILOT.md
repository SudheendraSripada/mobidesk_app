# Phase 2 pilot build and rollout checklist

## Flutter build configuration

Production builds should use build-time configuration. Do not enable `ALLOW_DEMO_MODE` or `ALLOW_SERVER_SETUP` for student builds. These switches are intended only for a developer-controlled test build.

Supply the project URL, the **publishable/anon** Supabase key, the private Tailscale Serve Guacamole URL, and the private lifecycle API origin at build time. Never use or ship the Supabase `service_role` key.

Example in Codespaces after setting the four shell variables from trusted configuration:

```bash
flutter pub get
flutter analyze
flutter test --concurrency=1 --dart-define=ALLOW_DEMO_MODE=true
flutter build apk --debug \
  --dart-define=SUPABASE_URL="$SUPABASE_URL" \
  --dart-define=SUPABASE_ANON_KEY="$SUPABASE_ANON_KEY" \
  --dart-define=GUACAMOLE_BASE_URL="$GUACAMOLE_BASE_URL" \
  --dart-define=CLOUD_PC_API_BASE_URL="$CLOUD_PC_API_BASE_URL"
```

`CLOUD_PC_API_BASE_URL` is the HTTPS origin for the private Tailscale Serve host, not a public endpoint. The API currently expects `/api/v1/cloud-pc/...` at the origin root. The host-side Serve route must be configured and verified separately before this setting can work.

For a temporary developer demo build only, explicitly add `--dart-define=ALLOW_DEMO_MODE=true`. For a developer build that needs the setup UI, explicitly add `--dart-define=ALLOW_SERVER_SETUP=true`. Do not use either flag for student distribution.

## Backend prerequisites before live testing

- Confirm the pilot VM is `win-02` and verify its guest IP remains `172.16.33.138` (the host neighbor table matched the guest's libvirt MAC and TCP port 3389 accepted a connection during the pilot diagnostic).
- Create and verify the one real pilot VM row in `public.vms`; use its actual UUID for `MOBIDESK_PILOT_VM_ID`.
- Create exactly one `student_vm_access` mapping for the authorized pilot student.
- Configure `MOBIDESK_GUEST_IP=172.16.33.138`, `MOBIDESK_GUEST_RDP_PORT=3389`, `MOBIDESK_GUAC_HOST=127.0.0.1`, and `MOBIDESK_GUAC_PORT=8090` in the protected host-agent environment file. Re-verify these values before deployment.
- Install the host agent under a dedicated least-privilege account and verify only `win-02` can be managed.
- Keep the service bound to loopback and add it behind the existing private Tailscale Serve configuration. Do not open a public port and do not enable Funnel.
- Verify `/healthz`, then test status/start/readiness/stop using a real signed-in student's Supabase access token and assigned account.
- Only after those checks, build/install the configured APK and test the remote desktop session end to end.

The lifecycle API checks TCP readiness for guest RDP and host-local Guacamole. That is a readiness signal, not proof that a desktop session has successfully connected.
