# MobiDesk Server Infrastructure Guide

This guide details the complete server deployment on **Ubuntu Server 24.04 LTS**:
1. Running **Apache Guacamole** (guacd + web app + PostgreSQL) via Docker Compose.
2. Enabling **RDP on Windows VMs** (port 3389).
3. Adding a **Guacamole RDP connection** (RDP credentials stored EXCLUSIVELY in Guacamole).
4. Creating a **Guacamole student user** matching their MobiDesk login.
5. Verifying network connectivity with `nc -vz` and REST authentication with `curl`.

---

## 1. Deploying Apache Guacamole on Ubuntu Server 24.04

### Step 1: Install Docker & Docker Compose
```bash
sudo apt update
sudo apt install -y ca-certificates curl gnupg lsb-release

# Install Docker Engine
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

### Step 2: Initialize Database and Start Stack
```bash
cd /path/to/mobidesk_app/server/guacamole

# Generate official Guacamole PostgreSQL schema
chmod +x initdb.sh
./initdb.sh

# Start the stack
docker compose up -d
```

### Step 3: Verify Container Health
```bash
docker compose ps
```
The Guacamole web UI is now accessible at:
`http://<SERVER_IP>:8080/guacamole/`
Default administrator login:
- **Username:** `guacadmin`
- **Password:** `guacadmin`
*(Be sure to change the admin password upon first login!)*

---

## 2. Enabling RDP on a Windows VM

To allow Guacamole to stream the student's Windows desktop:

1. **Enable Remote Desktop**:
   - Open **Settings** -> **System** -> **Remote Desktop**.
   - Toggle **Enable Remote Desktop** to **On**.
   - Click **Confirm**.
2. **Configure Windows Firewall**:
   - Remote Desktop operates on **TCP Port 3389**.
   - Open an elevated PowerShell prompt on the Windows VM:
     ```powershell
     Enable-NetFirewallRule -DisplayGroup "Remote Desktop"
     ```
3. **Verify Network Level Authentication (NLA)**:
   - For highest compatibility with Guacamole guacd, ensure standard NLA or TLS security mode is permitted.
   - If using domain or local user accounts, ensure the student account is added to the **Remote Desktop Users** group:
     ```powershell
     Add-LocalGroupMember -Group "Remote Desktop Users" -Member "StudentUser"
     ```

---

## 3. Adding a Guacamole RDP Connection

**CRITICAL SECURITY PRINCIPLE**: RDP passwords and administrative credentials live **ONLY** in Guacamole. They are NEVER stored in Supabase or exposed to the mobile app.

1. Log into Guacamole web interface (`http://<SERVER_IP>:8080/guacamole/`) as `guacadmin`.
2. Navigate to **Settings** -> **Connections** -> **New Connection**.
3. Fill in the connection parameters:
   - **Name:** `Windows 11 Student Lab 01`
   - **Location:** `ROOT`
   - **Protocol:** `RDP`
4. Under **Network**:
   - **Hostname:** `<WINDOWS_VM_IP>` (e.g. `10.0.1.101`)
   - **Port:** `3389`
5. Under **Authentication**:
   - **Username:** `StudentUser` (or Windows VM account)
   - **Password:** `<VM_SECURE_PASSWORD>`
   - **Security mode:** `Any` (or `NLA`)
   - **Ignore server certificate:** `Check` (useful for self-signed certificates)
6. Under **Display / Performance**:
   - **Color depth:** `16-bit` or `24-bit`
   - **Resize method:** `Display Update` (allows native dynamic resizing to match HDMI monitor resolution)
   - **Enable audio:** `Check`
7. Click **Save**. Note the resulting Connection ID (e.g., `1`), which corresponds to `guac_connection_id` in the Supabase `vms` table.

---

## 4. Creating a Guacamole User per Student

Each student account in Guacamole must match their MobiDesk login username:

1. In Guacamole, go to **Settings** -> **Users** -> **New User**.
2. **Username:** `student@mobidesk.edu` (matching their MobiDesk student email/ID).
3. **Password:** `<STUDENT_PASSWORD>` (matching their MobiDesk password).
4. Under **Permissions**:
   - Do **NOT** grant administrative rights.
5. Under **Connections**:
   - Check the box next to the student's assigned connection (e.g., `Windows 11 Student Lab 01`).
6. Click **Save**.

Now, when the mobile app authenticates against `/api/tokens`, Guacamole verifies the student's credentials and grants access exclusively to their assigned Windows VM.

---

## 5. Connectivity & API Verification Checks

### Check RDP Port Connectivity (`nc -vz`)
From your Ubuntu server running Guacamole, test network visibility to the Windows VM:
```bash
nc -vz 10.0.1.101 3389
```
Expected output:
```text
Connection to 10.0.1.101 3389 port [tcp/ms-wbt-server] succeeded!
```

### Check Guacamole REST Authentication (`curl`)
Test that the student user can authenticate and retrieve an `authToken`:
```bash
curl -s -X POST http://localhost:8080/guacamole/api/tokens \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=student@mobidesk.edu&password=YourStudentPassword" | jq .
```
Expected response:
```json
{
  "authToken": "4B87C1D890E1F42A7C4958066D60DE2B44BAA208D64BC7AE88E4B075677943F2",
  "username": "student@mobidesk.edu",
  "dataSource": "postgresql",
  "availableTransports": []
}
```

If the token is returned, Guacamole is fully ready to serve both Phone Mode and Monitor Mode!
