package wiiuu.core;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Built-in console catalogue, from the NES era up to Wii U / Switch and PS4. */
public final class Systems {
    private Systems() {}

    private static GameSystem sys(String id, String name, String shortName, String maker, int year, int rgb,
                                  String exts, String markers, String emulator,
                                  String linux, String windows, String mac) {
        return new GameSystem(id, name, shortName, maker, year, rgb,
                split(exts), split(markers), emulator, linux, windows, mac);
    }

    private static Set<String> split(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Set.of(csv.split(","));
    }

    public static final List<GameSystem> ALL = List.of(
            // ---- Nintendo -------------------------------------------------------------
            sys("nes", "Nintendo Entertainment System", "NES", "Nintendo", 1983, 0xE60012,
                    "nes,unf,unif,fds", "", "Mesen",
                    "Mesen {rom}", "Mesen.exe {rom}", "open -W -a Mesen --args {rom}"),
            sys("snes", "Super Nintendo", "SNES", "Nintendo", 1990, 0x5C4B9E,
                    "sfc,smc,swc,fig", "", "Snes9x",
                    "snes9x-gtk {rom}", "snes9x-x64.exe -fullscreen {rom}", "open -W -a Snes9x --args {rom}"),
            sys("gb", "Game Boy / Color", "GB", "Nintendo", 1989, 0x8B9A3B,
                    "gb,gbc,sgb", "", "mGBA",
                    "mgba-qt -f {rom}", "mGBA.exe -f {rom}", "open -W -a mGBA --args -f {rom}"),
            sys("n64", "Nintendo 64", "N64", "Nintendo", 1996, 0x1A9E3F,
                    "z64,n64,v64", "", "Mupen64Plus",
                    "mupen64plus --fullscreen {rom}", "mupen64plus-ui-console.exe --fullscreen {rom}",
                    "mupen64plus --fullscreen {rom}"),
            sys("gba", "Game Boy Advance", "GBA", "Nintendo", 2001, 0x4B3FA0,
                    "gba", "", "mGBA",
                    "mgba-qt -f {rom}", "mGBA.exe -f {rom}", "open -W -a mGBA --args -f {rom}"),
            sys("gc", "Nintendo GameCube", "GCN", "Nintendo", 2001, 0x6A5ACD,
                    "iso,gcm,gcz,rvz,ciso,dol", "", "Dolphin",
                    "dolphin-emu -b -e {rom}", "Dolphin.exe -b -e {rom}", "open -W -a Dolphin --args -b -e {rom}"),
            sys("nds", "Nintendo DS", "DS", "Nintendo", 2004, 0x7A7A7A,
                    "nds,dsi,srl", "", "melonDS",
                    "melonDS -f {rom}", "melonDS.exe -f {rom}", "open -W -a melonDS --args -f {rom}"),
            sys("wii", "Wii", "Wii", "Nintendo", 2006, 0x33B5E5,
                    "iso,wbfs,rvz,gcz,ciso,wad,dol,elf", "", "Dolphin",
                    "dolphin-emu -b -e {rom}", "Dolphin.exe -b -e {rom}", "open -W -a Dolphin --args -b -e {rom}"),
            sys("3ds", "Nintendo 3DS", "3DS", "Nintendo", 2011, 0xCE181E,
                    "3ds,cci,cxi,cia,3dsx,app", "", "Azahar",
                    "azahar {rom}", "azahar.exe {rom}", "open -W -a Azahar --args {rom}"),
            sys("wiiu", "Wii U", "Wii U", "Nintendo", 2012, 0x009AC7,
                    "wua,wud,wux,rpx", "", "Cemu",
                    "cemu -f -g {rom}", "Cemu.exe -f -g {rom}", "open -W -a Cemu --args -f -g {rom}"),
            sys("switch", "Nintendo Switch", "Switch", "Nintendo", 2017, 0xE60012,
                    "nsp,xci,nro,nca", "", "Ryujinx",
                    "Ryujinx --fullscreen {rom}", "Ryujinx.exe --fullscreen {rom}",
                    "open -W -a Ryujinx --args --fullscreen {rom}"),
            // ---- Sega -----------------------------------------------------------------
            sys("sms", "Sega Master System", "SMS", "Sega", 1985, 0x1F4E9E,
                    "sms,gg", "", "Mednafen",
                    "mednafen {rom}", "mednafen.exe {rom}", "mednafen {rom}"),
            sys("genesis", "Sega Genesis / Mega Drive", "MD", "Sega", 1988, 0x222222,
                    "md,gen,smd,32x", "", "Mednafen",
                    "mednafen {rom}", "mednafen.exe {rom}", "mednafen {rom}"),
            sys("saturn", "Sega Saturn", "SAT", "Sega", 1994, 0x3A3A6A,
                    "cue,chd,m3u,ccd", "", "Mednafen",
                    "mednafen {rom}", "mednafen.exe {rom}", "mednafen {rom}"),
            sys("dc", "Sega Dreamcast", "DC", "Sega", 1998, 0xF28C28,
                    "gdi,cdi,chd", "", "Flycast",
                    "flycast {rom}", "flycast.exe {rom}", "open -W -a Flycast --args {rom}"),
            // ---- Sony -----------------------------------------------------------------
            sys("ps1", "PlayStation", "PS1", "Sony", 1994, 0x8C8C8C,
                    "cue,chd,pbp,m3u,ecm,img,iso", "", "DuckStation",
                    "duckstation-qt -fullscreen -batch -- {rom}", "duckstation-qt-x64-ReleaseLTCG.exe -fullscreen -batch -- {rom}",
                    "open -W -a DuckStation --args -fullscreen -batch -- {rom}"),
            sys("ps2", "PlayStation 2", "PS2", "Sony", 2000, 0x1C3F94,
                    "iso,chd,cso,zso,gz,elf", "", "PCSX2",
                    "pcsx2-qt -fullscreen -batch -- {rom}", "pcsx2-qt.exe -fullscreen -batch -- {rom}",
                    "open -W -a PCSX2 --args -fullscreen -batch -- {rom}"),
            sys("psp", "PlayStation Portable", "PSP", "Sony", 2004, 0x2B2B2B,
                    "iso,cso,pbp,chd", "", "PPSSPP",
                    "PPSSPPSDL --fullscreen {rom}", "PPSSPPWindows64.exe --fullscreen {rom}",
                    "open -W -a PPSSPPSDL --args --fullscreen {rom}"),
            sys("ps3", "PlayStation 3", "PS3", "Sony", 2006, 0x111111,
                    "", "eboot.bin", "RPCS3",
                    "rpcs3 --no-gui {rom}", "rpcs3.exe --no-gui {rom}", "open -W -a RPCS3 --args --no-gui {rom}"),
            sys("ps4", "PlayStation 4", "PS4", "Sony", 2013, 0x003791,
                    "", "eboot.bin", "shadPS4",
                    "shadps4 -g {rom}", "shadPS4.exe -g {rom}", "open -W -a shadps4 --args -g {rom}")
    );

    public static Optional<GameSystem> byId(String id) {
        return ALL.stream().filter(s -> s.id().equals(id)).findFirst();
    }
}
