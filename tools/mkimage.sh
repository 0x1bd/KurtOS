#!/usr/bin/env bash
set -euo pipefail

KERNEL="$1"
FLX="$2"
LIMINE_DIR="$3"
OUT="$4"

SIZE_MB=64
ESP_START=2048
SECTOR=512

if [ ! -x "${LIMINE_DIR}/limine" ]; then
    make -C "$LIMINE_DIR" >/dev/null
fi

rm -f "$OUT"
truncate -s "${SIZE_MB}M" "$OUT"

sfdisk --quiet --label gpt "$OUT" <<EOF
${ESP_START},,U,*
EOF

ESP_OFFSET=$((ESP_START * SECTOR))

mformat -i "${OUT}@@${ESP_OFFSET}" -F -v KURTOS ::
mmd -i "${OUT}@@${ESP_OFFSET}" ::/EFI
mmd -i "${OUT}@@${ESP_OFFSET}" ::/EFI/BOOT
mmd -i "${OUT}@@${ESP_OFFSET}" ::/boot
mmd -i "${OUT}@@${ESP_OFFSET}" ::/boot/limine

mcopy -i "${OUT}@@${ESP_OFFSET}" "$KERNEL" ::/boot/kurtos.elf
mcopy -i "${OUT}@@${ESP_OFFSET}" "$FLX" ::/boot/flx.img
mcopy -i "${OUT}@@${ESP_OFFSET}" limine.conf ::/boot/limine/limine.conf
mcopy -i "${OUT}@@${ESP_OFFSET}" "${LIMINE_DIR}/BOOTX64.EFI" ::/EFI/BOOT/BOOTX64.EFI
mcopy -i "${OUT}@@${ESP_OFFSET}" "${LIMINE_DIR}/limine-bios.sys" ::/boot/limine/limine-bios.sys

"${LIMINE_DIR}/limine" bios-install "$OUT" >/dev/null

echo "image: $OUT ($(stat -c%s "$OUT") bytes)"
