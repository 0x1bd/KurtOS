#!/usr/bin/env bash
# winget install usbipd
set -euo pipefail
cd "$(dirname "$0")/.."

command -v usbip >/dev/null 2>&1 || { echo ">> installing usbip client..."; sudo dnf install -y usbip; }

USBIPD="$(command -v usbipd.exe || true)"
[ -n "$USBIPD" ] || [ ! -x "/mnt/c/Program Files/usbipd-win/usbipd.exe" ] || USBIPD="/mnt/c/Program Files/usbipd-win/usbipd.exe"
[ -n "$USBIPD" ] || { echo "usbipd.exe not found. On Windows run:  winget install usbipd"; exit 1; }
USBIPD_WIN="$(wslpath -w "$USBIPD")"   # e.g. C:\Program Files\usbipd-win\usbipd.exe, for Start-Process

BUSID="${1:-}"
if [ -z "$BUSID" ]; then
  BUSID=$("$USBIPD" list | grep -iE 'storage|speicher|flash|disk|datentr' | head -n1 | awk '{print $1}' || true)
  if [ -z "$BUSID" ]; then
    echo "Couldn't auto-detect a USB stick. Devices seen:"; echo
    "$USBIPD" list
    echo; echo "Re-run with the BUSID, e.g.:  tools/flash-usb.sh 2-4"; exit 1
  fi
  echo ">> using BUSID $BUSID (pass one as an argument to override)"
fi

before=$(lsblk -ndo NAME,TRAN | awk '$2=="usb"{print $1}' | sort)

echo ">> approve the UAC prompt to bind the device..."
powershell.exe -NoProfile -Command "Start-Process -FilePath '$USBIPD_WIN' -Verb RunAs -Wait -ArgumentList 'bind','--force','--busid','$BUSID'"
"$USBIPD" attach --wsl --busid "$BUSID" || true   # tolerate 'already attached' on re-runs

echo -n ">> waiting for the stick"
DEV=""
for _ in $(seq 1 20); do
  now=$(lsblk -ndo NAME,TRAN | awk '$2=="usb"{print $1}' | sort)
  new=$(comm -13 <(printf '%s\n' "$before") <(printf '%s\n' "$now") | head -n1)
  [ -n "$new" ] && { DEV="/dev/$new"; break; }
  echo -n "."; sleep 1
done
echo

if [ -z "$DEV" ]; then
  only=$(lsblk -ndo NAME,TRAN | awk '$2=="usb"{print $1}')
  [ "$(printf '%s\n' "$only" | grep -c .)" = 1 ] && DEV="/dev/$only"
fi
[ -n "$DEV" ] || { echo "No USB disk found. Check:  $USBIPD list  /  lsblk"; exit 1; }

echo ">> stick is $DEV:"
lsblk -o NAME,SIZE,TYPE,TRAN,MODEL "$DEV"
echo

make usb-kernel DEV="$DEV"

"$USBIPD" detach --busid "$BUSID" || true
echo ">> detached $BUSID."
