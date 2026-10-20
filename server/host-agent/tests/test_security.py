import os
import sys
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
import app as api


class ApiSecurityTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(api.app)
        self.claims = {"sub": "d0000000-0000-0000-0000-000000000001", "access_token": "test-token"}
        api.app.dependency_overrides[api.require_student] = lambda: self.claims
        self.addCleanup(api.app.dependency_overrides.clear)
        self.assignment = patch.object(api, "require_pilot_assignment")
        self.assignment_mock = self.assignment.start()
        self.addCleanup(self.assignment.stop)

    def test_health_response_has_no_infrastructure_details(self):
        response = self.client.get("/healthz")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"ok": True})

    def test_status_checks_assignment_before_lifecycle_state(self):
        with patch.object(api, "_virsh", return_value="running") as virsh, patch.object(
            api, "_guest_ready", return_value=True
        ):
            response = self.client.get("/api/v1/cloud-pc/status")
        self.assertEqual(response.status_code, 200)
        self.assignment_mock.assert_called_once_with(self.claims)
        virsh.assert_called_once_with("domstate")
        self.assertEqual(response.json(), {"state": "running", "ready": True})
        self.assertNotIn("hostname", response.json())
        self.assertNotIn("ip", response.json())

    def test_start_does_not_restart_running_guest(self):
        with patch.object(api, "_virsh", return_value="running") as virsh, patch.object(
            api, "_guest_ready", return_value=True
        ), patch.object(api, "_rate_limit"):
            response = self.client.post("/api/v1/cloud-pc/start")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"state": "running", "ready": True})
        self.assertEqual([call.args[0] for call in virsh.call_args_list], ["domstate"])

    def test_stop_requests_graceful_shutdown(self):
        with patch.object(api, "_virsh", side_effect=["running", "shutting down"]) as virsh, patch.object(
            api, "_rate_limit"
        ):
            response = self.client.post("/api/v1/cloud-pc/stop")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"state": "stopping", "ready": False})
        self.assertEqual([call.args[0] for call in virsh.call_args_list], ["domstate", "shutdown"])

    def test_assignment_denial_prevents_lifecycle_command(self):
        self.assignment_mock.side_effect = api.HTTPException(status_code=403, detail="No Cloud PC is assigned to this account.")
        with patch.object(api, "_virsh") as virsh:
            response = self.client.post("/api/v1/cloud-pc/stop")
        self.assertEqual(response.status_code, 403)
        virsh.assert_not_called()


if __name__ == "__main__":
    unittest.main()
