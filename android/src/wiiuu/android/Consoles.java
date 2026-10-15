package wiiuu.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The consoles WII-UU for Android plays with Choccy Milk: the same ids, names, colours and folder
 * names as WII-UU on a computer (roms/nes, roms/snes...), so a games folder can be copied over as
 * it is. Only cores that draw without a graphics chip, like the desktop Choccy Milk.
 */
final class Consoles {
    private Consoles() {}

    static final class Console {
        final String id, name, shortName, maker;
        final int year, color;
        final Set<String> extensions;
        /** libretro cores (buildbot names), best first */
        final List<String> cores;
        /** shown with "(experimental)": may not run on every device */
        final boolean experimental;

        Console(String id, String name, String shortName, String maker, int year, int color, String ext, String cores,
                boolean experimental) {
            this.id = id;
            this.name = name;
            this.shortName = shortName;
            this.maker = maker;
            this.year = year;
            this.color = 0xFF000000 | color;
            this.extensions = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(ext.split(","))));
            this.cores = Collections.unmodifiableList(Arrays.asList(cores.split(",")));
            this.experimental = experimental;
        }
    }

    static final List<Console> ALL;

    static {
        List<Console> all = new ArrayList<>();
        all.add(new Console("nes", "Nintendo Entertainment System", "NES", "Nintendo", 1983, 0xE60012,
                "nes,unf,unif,fds,zip", "fceumm,nestopia,mesen", false));
        all.add(new Console("snes", "Super Nintendo", "SNES", "Nintendo", 1990, 0x5C4B9E,
                "sfc,smc,swc,fig,zip", "snes9x", false));
        all.add(new Console("gb", "Game Boy / Color", "GB", "Nintendo", 1989, 0x8B9A3B,
                "gb,gbc,sgb,zip", "gambatte,sameboy,mgba", false));
        all.add(new Console("gba", "Game Boy Advance", "GBA", "Nintendo", 2001, 0x4B3FA0,
                "gba,zip", "mgba", false));
        all.add(new Console("n64", "Nintendo 64", "N64", "Nintendo", 1996, 0x1A9E3F,
                "z64,n64,v64,zip", "parallel_n64", true));
        all.add(new Console("nds", "Nintendo DS", "DS", "Nintendo", 2004, 0x7A7A7A,
                "nds,dsi,srl,zip", "melonds,desmume", true));
        all.add(new Console("sms", "Sega Master System", "SMS", "Sega", 1985, 0x1F4E9E,
                "sms,gg,zip", "genesis_plus_gx,picodrive", false));
        all.add(new Console("genesis", "Sega Genesis / Mega Drive", "MD", "Sega", 1988, 0x222222,
                "md,gen,smd,32x,bin,zip", "genesis_plus_gx,picodrive", false));
        all.add(new Console("saturn", "Sega Saturn", "SAT", "Sega", 1994, 0x3A3A6A,
                "cue,chd,m3u,ccd", "yabause", true));
        all.add(new Console("ps1", "PlayStation", "PS1", "Sony", 1994, 0x8C8C8C,
                "cue,chd,pbp,m3u,img,iso", "pcsx_rearmed,swanstation", false));
        ALL = Collections.unmodifiableList(all);
    }

    static Console byId(String id) {
        for (Console c : ALL) if (c.id.equals(id)) return c;
        return null;
    }

    /**
     * Core options per console, as the desktop Choccy Milk sets them where the same core is used:
     * N64 with its software renderer, the DS with its software renderer and no BIOS files needed.
     */
    static String options(String console) {
        switch (console) {
            case "n64":
                return "parallel-n64-gfxplugin=angrylion\nparallel-n64-angrylion-multithread=all threads\n"
                        + "parallel-n64-rspplugin=auto\n";
            case "nds":
                return "melonds_opengl_renderer=disabled\nmelonds_boot_directly=enabled\n";
            case "ps1":
                return "pcsx_rearmed_drc=enabled\n";
            default:
                return "";
        }
    }

}
