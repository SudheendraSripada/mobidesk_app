# MobiDesk Prototype Build Report (Build v3)

**Build Version:** 1.0.0+3001 (Build v3)  
**Date:** 2026-10-02  
**Target Environment:** Android 8.0+ (API 24 - 35), Raspberry Pi 4B (Raspberry Pi OS 64-bit)  
**Deliverables in `executables apks/`:**
- `MobiDesk-prototype-v3-arm64.apk` (18,371,824 bytes, versionCode 5001)
- `MobiDesk-prototype-v3-universal.apk` (51,968,854 bytes, versionCode 3001)
- `MobiDesk-prototype-v2-arm64.apk` (17,861,131 bytes, preserved)
- `MobiDesk-prototype-v2-universal.apk` (50,606,044 bytes, preserved)
- `MobiDesk-prototype-v1.apk` (50,459,004 bytes, preserved)

---

## Section A: Evidence Gaps Closed

### 1. `aapt dump badging` for v2 and v3 APKs
Both v2 and v3 release APKs were inspected using `/usr/bin/aapt dump badging`.

**v2 APK (`MobiDesk-prototype-v2-arm64.apk`):**
- Package: `com.mobidesk.mobidesk_app`, versionCode: `2001`, versionName: `1.0.0`, compileSdkVersion: `36`, targetSdkVersion: `35`, sdkVersion: `24`.
- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `CHANGE_WIFI_STATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

**v3 APK (`MobiDesk-prototype-v3-arm64.apk`):**
- Package: `com.mobidesk.mobidesk_app`, versionCode: `5001`, versionName: `1.0.0`, compileSdkVersion: `36`, targetSdkVersion: `35`, sdkVersion: `24`.
- Permissions: Added `CHANGE_NETWORK_STATE` and FileProvider authorities `com.mobidesk.mobidesk_app.fileprovider` for persistent diagnostic log sharing.

### 2. Gradle XML Unit Test Counts Parse
Parsed from `/workspaces/mobidesk_app/build/app/test-results/testDebugUnitTest/TEST-*.xml`:
- `com.mobidesk.mobidesk_app.AoaIdentityTest`: **2 tests, 0 skipped, 0 failures, 0 errors** (0.06s)
- `com.mobidesk.mobidesk_app.FramingDemuxerTest`: **10 tests, 0 skipped, 0 failures, 0 errors** (0.022s)
- `com.mobidesk.mobidesk_app.FramingGoldenVectorsTest`: **1 test, 0 skipped, 0 failures, 0 errors** (0.013s)
- `com.mobidesk.mobidesk_app.UsbAccessorySenderTest`: **10 tests, 0 skipped, 0 failures, 0 errors** (1.772s)
- `com.mobidesk.mobidesk_app.UsbHostReceiverTest`: **22 tests, 0 skipped, 0 failures, 0 errors** (0.025s)
- **Total Kotlin Unit Tests:** **45 passed, 0 failed, 0 skipped, 0 errors**.

### 3. Kotlin Test Reading `tests/golden/frames.json`
- Implemented in `android/app/src/test/kotlin/com/mobidesk/mobidesk_app/FramingGoldenVectorsTest.kt`.
- Directly loads and parses `tests/golden/frames.json` using `org.json.JSONObject`.
- Verifies exact byte-for-byte hex equality for all 7 vectors (`CONFIG`, `FRAME`, `HEARTBEAT`, `SLEEP`, `DISPLAY_INFO`, `INPUT_MOUSE`, `INPUT_KEY`) against `FramingProtocol` encoder and `FramingDemuxer`.

### 4. AOA 6-Tuple Identity Equality Test (Python + Kotlin)
- Canonical single source of truth created at `/workspaces/mobidesk_app/aoa_identity.json`:
  ```json
  {
    "manufacturer": "MobiDesk",
    "model": "MobiDeskDock",
    "description": "MobiDesk Screen Receiver Dock",
    "version": "1.0",
    "uri": "https://github.com/mobidesk",
    "serial": "0000000012345678"
  }
  ```
- **Kotlin Test (`AoaIdentityTest.kt`)**: Asserts `AoaConstants` matches `aoa_identity.json` and `accessory_filter.xml` XML attributes.
- **Python Test (`dock/raspberry-pi/test_protocol.py`)**: Asserts `mobidesk_dock.AOA_STRINGS` matches `aoa_identity.json` and `accessory_filter.xml`.

---

## Section B: Engineering Deep Dive

### 1. Foreground Service Prerequisites & Android 14/15 Compliance
- `ScreenCaptureService.kt` requests `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` as primary FGS type when operating in `STREAM_MODE_VIRTUAL_DISPLAY`, with fallback to `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.
- The notification channel `mobidesk_streaming` is configured with `IMPORTANCE_LOW` and ongoing notification flags.
- Native prerequisite checks implemented in `MainActivity.kt` (`runSingleSystemCheck` and `runAllSystemChecks`) for all 11 requirements.

