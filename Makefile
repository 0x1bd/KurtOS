QEMU  = qemu-system-x86_64
IMAGE = build/kurtos.img
ESP   = build/esp.img

ESP_TYPE = c12a7328-f81f-11d2-ba4b-00a0c93ec93b

ACCEL = $(shell test -w /dev/kvm && echo -enable-kvm -cpu host)

QEMU_FLAGS = -M q35 \
             $(ACCEL) \
             -smp 4 \
             -m 2G \
             -drive if=none,id=stick,format=raw,file=$(IMAGE) \
             -device qemu-xhci,id=xhci \
             -device usb-storage,bus=xhci.0,drive=stick,bootindex=0 \
             -serial stdio \
             -no-reboot

IDE_FLAGS = -M q35 \
            $(ACCEL) \
            -smp 4 \
            -m 2G \
            -drive file=$(IMAGE),format=raw \
            -serial stdio \
            -no-reboot

OVMF = /usr/share/edk2/ovmf/OVMF_CODE.fd

GRADLE_FLAGS = -Pkurtos.release=true
ifeq ($(DEBUG),1)
GRADLE_FLAGS =
endif

.PHONY: all run run-ide run-uefi run-headless debug usb usb-kernel clean

all:
	./gradlew buildImage $(GRADLE_FLAGS)

run: all
	$(QEMU) $(QEMU_FLAGS)

run-ide: all
	$(QEMU) $(IDE_FLAGS)

run-uefi: all
	$(QEMU) $(QEMU_FLAGS) -bios $(OVMF)

run-headless: all
	$(QEMU) $(QEMU_FLAGS) -display none

debug: all
	$(QEMU) $(QEMU_FLAGS) -s -S

usb: all
	@test -n "$(DEV)" || { echo "usage: make usb DEV=/dev/sdX"; exit 1; }
	@test -b "$(DEV)" || { echo "$(DEV) is not a block device"; exit 1; }
	@test "$$(lsblk -ndo TYPE $(DEV))" = disk || { \
		echo "REFUSING: $(DEV) is a $$(lsblk -ndo TYPE $(DEV)), not a whole disk."; \
		echo "The image carries its own partition table, so it must be written to"; \
		echo "the disk (e.g. /dev/sda), not a partition (e.g. /dev/sda1)."; \
		exit 1; }
	@test "$$(lsblk -ndo TRAN $(DEV))" = usb || { \
		echo "REFUSING: $(DEV) is not a USB device (transport: $$(lsblk -ndo TRAN $(DEV)))."; \
		exit 1; }
	@lsblk -o NAME,SIZE,TYPE,TRAN,MODEL,MOUNTPOINTS $(DEV)
	@echo
	@echo "This ERASES ALL OF $(DEV), including the KURTDATA partition:"
	@echo "any savegames and any ROMs you added to it are lost."
	@echo "Back up KURTDATA/saves first if you care about it."
	@echo
	@echo "Press enter to continue, Ctrl-C to abort."
	@read _
	sudo dd if=$(IMAGE) of=$(DEV) bs=4M oflag=sync status=progress
	sudo sync
	@echo "done. now safe to unplug."

usb-kernel: all
	@test -n "$(DEV)" || { echo "usage: make usb-kernel DEV=/dev/sdX"; exit 1; }
	@test -b "$(DEV)" || { echo "$(DEV) is not a block device"; exit 1; }
	@test "$$(lsblk -ndo TYPE $(DEV))" = disk || { \
		echo "REFUSING: $(DEV) is a $$(lsblk -ndo TYPE $(DEV)), not a whole disk."; \
		echo "Pass the disk (e.g. /dev/sda); the ESP is located from its partition table."; \
		exit 1; }
	@test "$$(lsblk -ndo TRAN $(DEV))" = usb || { \
		echo "REFUSING: $(DEV) is not a USB device (transport: $$(lsblk -ndo TRAN $(DEV)))."; \
		exit 1; }
	@part=$$(lsblk -lnpo NAME,PARTTYPE $(DEV) | awk '$$2 == "$(ESP_TYPE)" { print $$1; exit }'); \
	test -n "$$part" || { echo "REFUSING: no EFI system partition on $(DEV). Use 'make usb' first."; exit 1; }; \
	size=$$(sudo blockdev --getsize64 $$part); \
	esp=$$(stat -c%s $(ESP)); \
	test "$$size" -ge "$$esp" || { echo "REFUSING: $$part is $$size bytes, need $$esp."; exit 1; }; \
	if findmnt -rno TARGET $$part >/dev/null; then echo "REFUSING: $$part is mounted. Unmount it first."; exit 1; fi; \
	echo "Updating the kernel on $$part ($(DEV))."; \
	echo "KURTDATA is NOT touched: your saves and ROMs survive."; \
	echo; \
	echo "Press enter to continue, Ctrl-C to abort."; \
	read _; \
	sudo dd if=$(ESP) of=$$part bs=4M oflag=sync status=progress; \
	sudo sync; \
	echo "done. now safe to unplug."

clean:
	./gradlew clean
