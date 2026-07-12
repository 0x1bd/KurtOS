#!/usr/bin/env bash
set -euo pipefail

KERNEL="$1"
LIMINE_DIR="$2"
ROMS_DIR="$3"
OUT="$4"
ESP_OUT="$5"

SECTOR=512

IMAGE_SECTORS=1048576

ESP_START=2048
ESP_SECTORS=131072

DATA_START=133120
DATA_SECTORS=915392

DATA_TYPE=EBD0A0A2-B9E5-4433-87C0-68B21B25C673
DATA_LABEL=KURTDATA

if [ ! -x "${LIMINE_DIR}/limine" ]; then
    make -C "$LIMINE_DIR" >/dev/null
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

DATA="${WORK}/data.img"

rm -f "$ESP_OUT"
truncate -s $((ESP_SECTORS * SECTOR)) "$ESP_OUT"
mformat -i "$ESP_OUT" -F -v KURTOS ::
mmd -i "$ESP_OUT" ::/EFI
mmd -i "$ESP_OUT" ::/EFI/BOOT
mmd -i "$ESP_OUT" ::/boot
mmd -i "$ESP_OUT" ::/boot/limine

mcopy -i "$ESP_OUT" "$KERNEL" ::/boot/kurtos.elf
mcopy -i "$ESP_OUT" limine.conf ::/boot/limine/limine.conf
mcopy -i "$ESP_OUT" "${LIMINE_DIR}/BOOTX64.EFI" ::/EFI/BOOT/BOOTX64.EFI
mcopy -i "$ESP_OUT" "${LIMINE_DIR}/limine-bios.sys" ::/boot/limine/limine-bios.sys

truncate -s $((DATA_SECTORS * SECTOR)) "$DATA"
mformat -i "$DATA" -F -c 8 -v "$DATA_LABEL" ::
mmd -i "$DATA" ::/roms
mmd -i "$DATA" ::/saves

roms=0
if [ -d "$ROMS_DIR" ]; then
    for rom in "$ROMS_DIR"/*; do
        [ -f "$rom" ] || continue
        mcopy -i "$DATA" "$rom" "::/roms/$(basename "$rom")"
        roms=$((roms + 1))
    done
fi

rm -f "$OUT"
truncate -s $((IMAGE_SECTORS * SECTOR)) "$OUT"

sfdisk --quiet --label gpt "$OUT" <<EOF
start=${ESP_START}, size=${ESP_SECTORS}, type=U, bootable
start=${DATA_START}, size=${DATA_SECTORS}, type=${DATA_TYPE}, name="${DATA_LABEL}"
EOF

dd if="$ESP_OUT" of="$OUT" bs=$SECTOR seek=$ESP_START conv=notrunc status=none
dd if="$DATA" of="$OUT" bs=$SECTOR seek=$DATA_START conv=notrunc status=none

"${LIMINE_DIR}/limine" bios-install "$OUT" >/dev/null

echo "image: $OUT ($(stat -c%s "$OUT") bytes, ${roms} rom(s) on ${DATA_LABEL})"
