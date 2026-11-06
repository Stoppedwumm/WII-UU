package wiiuu.core;

import java.nio.file.Path;
import java.util.*;

/** Shared catalogue, installation and preference operations for the two Choccy Milk frontends. */
public final class CoreManager {
    public record Entry(String id, Path installed) {
        @Override public String toString() { return id + (installed == null ? " — not installed" : " — installed"); }
    }
    private final Config config;
    private final Cores cores;
    public CoreManager(Config config) { this.config = config; this.cores = new Cores(config); }
    public static List<String> catalogue(String system) {
        return Cores.CORES.getOrDefault(system, List.of()).stream().filter(Cores::choccyCanRun).toList();
    }
    public List<Entry> entries(String system) {
        return catalogue(system).stream().map(id -> new Entry(id, cores.installedChoccyCore(id))).toList();
    }
    public String selected(String system) { return config.get("choccy.core." + system, "").trim(); }
    public void select(String system, String id) {
        if (!id.isEmpty() && !catalogue(system).contains(id)) throw new IllegalArgumentException("Choose a core from this console's catalogue");
        if (system.equals("nds") && DsOnline.enabled(config) && !id.isEmpty() && !id.equals("melondsds"))
            throw new IllegalArgumentException("DS online requires melonDS DS. Turn off DS online to choose a different core.");
        config.set("choccy.core." + system, id.isEmpty() ? null : id); config.save();
    }
    public Path download(String system, String id) {
        if (!catalogue(system).contains(id)) throw new IllegalArgumentException("Choose a core from this console's catalogue");
        return cores.downloadChoccyCore(id);
    }
}
