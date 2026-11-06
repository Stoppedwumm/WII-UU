package wiiuu.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** DS WFC settings shared by both frontends. melonDS DS provides the networking implementation. */
public final class DsOnline {
    private DsOnline() {}
    public static boolean enabled(Config c) { return c.getBool("choccy.nds.online", false); }
    public static String dns(Config c) { return c.get("choccy.nds.dns", "").trim(); }
    public static boolean validDns(String value) {
        if (value.isBlank()) return true;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || part.length() > 1 && part.startsWith("0") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
    public static boolean validName(String name) { return name.matches("[A-Za-z0-9_-]{1,10}"); }
    public static synchronized String username(Config c) {
        String existing = c.get("choccy.nds.username", "");
        if (validName(existing)) return existing;
        String name = "CM" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        c.set("choccy.nds.username", name); c.save(); return name;
    }
    public static Map<String, String> options(Config c) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("melonds_network_mode", enabled(c) ? "indirect" : "disabled");
        if (!enabled(c)) return result;
        result.put("wiiuu.username", username(c));
        if (!dns(c).isBlank()) {
            if (!validDns(dns(c))) throw new IllegalArgumentException("DS online DNS must be an IPv4 address");
            result.put("melonds_firmware_wfc_dns", dns(c));
            result.put("melonds_override_fw_settings", "enabled");
        }
        // Preserve existing firmware/MAC by default: changing MACs can change Pokémon save access.
        if (c.getBool("choccy.nds.online.uniqueMac", false)) result.put("melonds_mac_address_mode", "from-username");
        return result;
    }
}