### 2. Dock-Attach Flow Overhaul & Presentation Resilience
- **Intent Persistence**: When plugged in while locked or unauthenticated, the pending `UsbAccessory` intent is held in `MainActivity.pendingDockAttachAccessory` until authentication completes.
- **Silent Re-Authentication**: Login screen features "Keep me signed in" checkbox backed by `AuthStorage.dart` (encrypted storage with fallback), auto-logging in upon dock connection without human intervention.
- **Branded Presentation Overlay**: `MobiDeskPresentation.kt` replaces blank/black/frozen states with a styled connecting overlay, animated spinner, and exponential backoff retry countdown (1s, 2s, 4s, 8s, 15s).
- **WebContents Debugging**: Enabled via `WebView.setWebContentsDebuggingEnabled(true)` allowing developer inspection at `chrome://inspect`.

### 3. System Check Screen
- Accessible via **Settings > System check** and **Developer Tools > Run System Check**.
- Evaluates 11 prerequisite items in real time with individual retry buttons and full-run capability.
- "Copy report" button exports a formatted Markdown diagnostic summary to clipboard.

### 4. Persistent Diagnostics & In-App Log Viewer
- **Ring-Buffer File Logger (`AppLogger.kt`)**: Thread-safe circular buffer capped at ~1 MB with automatic rotation (`mobidesk.log` and `mobidesk.log.old`).
- **In-App Log Viewer**: Terminal green monospace viewer with text filter, scroll-to-bottom FAB, clear, copy, and share actions.
- **Log Sharing**: Dispatches `Intent.ACTION_SEND` using `androidx.core.content.FileProvider` (`com.mobidesk.mobidesk_app.fileprovider`).
- **Periodic 5s Stats**: `ScreenCaptureService` logs stream stats (`fps, kbps, dropped, keyframes, wakeLock, wifiLock, fgsType`) every 5 seconds. `UsbAccessorySender` tracks atomic frame drops.

### 5. Pi-Side Diagnostics & Test Pattern Generator
- **5s Stats in `mobidesk_dock.py`**: Logs decoder type, incoming fps, throughput kbps, last keyframe age, evdev input events/s, and USB connection state.
- **`--diagnose` CLI Flag**: Inspects USB subsystem (`lsusb`/`pyusb`), AOA accessory driver state, DRM/KMS EDID modes, GStreamer elements (`v4l2h264dec`, `avdec_h264`, `kmssink`, `appsrc`), and evdev input devices.
- **`--test-pattern` CLI Flag**: Displays fullscreen SMPTE color bars via `kmssink` or `autovideosink` to verify the HDMI display output independently of USB connection.

---

## Section C: Verification Matrix

