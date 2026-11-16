#!/usr/bin/env python3
"""Build Milk Run: original tiny GB homebrew. No commercial game assets.
Move the milk-white square to the dark square; score is shown as a tile bar.
This file is the complete, editable ROM source (LR35902 opcodes).
"""
from pathlib import Path
rom=bytearray(32768); code=bytearray(); labels={}; fix=[]
def emit(*values):code.extend(values)
def label(name):labels[name]=0x150+len(code)
def jump(op,name):emit(op,0,0);fix.append((len(code)-2,name))
def load(address):emit(0xfa,address&255,address>>8)
def save(address):emit(0xea,address&255,address>>8)
# Interrupts off, stack in HRAM; wait for VBlank before disabling LCD.
emit(0xf3,0x31,0xfe,0xff)
label('wait');emit(0xf0,0x44,0xfe,144);jump(0xda,'wait');emit(0xaf,0xe0,0x40)
# Clear tile memory, background and OAM.
emit(0x21,0x00,0x80,0x01,0x00,0x20)
label('clear');emit(0xaf,0x22,0x0b,0x78,0xb1);jump(0xc2,'clear')
emit(0x21,0x00,0xfe,0x06,160)
label('oam');emit(0xaf,0x22,0x05);jump(0xc2,'oam')
# Tiles: light checker background, player, goal, score.
for tile,rows in enumerate(([0x55,0,0xaa,0]*4,[0xff,0xff]*8,[0x7e,0,0x81,0,0xbd,0,0xa5,0,0xa5,0,0xbd,0,0x81,0,0x7e,0],[0xff,0]*8)):
    emit(0x21,(0x8000+tile*16)&255,(0x8000+tile*16)>>8)
    for value in rows:emit(0x36,value,0x23)
# Player x/y and goal x/y, score in WRAM.
for address,value in [(0xc000,48),(0xc001,72),(0xc002,120),(0xc003,96),(0xc004,0)]:
    emit(0x3e,value);save(address)
# Palette, sprites and screen on. A short square-wave tone on collections.
for reg,value in [(0x47,0xe4),(0x48,0x1b),(0x42,0),(0x43,0),(0x26,0x80),(0x24,0x77),(0x25,0x11),(0x40,0x93)]:emit(0x3e,value,0xe0,reg)
label('loop')
# Wait for a fresh VBlank once per frame.
label('visible');emit(0xf0,0x44,0xfe,144);jump(0xd2,'visible')
label('vblank');emit(0xf0,0x44,0xfe,144);jump(0xda,'vblank')
emit(0x3e,0x20,0xe0,0x00,0xf0,0x00,0xf0,0x00,0x2f,0x47)
# Joypad bits 0 right, 1 left, 2 up, 3 down; keep sprites on-screen.
for bit,addr,delta,limit in [(0,0xc000,1,160),(1,0xc000,-1,8),(2,0xc001,-1,24),(3,0xc001,1,144)]:
    emit(0xcb,0x40+bit*8);jump(0xca,f'skip{bit}');load(addr);emit(0xfe,limit);jump(0xca,f'skip{bit}');emit(0x3c if delta==1 else 0x3d);save(addr);label(f'skip{bit}')
# Copy coordinates to OAM.
for base,x,y,tile in [(0xfe00,0xc000,0xc001,1),(0xfe04,0xc002,0xc003,2)]:
    load(y);save(base);load(x);save(base+1);emit(0x3e,tile);save(base+2)
# Collision when coordinates are within eight pixels in either dimension.
load(0xc002);emit(0x47);load(0xc000);emit(0x90,0xc6,7,0xfe,15);jump(0xd2,'loop')
load(0xc003);emit(0x47);load(0xc001);emit(0x90,0xc6,7,0xfe,15);jump(0xd2,'loop')
load(0xc004);emit(0x3c,0xe6,15);save(0xc004);emit(0x5f,0x16,0,0x21,0x00,0x98,0x19,0x36,3)
# Deterministic new target, 8-pixel grid.
load(0xc002);emit(0xc6,56,0xfe,160);jump(0xda,'xok');emit(0xd6,144);label('xok');save(0xc002)
load(0xc003);emit(0xc6,40,0xfe,144);jump(0xda,'yok');emit(0xd6,112);label('yok');save(0xc003)
for reg,value in [(0x11,0x80),(0x12,0xf2),(0x13,0xc0),(0x14,0x87)]:emit(0x3e,value,0xe0,reg)
jump(0xc3,'loop')
for offset,name in fix:
    target=labels[name];code[offset:offset+2]=target.to_bytes(2,'little')
rom[0x100:0x104]=bytes([0,0xc3,0x50,1])
# Standard cartridge header logo required by the hardware boot ROM.
rom[0x104:0x134]=bytes.fromhex('CEED6666CC0D000B03730083000C000D0008111F8889000EDCCC6EE6DDDDD999BBBB67636E0EECCCDDDC999FBBB9333E')
rom[0x134:0x13c]=b'MILK RUN';rom[0x148]=0;rom[0x149]=0
rom[0x150:0x150+len(code)]=code
rom[0x14d]=(-sum(rom[0x134:0x14d])-25)&255
checksum=sum(rom)&65535;rom[0x14e:0x150]=checksum.to_bytes(2,'big')
Path('docs/demo/milk-run.gb').write_bytes(rom)
print('Built Milk Run:',len(rom),'bytes')
