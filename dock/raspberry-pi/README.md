# MobiDesk Raspberry Pi 4B USB Dock

Turn your Raspberry Pi 4B into a zero-latency USB Dock for MobiDesk. The Pi acts as a **USB Host** via any of its USB-A ports (blue USB 3.0 ports recommended), connects directly to an Android phone via a standard USB cable, initiates the Android Open Accessory (AOA 2.0) handshake, detects the native resolution of your HDMI monitor, and hardware-decodes H.264 video directly to the monitor with NO black bars.

---

## 1. Hardware Requirements

- **Raspberry Pi 4B** (2GB, 4GB, or 8GB RAM).
- **Micro-HDMI to HDMI cable** connected to HDMI0 (the port closest to the USB-C power input).
- **USB-A to USB-C cable** connecting the Pi USB-A port to the student's Android phone.
- **MicroSD Card** (16GB+ recommended) with **Raspberry Pi OS Lite (64-bit / Bookworm)** installed.
- Official 5V / 3A USB-C Power Supply for the Pi.

---

## 2. Raspberry Pi OS Lite Setup Steps

### Step 1: Flash Raspberry Pi OS Lite
1. Download [Raspberry Pi Imager](https://www.raspberrypi.com/software/).
2. Choose OS: `Raspberry Pi OS (other)` -> `Raspberry Pi OS Lite (64-bit)`.
3. Click the gear icon to configure:
   - Set hostname (e.g. `mobidesk-dock`).
   - Set username & password (e.g. `pi` / your secure password).
   - Configure Wi-Fi or use Ethernet.
   - Enable SSH.
4. Flash the MicroSD card and boot the Raspberry Pi.

### Step 2: Configure Display Settings in `/boot/firmware/config.txt`
To ensure clean KMS hardware output without window manager interference:
```bash
sudo nano /boot/firmware/config.txt
```
Ensure the following lines are present:
```ini
# Enable Full KMS graphics driver for hardware V4L2 decoding and kmssink output
dtoverlay=vc4-kms-v3d
gpu_mem=128
hdmi_force_hotplug=1
```
Reboot the Pi:
```bash
sudo reboot
```

### Step 3: Run the MobiDesk Dock Installer
Copy or clone the repository onto the Pi, then run:
```bash
cd dock/raspberry-pi
sudo bash install.sh
```

The script will automatically:
1. Install GStreamer with hardware acceleration (`v4l2h264dec`, `kmssink`, `libav`).
2. Install Python USB and GStreamer bindings (`python3-usb`, `python3-gi`, `python3-gst-1.0`).
3. Set up udev rules for plugdev access.
4. Install and enable the `mobidesk-dock.service` systemd unit.

---

## 3. Verifying and Testing the Dock

### Check Service Status
```bash
sudo systemctl status mobidesk-dock.service
```

### View Live Logs
```bash
journalctl -u mobidesk-dock.service -f
```

Sample output when a phone is plugged in:
```text
(MobiDeskDock) Target Monitor: 1920x1080@60 FPS
(MobiDeskDock) Found USB device 18d1:4ee1. Performing AOA 2.0 handshake...
(MobiDeskDock) Android device supports AOA version: 2
(MobiDeskDock) AOA start request sent. Waiting for phone re-enumeration...
(MobiDeskDock) Starting active streaming session over USB AOA 2.0...
(MobiDeskDock) Attempting GStreamer pipeline: appsrc name=src is-live=true format=time block=false ! h264parse ! v4l2h264dec capture-io-mode=4 ! kmssink sync=false
(MobiDeskDock) GStreamer pipeline successfully launched.
(MobiDeskDock) Sent TYPE_DISPLAY_INFO (1920x1080@60) to phone.
(MobiDeskDock) Sent initial keyframe request heartbeat.
```

---

## 4. Manual Execution for Debugging

If you want to run the dock script manually in the terminal:
```bash
sudo systemctl stop mobidesk-dock.service
sudo python3 /usr/local/bin/mobidesk_dock.py
```
Press `Ctrl+C` to stop.
