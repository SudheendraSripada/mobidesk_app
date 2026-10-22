"""Private, allowlisted lifecycle API for the single MobiDesk pilot VM."""
from __future__ import annotations

import os
import socket
import subprocess
import threading
import time
import uuid
from collections import defaultdict, deque
from typing import Any

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException

app = FastAPI(title="MobiDesk Private Cloud PC API", docs_url=None, redoc_url=None)
_rate_lock = threading.Lock()
_mutations: dict[str, deque[float]] = defaultdict(deque)


def _config(name: str, default: str = "") -> str:
    value = os.getenv(name, default).strip()
    if not value:
        raise HTTPException(status_code=503, detail="Cloud PC service is not configured.")
    return value


def _supabase_url() -> str:
    return _config("SUPABASE_URL").rstrip("/")


def require_student(authorization: str | None = Header(default=None)) -> dict[str, Any]:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Please sign in again.")
    token = authorization[7:].strip()
    if not token:
        raise HTTPException(status_code=401, detail="Please sign in again.")
    try:
        response = httpx.get(
            f"{_supabase_url()}/auth/v1/user",
            headers={"apikey": _config("SUPABASE_ANON_KEY"), "Authorization": f"Bearer {token}"},
            timeout=5.0,
        )
        if response.status_code != 200:
            raise ValueError("invalid session")
        user = response.json()
        user_id = str(user["id"])
        uuid.UUID(user_id)
        return {"sub": user_id, "access_token": token}
    except Exception:
        raise HTTPException(status_code=401, detail="Please sign in again.") from None


def _rest_get(path: str, token: str) -> list[dict[str, Any]]:
    try:
        response = httpx.get(
            f"{_supabase_url()}/rest/v1/{path}",
            headers={"apikey": _config("SUPABASE_ANON_KEY"), "Authorization": f"Bearer {token}"},
            timeout=5.0,
        )
        if response.status_code != 200:
            raise HTTPException(status_code=502, detail="Unable to verify Cloud PC access.")
        payload = response.json()
        if not isinstance(payload, list):
            raise ValueError("unexpected response")
        return payload
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=502, detail="Unable to verify Cloud PC access.") from None


def require_pilot_assignment(claims: dict[str, Any]) -> None:
    student_id = claims["sub"]
    pilot_id = _config("MOBIDESK_PILOT_VM_ID")
    try:
        uuid.UUID(pilot_id)
    except (ValueError, TypeError, AttributeError):
        raise HTTPException(status_code=503, detail="Cloud PC service is not configured.") from None
    access_token = claims["access_token"]
    assignments = _rest_get(
        "student_vm_access?select=vm_id&student_id=eq."
        + student_id + "&limit=2",
        access_token,
    )
    if len(assignments) != 1 or assignments[0].get("vm_id") != pilot_id:
        raise HTTPException(status_code=403, detail="No Cloud PC is assigned to this account.")
    rows = _rest_get("vms?select=id&id=eq." + pilot_id + "&limit=1", access_token)
    if len(rows) != 1 or rows[0].get("id") != pilot_id:
        raise HTTPException(status_code=403, detail="No Cloud PC is assigned to this account.")


def _virsh(action: str) -> str:
    # The root-owned helper fixes the domain name and independently validates
    # exactly one allowlisted action. The API never invokes a shell.
    if action not in {"domstate", "start", "shutdown"}:
        raise HTTPException(status_code=500, detail="Unsupported lifecycle operation.")
    try:
        result = subprocess.run(
            ["sudo", "-n", "/usr/local/sbin/mobidesk-vm-control", action],
            check=False, capture_output=True, text=True, timeout=8,
        )
    except Exception:
        raise HTTPException(status_code=503, detail="Cloud PC control is unavailable.") from None
    if result.returncode != 0:
        raise HTTPException(status_code=503, detail="Cloud PC control is unavailable.")
    return result.stdout.strip().lower()


def _port_open(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=0.7):
            return True
    except OSError:
        return False


def _guest_ready() -> bool:
    try:
        guest_port = int(os.getenv("MOBIDESK_GUEST_RDP_PORT", "3389"))
        # Guacamole is published on host port 8090 in the current pilot setup.
        guac_port = int(os.getenv("MOBIDESK_GUAC_PORT", "8090"))
        if not (1 <= guest_port <= 65535 and 1 <= guac_port <= 65535):
            return False
    except ValueError:
        return False
    # Require an explicitly verified guest IP; do not fall back to an example
    # address from the earlier NAT-network draft.
    guest_ip = _config("MOBIDESK_GUEST_IP")
    guac_host = _config("MOBIDESK_GUAC_HOST", "127.0.0.1")
    return _port_open(guac_host, guac_port) and _port_open(guest_ip, guest_port)


def _rate_limit(student_id: str) -> None:
    now = time.monotonic()
    with _rate_lock:
        recent = _mutations[student_id]
        while recent and now - recent[0] > 60:
            recent.popleft()
        if len(recent) >= 6:
            raise HTTPException(status_code=429, detail="Too many requests. Please wait and try again.")
        recent.append(now)


@app.get("/healthz")
def healthz() -> dict[str, bool]:
    return {"ok": True}


@app.get("/api/v1/cloud-pc/status")
def cloud_pc_status(claims: dict[str, Any] = Depends(require_student)) -> dict[str, Any]:
    require_pilot_assignment(claims)
    state = _virsh("domstate")
    running = state == "running"
    return {"state": "running" if running else ("stopped" if "shut off" in state else "starting"),
            "ready": running and _guest_ready()}


@app.post("/api/v1/cloud-pc/start")
def cloud_pc_start(claims: dict[str, Any] = Depends(require_student)) -> dict[str, Any]:
    require_pilot_assignment(claims)
    _rate_limit(claims["sub"])
    if _virsh("domstate") != "running":
        _virsh("start")
    try:
        timeout_seconds = min(max(int(os.getenv("MOBIDESK_START_TIMEOUT_SECONDS", "90")), 1), 120)
    except ValueError:
        timeout_seconds = 90
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if _virsh("domstate") == "running" and _guest_ready():
            return {"state": "running", "ready": True}
        time.sleep(2)
    raise HTTPException(status_code=504, detail="Cloud PC did not become ready. Please try again.")


@app.post("/api/v1/cloud-pc/stop")
def cloud_pc_stop(claims: dict[str, Any] = Depends(require_student)) -> dict[str, Any]:
    require_pilot_assignment(claims)
    _rate_limit(claims["sub"])
    if _virsh("domstate") == "running":
        _virsh("shutdown")
        return {"state": "stopping", "ready": False}
    return {"state": "stopped", "ready": False}
