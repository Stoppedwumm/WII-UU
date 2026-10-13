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
    watch                      also take over real controllers (see below)
Answers "ok <n>" or "err <n> <reason>" to create.

Real controllers (USB / Bluetooth) plugged in while it watches are grabbed (EVIOCGRAB: only this
helper reads them, so an emulator never sees them besides the virtual pads) and their input is
passed to WII-UU, which gives each one a player number like a phone and plays it through that
player's virtual pad - the pad every emulator is set up for. Their lines:
    pad+ <id> <name>           a controller came (id: its event node and a number, e.g. event7.3)
    pad- <id>                  ... went
    in <id> b <BUTTON> <0|1>   a button as the GamePad's (A, B, X, Y, L, R, ZL, ZR, PLUS, MINUS,
                               HOME, L3, R3, UP, DOWN, LEFT, RIGHT), by position; others as
                               K<evdev code in hex> (WII-UU can remap them)
    in <id> s <0|1> <x> <y>    a stick (0 left, 1 right), -1..1 with +y down Needs write access to /dev/uinput
(the WII-UU installer adds a udev rule for that). Uses only the standard library.
"""
import fcntl
import os
import struct
import re
import sys
import threading
import time

# ioctl request numbers from <linux/uinput.h>
def _IOC(direction, typ, nr, size):
    return (direction << 30) | (size << 16) | (ord(typ) << 8) | nr

def _IOW(typ, nr, size):
    return _IOC(1, typ, nr, size)

def _IO(typ, nr):
    return _IOC(0, typ, nr, 0)

def _IOR(typ, nr, size):
    return _IOC(2, typ, nr, size)

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
ABSINFO = struct.Struct("iiiiii")              # value, minimum, maximum, fuzz, flat, resolution
EVIOCGRAB = _IOW("E", 0x90, 4)

def EVIOCGBIT(ev, size):
    return _IOC(2, "E", 0x20 + ev, size)

def EVIOCGABS(code):
    return _IOR("E", 0x40 + code, ABSINFO.size)

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


# ---- real controllers -------------------------------------------------------------------

# buttons by position, as the GamePad's: the bottom one is B, the right one A (Nintendo layout)
GAMEPAD = {
    0x130: "B", 0x131: "A", 0x133: "X", 0x134: "Y",           # SOUTH EAST NORTH WEST
    0x136: "L", 0x137: "R", 0x138: "ZL", 0x139: "ZR",         # TL TR TL2 TR2
    0x13A: "MINUS", 0x13B: "PLUS", 0x13C: "HOME", 0x13D: "L3", 0x13E: "R3",
    0x220: "UP", 0x221: "DOWN", 0x222: "LEFT", 0x223: "RIGHT",
}
# old style "joystick" buttons (cheap USB pads, PlayStation adapters): their usual order is
# triangle, circle, cross, square, L1, R1, L2, R2, select, start, L3, R3
JOYSTICK = {
    0x120: "X", 0x121: "A", 0x122: "B", 0x123: "Y", 0x124: "L", 0x125: "R",
    0x126: "ZL", 0x127: "ZR", 0x128: "MINUS", 0x129: "PLUS", 0x12A: "L3", 0x12B: "R3",
}
ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ = 0, 1, 2, 3, 4, 5
ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y = 9, 10, 0x10, 0x11
EVENT_DEVICES = "/proc/bus/input/devices"
SKIP_NAMES = re.compile(r"motion sensors|imu|accelerometer|touchpad|gyro", re.I)

_out_lock = threading.Lock()


def say(line):
    with _out_lock:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


def joysticks(text):
    """(name, event node, virtual?, sysfs path) of every device with a joystick handler, from /proc/bus/input/devices."""
    found = []
    for block in re.split(r"\n\s*\n", text):
        name, sysfs, event, js = None, "", None, False
        for line in block.splitlines():
            if line.startswith("N: Name="):
                name = line[8:].replace('"', "").strip()
            elif line.startswith("S: Sysfs="):
                sysfs = line[9:].strip()
            elif line.startswith("H: Handlers="):
                for h in line[12:].split():
                    if re.fullmatch(r"event\d+", h):
                        event = h
                    elif re.fullmatch(r"js\d+", h):
                        js = True
        if name and event and js:
            found.append((name, event, sysfs.startswith("/devices/virtual/"), sysfs))
    return found


def ours(name, virtual):
    """WII-UU's own virtual pads (the phones'), which it must never grab."""
    return virtual and name.startswith("Microsoft X-Box 360 pad")


def has_bit(bits, n):
    return n // 8 < len(bits) and bits[n // 8] >> (n % 8) & 1


class Controller:
    """Turns one controller's evdev events into the GamePad's buttons and sticks."""

    def __init__(self, ident, absinfo, keys):
        self.id = ident
        self.info = absinfo                      # code -> (min, max, flat)
        rs = ABS_RX in absinfo and ABS_RY in absinfo
        # with no RX/RY the right stick is Z/RZ (most HID pads); otherwise Z/RZ are the triggers
        self.right = (ABS_RX, ABS_RY) if rs else (ABS_Z, ABS_RZ)
        self.triggers = {ABS_Z: "ZL", ABS_RZ: "ZR"} if rs else {}
        self.triggers.update({ABS_BRAKE: "ZL", ABS_GAS: "ZR"})
        # pads with digital ZL / ZR buttons too report analog ones: the buttons win
        if has_bit(keys, 0x138):
            self.triggers = {c: b for c, b in self.triggers.items() if b != "ZL"}
        if has_bit(keys, 0x139):
            self.triggers = {c: b for c, b in self.triggers.items() if b != "ZR"}
        self.axes = {}
        self.pressed = set()
        self.dirty = set()
        self.sent = {0: (0.0, 0.0), 1: (0.0, 0.0)}

    def norm(self, code, value):
        lo, hi, flat = self.info.get(code, (-32768, 32767, 0))
        if hi <= lo:
            return 0.0
        v = (2.0 * (value - lo) / (hi - lo)) - 1.0
        dead = max(flat / float(hi - lo) * 2.0, 0.12)
        if abs(v) < dead:
            return 0.0
        return max(-1.0, min(1.0, (v - dead if v > 0 else v + dead) / (1.0 - dead)))

    def trigger(self, code, value):
        lo, hi, _ = self.info.get(code, (0, 255, 0))
        return hi > lo and (value - lo) / float(hi - lo) > 0.4

    def button(self, name, down):
        if down == (name in self.pressed):
            return []
        if down:
            self.pressed.add(name)
        else:
            self.pressed.discard(name)
        return ["in %s b %s %d" % (self.id, name, 1 if down else 0)]

    def event(self, typ, code, value):
        """The lines one event gives (sticks wait for the end of the report)."""
        if typ == EV_KEY and value != 2:
            # buttons without a GamePad name (paddles, extra buttons) as K<code>, for remapping them
            name = GAMEPAD.get(code) or JOYSTICK.get(code) or ("K%X" % code if code >= 0x100 else None)
            return self.button(name, value != 0) if name else []
        if typ == EV_ABS:
            if code == ABS_HAT0X:
                return self.button("LEFT", value < 0) + self.button("RIGHT", value > 0)
            if code == ABS_HAT0Y:
                return self.button("UP", value < 0) + self.button("DOWN", value > 0)
            if code in self.triggers:
                return self.button(self.triggers[code], self.trigger(code, value))
            if code in (ABS_X, ABS_Y) + self.right:
                self.axes[code] = self.norm(code, value)
                self.dirty.add(0 if code in (ABS_X, ABS_Y) else 1)
            return []
        if typ == EV_SYN and code == SYN_REPORT:
            lines = []
            for stick in sorted(self.dirty):
                cx, cy = (ABS_X, ABS_Y) if stick == 0 else self.right
                xy = (round(self.axes.get(cx, 0.0), 3), round(self.axes.get(cy, 0.0), 3))
                if xy != self.sent[stick]:
                    self.sent[stick] = xy
                    lines.append("in %s s %d %.3f %.3f" % (self.id, stick, xy[0], xy[1]))
            self.dirty.clear()
            return lines
        return []


def open_controller(event, ident):
    """Opens and grabs a controller; (fd, Controller) or None when it isn't a gamepad."""
    fd = os.open("/dev/input/" + event, os.O_RDONLY)
    try:
        keys = bytearray(0x300 // 8)
        fcntl.ioctl(fd, EVIOCGBIT(EV_KEY, len(keys)), keys)
        if not any(has_bit(keys, k) for k in list(GAMEPAD) + list(JOYSTICK)):
            os.close(fd)
            return None
        absbits = bytearray(0x40 // 8)
        fcntl.ioctl(fd, EVIOCGBIT(EV_ABS, len(absbits)), absbits)
        info = {}
        for code in range(0x40):
            if has_bit(absbits, code):
                buf = bytearray(ABSINFO.size)
                fcntl.ioctl(fd, EVIOCGABS(code), buf)
                _, lo, hi, _, flat, _ = ABSINFO.unpack(buf)
                info[code] = (lo, hi, flat)
        fcntl.ioctl(fd, EVIOCGRAB, 1)
        return fd, Controller(ident, info, keys)
    except OSError:
        os.close(fd)
        raise


def read_controller(fd, pad):
    try:
        while True:
            data = os.read(fd, EVENT.size * 64)
            if not data:
                break
            for i in range(0, len(data) - EVENT.size + 1, EVENT.size):
                _, _, typ, code, value = EVENT.unpack_from(data, i)
                for line in pad.event(typ, code, value):
                    say(line)
    except OSError:
        pass                                     # unplugged
    finally:
        try:
            os.close(fd)
        except OSError:
            pass
        say("pad- %s" % pad.id)


def watch():
    """
    Takes over controllers as they're plugged in (checks twice a second).

    A device is its event node plus where it sits (sysfs): a controller plugged in again usually
    gets the same node, and must count as a new one. Each connection gets an id of its own
    (event7.3), so the "pad-" of the one before can never end the new one. A node that can't be
    opened yet (udev still setting its permissions, just after it appears) is tried again.
    """
    taken, skipped, tries, count = {}, set(), {}, 0
    while True:
        try:
            with open(EVENT_DEVICES) as f:
                devices = joysticks(f.read())
        except OSError:
            devices = []
        here = {(event, sysfs) for _, event, _, sysfs in devices}
        for key in [k for k, t in taken.items() if not t.is_alive() or k not in here]:
            del taken[key]
        skipped &= here
        for key in [k for k in tries if k not in here]:
            del tries[key]
        for name, event, virtual, sysfs in devices:
            key = (event, sysfs)
            if key in taken or key in skipped or ours(name, virtual) or SKIP_NAMES.search(name):
                continue
            ident = "%s.%d" % (event, count + 1)
            try:
                got = open_controller(event, ident)
            except OSError as e:
                tries[key] = tries.get(key, 0) + 1
                if tries[key] == 10:             # 5 s of trying: say so once, keep trying
                    say("padfail %s %s: %s" % (event, name, e))
                continue
            tries.pop(key, None)
            count += 1
            if got is None:
                skipped.add(key)                 # not a gamepad
                continue
            say("pad+ %s %s" % (ident, name))
            t = threading.Thread(target=read_controller, args=got, daemon=True)
            t.start()
            taken[key] = t
        time.sleep(0.5)


def selftest():
    """Prints the ioctl numbers and struct sizes so they can be checked against <linux/uinput.h>."""
    print("UI_SET_EVBIT=%#x UI_SET_KEYBIT=%#x UI_SET_ABSBIT=%#x UI_DEV_SETUP=%#x UI_ABS_SETUP=%#x "
          "UI_DEV_CREATE=%#x UI_DEV_DESTROY=%#x" % (UI_SET_EVBIT, UI_SET_KEYBIT, UI_SET_ABSBIT, UI_DEV_SETUP,
                                                   UI_ABS_SETUP, UI_DEV_CREATE, UI_DEV_DESTROY))
    print("EVIOCGRAB=%#x EVIOCGABS(0)=%#x EVIOCGBIT(EV_KEY,96)=%#x" % (EVIOCGRAB, EVIOCGABS(0), EVIOCGBIT(EV_KEY, 96)))
    print("sizeof uinput_setup=%d uinput_abs_setup=%d input_event=%d" % (UINPUT_SETUP.size, ABS_SETUP.size, EVENT.size))


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        selftest()
        return
    pads = {}
    for line in sys.stdin:
        p = line.split()
        if not p:
            continue
        try:
            n = int(p[1]) if len(p) > 1 else 0
            if p[0] == "watch":
                threading.Thread(target=watch, daemon=True).start()
            elif p[0] == "create":
                if n not in pads:
                    pads[n] = Pad(n)
                say("ok %d" % n)
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
                say("err %s %s" % (p[1] if len(p) > 1 else "?", e))
    for pad in pads.values():
        pad.close()


if __name__ == "__main__":
    main()
