QEMU  = qemu-system-x86_64
IMAGE = build/kurtos.img

QEMU_FLAGS = -M q35 \
             -m 512M \
             -drive file=$(IMAGE),format=raw \
             -serial stdio \
             -no-reboot

OVMF = /usr/share/edk2/ovmf/OVMF_CODE.fd

GRADLE_FLAGS = -Pkurtos.release=true
ifeq ($(DEBUG),1)
GRADLE_FLAGS =
endif

.PHONY: all run run-uefi run-headless debug usb clean

all:
	./gradlew buildImage $(GRADLE_FLAGS)

run: all
	$(QEMU) $(QEMU_FLAGS)

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
	@echo "This ERASES ALL OF $(DEV). Press enter to continue, Ctrl-C to abort."
	@read _
	sudo dd if=$(IMAGE) of=$(DEV) bs=4M oflag=sync status=progress
	sudo sync
	@echo "done. now safe to unplug."

clean:
	./gradlew clean