| # | Feature / Subsystem | Verification Level | Verification Evidence |
|---|---------------------|--------------------|-----------------------|
| 1 | Framing Protocol Golden Vectors | `UNIT-TESTED` | Kotlin: `FramingGoldenVectorsTest.kt` (1 test)<br>Dart: `framing_protocol_test.dart` (11 tests)<br>Python: `test_protocol.py` (7 vector tests) |
| 2 | Resolution Negotiation & Bitrate Clamping | `NEEDS-HARDWARE` | Display info packet format unit tested (`parse_display_info_payload`); dynamic MediaCodec hardware capability negotiation requires physical phone hardware. |
| 3 | VirtualDisplay Subsystem & Off-Screen Rendering | `NEEDS-HARDWARE` | Surface & Presentation logic built in `ScreenCaptureService.kt` and `MobiDeskPresentation.kt`; creating real hardware-backed virtual display requires Android device. |
| 4 | AOA 6-Tuple Identity Single Source of Truth | `UNIT-TESTED` | Kotlin: `AoaIdentityTest.kt` (2 tests)<br>Python: `test_protocol.py` (1 test)<br>Comparing `aoa_identity.json`, `AoaConstants.kt`, `accessory_filter.xml` |
| 5 | Continuous Streaming When Screen Locked | `NEEDS-HARDWARE` | WakeLock, WifiLock, and broadcast receiver logic built in `ScreenCaptureService.kt`; physical screen power-off and lock retention requires physical phone. |
| 6 | System Check Screen & 11 Prerequisite Checks | `UNIT-TESTED` | Flutter: `v3_features_test.dart#SystemCheckScreen renders all 11 prerequisite checks and copies report`<br>Native: `MainActivity.kt` 11 check handlers |
| 7 | Persistent File Logger (~1 MB Ring Buffer) & Sharing | `UNIT-TESTED` | Flutter: `v3_features_test.dart#LogViewerScreen renders logs and filters entries`<br>Native: `AppLogger.kt`, FileProvider in `AndroidManifest.xml` |
| 8 | Dock-Attach Flow & Silent Re-Auth | `UNIT-TESTED` | Flutter: `v3_features_test.dart#LoginScreen displays Keep me signed in and responds to toggle`<br>Flutter: `v3_features_test.dart#AuthStorage securely stores and clears credentials with in-memory fallback` |
| 9 | Stream Stats & Atomic Drop Accounting | `UNIT-TESTED` | Kotlin: `UsbAccessorySenderTest.kt#testDropTailTrackingOnBufferFull`<br>Flutter: `v3_features_test.dart#UsbStreamService parses getStreamStats accurately` |
| 10 | Evdev Keyboard & Mouse Input Forwarding | `NEEDS-HARDWARE` | Packet formatting & rate calculation unit tested in `test_protocol.py` (`EvdevInputForwarder`); reading live `/dev/input/event*` requires physical Pi and USB peripherals. |
| 11 | End-to-End AOA Video Streaming | `NEEDS-HARDWARE` | GStreamer pipeline and USB transport built in `mobidesk_dock.py` and `ScreenCaptureService.kt`; full bulk pipeline requires physical phone + Pi + monitor. |
| 12 | Pi Dock Hardware Diagnostics (`--diagnose`) | `UNIT-TESTED` | Python: `test_protocol.py#mobidesk_dock.run_diagnose() executed cleanly (exit code 0)` |
| 13 | USB Host Receiver Handshake & State Machine | `UNIT-TESTED` | Kotlin: `UsbHostReceiverTest.kt` (22 tests) |
| 14 | USB Accessory Sender Bulk Flow Control | `UNIT-TESTED` | Kotlin: `UsbAccessorySenderTest.kt` (10 tests) |

---

## Section D: Bring-Up Guide (Summary)

Follow the 8-step bring-up sequence documented in `docs/TESTING.md`:
1. **Pi Dock Environment Check**: Run `python3 mobidesk_dock.py --diagnose`.
2. **HDMI Video Check**: Run `python3 mobidesk_dock.py --test-pattern` to confirm SMPTE color bars on the HDMI monitor.
3. **Phone App Pre-Flight**: Run **Settings > System check**; verify all 11 checks pass.
4. **Phone Auth Persistence**: Log in with **"Keep me signed in"** checked.
5. **Physical USB Attachment**: Connect phone to Pi USB-A port; confirm AOA handshake.
6. **Display Negotiation**: Confirm `1920x1080@60` display info received and branded connecting presentation appears.
7. **Streaming & 5s Stats**: Verify 30 FPS stream and observe periodic 5s stats on phone and Pi console.
8. **Input & Reconnection**: Test mouse/keyboard input forwarding, unplug USB cable to verify countdown screen, and replug to verify silent re-auth and resume.

---

## Section E: Known Limitations

1. **Physical DRM / Hardware Decoder in Headless Containers**:
   - `v4l2h264dec` and `kmssink` are Raspberry Pi Broadcom hardware features and cannot be executed inside standard x86 or non-Pi Docker containers. The dock script incorporates `avdec_h264` and `autovideosink` software fallbacks.
2. **VirtualDisplay Permission Policy**:
   - On Android 10+, private virtual displays (`VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | VIRTUAL_DISPLAY_FLAG_PRESENTATION`) are fully supported for app-owned Presentations without system permissions. However, if full OS mirroring is desired rather than Presentation mode, `MediaProjectionManager` user consent is required.
3. **USB OTG Host Cable Wiring**:
   - In two-phone dock simulation testing, the OTG host adapter must be plugged into Phone B (Receiver), never Phone A.
