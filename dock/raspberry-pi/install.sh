#!/usr/bin/env bash
# ==============================================================================
# MobiDesk Raspberry Pi 4B Dock Installation Script
# Target: Raspberry Pi OS Lite (64-bit / Debian Bookworm)
# ==============================================================================
set -euo pipefail

echo "======================================================"
echo "Installing MobiDesk Raspberry Pi 4B USB Dock Daemon..."
echo "======================================================"

if [ "$EUID" -ne 0 ]; then
  echo "Error: Please run as root (e.g. sudo bash install.sh)"
  exit 1
fi

echo "[1/5] Updating apt repositories..."
apt-get update

echo "[2/5] Installing GStreamer and Python dependencies..."
apt-get install -y \
  python3 \
  python3-pip \
  python3-usb \
  python3-gi \
  python3-gst-1.0 \
  gstreamer1.0-tools \
  gstreamer1.0-plugins-base \
  gstreamer1.0-plugins-good \
  gstreamer1.0-plugins-bad \
  gstreamer1.0-plugins-ugly \
  gstreamer1.0-libav \
  gstreamer1.0-alsa \
  libgstreamer1.0-dev

echo "[3/5] Installing udev rules..."
cp 99-mobidesk-dock.rules /etc/udev/rules.d/99-mobidesk-dock.rules
udevadm control --reload-rules
udevadm trigger

echo "[4/5] Installing dock daemon executable..."
mkdir -p /usr/local/bin
cp mobidesk_dock.py /usr/local/bin/mobidesk_dock.py
chmod +x /usr/local/bin/mobidesk_dock.py

echo "[5/5] Configuring systemd service..."
cp mobidesk-dock.service /etc/systemd/system/mobidesk-dock.service
systemctl daemon-reload
systemctl enable mobidesk-dock.service
systemctl restart mobidesk-dock.service

echo "======================================================"
echo "MobiDesk Dock installation complete!"
echo "Status: systemctl status mobidesk-dock.service"
echo "Logs:   journalctl -u mobidesk-dock.service -f"
echo "======================================================"
