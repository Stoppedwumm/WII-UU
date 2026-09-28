#!/usr/bin/env python3
"""WII-UU virtual gamepads (Linux).

Creates one virtual Xbox 360 controller per phone GamePad through /dev/uinput, so every
emulator that supports controllers (SDL based: Dolphin, Cemu, PPSSPP, DuckStation, melonDS,
Ryujinx, RPCS3, ...) sees a real pad with analog sticks - no key mapping, no window focus needed.

Reads commands from stdin, one per line:
    create <n>                 make pad n (1..4)
    key <n> <code> <0|1>       button (evdev code)
    abs <n> <code> <value>     axis
    syn <n>                    flush a batch of changes
    destroy <n>
Answers "ok <n>" or "err <n> <reason>" to create. Needs write access to /dev/uinput
(the WII-UU installer adds a udev rule for that). Uses only the standard library.
"""
import fcntl
import os
import struct
import sys
import time

# ioctl request numbers from <linux/uinput.h>
def _IOC(direction, typ, nr, size):
    return (direction << 30) | (size << 16) | (ord(typ) << 8) | nr

def _IOW(typ, nr, size):
    return _IOC(1, typ, nr, size)

def _IO(typ, nr):
    return _IOC(0, typ, nr, 0)

UINPUT_SETUP = struct.Struct("HHHH80sI")          # input_id + name + ff_effects_max = 92 bytes
ABS_SETUP = struct.Struct("HxxiiiiII")            # code, pad, absinfo(value,min,max,fuzz,flat,res) = 28 bytes
UI_SET_EVBIT = _IOW("U", 100, 4)
UI_SET_KEYBIT = _IOW("U", 101, 4)
UI_SET_ABSBIT = _IOW("U", 103, 4)
UI_DEV_SETUP = _IOW("U", 3, UINPUT_SETUP.size)
UI_ABS_SETUP = _IOW("U", 4, ABS_SETUP.size)
UI_DEV_CREATE = _IO("U", 1)
UI_DEV_DESTROY = _IO("U", 2)

EV_SYN, EV_KEY, EV_ABS, SYN_REPORT, BUS_USB = 0, 1, 3, 0, 3
# the buttons an Xbox 360 pad has (xpad driver layout)
KEYS = [0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x13A, 0x13B, 0x13C, 0x13D, 0x13E]
# axis code -> (min, max, fuzz, flat)
AXES = {
    0x00: (-32768, 32767, 16, 128), 0x01: (-32768, 32767, 16, 128),   # left stick
    0x03: (-32768, 32767, 16, 128), 0x04: (-32768, 32767, 16, 128),   # right stick
    0x02: (0, 255, 0, 0), 0x05: (0, 255, 0, 0),                        # triggers (ZL / ZR)
    0x10: (-1, 1, 0, 0), 0x11: (-1, 1, 0, 0),                          # d-pad hat
}
# struct input_event: struct timeval (two native longs) + u16 type + u16 code + s32 value
EVENT = struct.Struct("llHHi")


class Pad:
    def __init__(self, n):
        self.fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
        fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_KEY)
        for k in KEYS:
            fcntl.ioctl(self.fd, UI_SET_KEYBIT, k)
        fcntl.ioctl(self.fd, UI_SET_EVBIT, EV_ABS)
        for code, (lo, hi, fuzz, flat) in AXES.items():
            fcntl.ioctl(self.fd, UI_SET_ABSBIT, code)
            fcntl.ioctl(self.fd, UI_ABS_SETUP, ABS_SETUP.pack(code, 0, lo, hi, fuzz, flat, 0))
        # identify as a wired Xbox 360 controller so SDL's controller database maps it automatically
        name = ("Microsoft X-Box 360 pad" if n == 1 else "Microsoft X-Box 360 pad %d" % n).encode()
        fcntl.ioctl(self.fd, UI_DEV_SETUP, UINPUT_SETUP.pack(BUS_USB, 0x045E, 0x028E, 0x0110, name, 0))
        fcntl.ioctl(self.fd, UI_DEV_CREATE)

    def emit(self, typ, code, value):
        t = time.time()
        os.write(self.fd, EVENT.pack(int(t), int((t % 1) * 1e6), typ, code, value))

    def close(self):
        try:
            fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        finally:
            os.close(self.fd)


def selftest():
    """Prints the ioctl numbers and struct sizes so they can be checked against <linux/uinput.h>."""
    print("UI_SET_EVBIT=%#x UI_SET_KEYBIT=%#x UI_SET_ABSBIT=%#x UI_DEV_SETUP=%#x UI_ABS_SETUP=%#x "
          "UI_DEV_CREATE=%#x UI_DEV_DESTROY=%#x" % (UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_ABSBIT, UI_DEV_SETUP,
                                                   UI_ABS_SETUP, UI_DEV_CREATE, UI_DEV_DESTROY))
    print("sizeof uinput_setup=%d uinput_abs_setup=%d input_event=%d" % (UINPUT_SETUP.size, ABS_SETUP.size, EVENT.size))


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        selftest()
        return
    pads = {}
    out = sys.stdout
    for line in sys.stdin:
        p = line.split()
        if not p:
            continue
        try:
            n = int(p[1]) if len(p) > 1 else 0
            if p[0] == "create":
                if n not in pads:
                    pads[n] = Pad(n)
                out.write("ok %d\n" % n)
                out.flush()
            elif p[0] == "key" and n in pads:
                pads[n].emit(EV_KEY, int(p[2]), int(p[3]))
            elif p[0] == "abs" and n in pads:
                pads[n].emit(EV_ABS, int(p[2]), int(p[3]))
            elif p[0] == "syn" and n in pads:
                pads[n].emit(EV_SYN, SYN_REPORT, 0)
            elif p[0] == "destroy" and n in pads:
                pads.pop(n).close()
        except (OSError, ValueError, IndexError) as e:
            if p[0] == "create":
                out.write("err %s %s\n" % (p[1] if len(p) > 1 else "?", e))
                out.flush()
    for pad in pads.values():
        pad.close()


if __name__ == "__main__":
    main()
