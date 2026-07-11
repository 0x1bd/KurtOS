QEMU  = qemu-system-x86_64
IMAGE = build/kurtos.img

QEMU_FLAGS = -M q35 \
             -m 512M \
             -drive file=$(IMAGE),format=raw \
             -serial stdio \
             -no-reboot

OVMF = /usr/share/edk2/ovmf/OVMF_CODE.fd

.PHONY: all run run-uefi run-headless debug usb clean

all:
	./gradlew buildImage

run: all
	$(QEMU) $(QEMU_FLAGS)

run-uefi: all
	$(QEMU) $(QEMU_FLAGS) -bios $(OVMF)

run-headless: all
	$(QEMU) $(QEMU_FLAGS) -display none

debug: all
	$(QEMU) $(QEMU_FLAGS) -s -S

usb: all
	@test -n "$(DEV)" || (echo "usage: make usb DEV=/dev/sdX" && exit 1)
	@lsblk -o NAME,SIZE,MODEL,TRAN $(DEV)
	@echo "This ERASES $(DEV). Press enter to continue, Ctrl-C to abort."
	@read _
	sudo dd if=$(IMAGE) of=$(DEV) bs=4M oflag=sync status=progress

clean:
	./gradlew clean
