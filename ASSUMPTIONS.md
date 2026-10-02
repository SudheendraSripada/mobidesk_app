# ASSUMPTIONS.md - MobiDesk Prototype Implementation

This document details all technical, architectural, and operational assumptions made during the implementation of the demo-ready MobiDesk prototype APK.

---

## 1. Authentication & Data Layer (Supabase)

1. **Authentication Binding**:
   - The `students` table uses `auth.users(id)` as its primary key (`id UUID PRIMARY KEY REFERENCES auth.users(id)`).
   - Row-Level Security (RLS) is enabled on `students`, `vms`, and `student_vm_access`.
   - A student can only query rows where `auth.uid() = student_id` (or `auth.uid() = id`).
2. **Credential Security**:
   - Windows VM credentials, RDP passwords, and administrative tokens are **NEVER** stored in Supabase or on the mobile device.
   - RDP credentials exist **EXCLUSIVELY** within Apache Guacamole's internal connection configuration in PostgreSQL.
3. **Graceful Unconfigured Fallback**:
   - If `SUPABASE_URL` and `SUPABASE_ANON_KEY` are not configured (or point to placeholder values), the application displays a friendly Setup screen rather than crashing.
   - The app provides an instant "Quick Demo Login" / "Offline Demo Mode" pre-populating realistic student profile and VM data.

---

## 2. Cloud PC Transport Layer (Apache Guacamole)

1. **Guacamole Stack Architecture**:
   - Guacamole runs as a containerized stack on Ubuntu Server 24.04 LTS using `guacd:1.5.5`, `postgres:15-alpine`, and `guacamole:1.5.5`.
   - The REST API endpoint `POST /api/tokens` accepts `application/x-www-form-urlencoded` credentials (`username` and `password`).
   - The HTML5 client is accessed via `${GUACAMOLE_BASE_URL}/#/client/{connectionId}?token={authToken}`.
2. **Student User Mapping**:
   - Each student account in Guacamole matches their MobiDesk username and password.
   - In Guacamole, the student user is granted read permission only to their assigned VM connection ID.
3. **Windows VM & RDP Connectivity**:
   - Windows VMs run RDP on standard port TCP 3389 with firewall permissions enabled.
   - Guacamole handles the RDP protocol translation, audio redirection, and dynamic desktop resizing.

---

## 3. USB AOA 2.0 & Framing Protocol

1. **USB Host/Accessory Roles**:
   - The Raspberry Pi 4B acts as **USB Host** via any of its USB-A ports (USB 3.0 ports recommended).
   - The Android phone acts as **USB Accessory** using Android Open Accessory (AOA 2.0).
   - Handshake sequence: Pi requests protocol version (request 51), sends identification strings (request 52), and triggers accessory mode switch (request 53).
2. **Framing Protocol Extension (`TYPE_DISPLAY_INFO`)**:
   - Protocol header: Exactly 16 bytes big-endian (`0x4D, 0x42`, packet type, flags, payload length, 64-bit ptsUs).
   - In Python (`mobidesk_dock.py`), the header is packed using `>2sBBIQ` (where `Q` denotes the 8-byte unsigned integer timestamp), perfectly matching Kotlin `buffer.putLong(ptsUs)` and Dart `bd.setUint64(8, ptsUs)`.
   - Packet type `5` (`TYPE_DISPLAY_INFO`) carries 12 bytes of big-endian payload: `width` (int32), `height` (int32), `fps` (int32).
   - Sent by the Pi Dock immediately upon connection after querying the HDMI display EDID via DRM/KMS sysfs (`/sys/class/drm/card*-HDMI-*/modes`).
   - Defaults to `1920x1080 @ 60 FPS` if the monitor mode cannot be parsed or if headless.

---

## 4. Monitor Mode & VirtualDisplay Pipeline

1. **Independent Off-Screen Rendering**:
   - In Monitor Mode, Android creates a `VirtualDisplay` at the exact monitor resolution using `DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`.
   - `DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC` is strictly excluded because the Android OS requires the signature-level system permission `android.permission.CAPTURE_VIDEO_OUTPUT`, throwing `SecurityException` for standard 3rd-party applications if included.
   - The `VirtualDisplay` is backed directly by the `MediaCodec` hardware encoder input Surface.
   - An Android `Presentation` (`MobiDeskPresentation`) displays an accelerated `WebView` rendering the Guacamole HTML5 client.
   - Because the `Presentation` renders to the `VirtualDisplay` rather than the primary screen, the phone's status message ("Connected, started streaming") is **NOT** baked into the HDMI monitor image.
2. **Continuous Streaming When Phone Screen is Off**:
   - In `STREAM_MODE_VIRTUAL_DISPLAY`, `ScreenCaptureService` ignores `Intent.ACTION_SCREEN_OFF` broadcasts and does not send `TYPE_SLEEP` to the dock.
   - A CPU Partial WakeLock and Android 14 Foreground Service (`FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`) ensure continuous hardware encoding and USB transmission while the phone screen is locked.
3. **Hardware Fallback Path**:
   - If `DisplayManager.createVirtualDisplay` with `Presentation` fails on a device due to OEM security policies or GPU driver limitations, the app falls back to `MediaProjection` screen mirroring at the monitor's aspect ratio.
   - An explicit notice is displayed alerting the user that phone-lock is unavailable in fallback mode.

---

## 5. Phone Mode (Option A)

1. **Immersive Client & Navigation**:
   - Fullscreen immersive landscape view (`PhoneCloudPcActivity` on Android, with responsive Flutter backup).
   - Hardware-accelerated WebView with DOM storage and JavaScript enabled.
   - Dual-layer input injection: Shortcut helper buttons (`Ctrl`, `Alt`, `Win`, `Esc`, `Tab`) and Right Click dispatch both native Android `KeyEvent`s and synthetic DOM `KeyboardEvent` / `MouseEvent` JavaScript events to ensure the Guacamole HTML5 canvas receives all inputs.
   - Floating translucent toolbar providing soft keyboard toggle, shortcut keys, reconnect, and disconnect controls.

---

## 6. App Quality, Permissions & Network Security

1. **SDK Targets**:
   - `minSdk 24` (Android 7.0 Nougat).
   - `targetSdk 35` (Android 15).
   - `compileSdk 35`.
2. **Android 13 & 14 Compliance**:
   - `POST_NOTIFICATIONS` runtime permission declared for Android 13+.
   - `FOREGROUND_SERVICE_MEDIA_PROJECTION` foreground service type declared for Android 14+.
3. **Network Security**:
   - Cleartext HTTP traffic is disabled globally by default.
   - Cleartext is permitted exclusively for private development LAN IP blocks (`localhost`, `10.0.2.2`, `192.168.0.0/16`, `10.0.0.0/8`, `172.16.0.0/12`) via `network_security_config.xml`.
4. **Developer Tools**:
   - Hidden under a long-press gesture on the MobiDesk logo in the AppBar, preserving all original Phone A (Sender) and Phone B (Receiver) diagnostic interfaces.
