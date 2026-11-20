package wiiuu.ui;

import java.util.List;

/** Headless checks for NetworkManager's escaped scan output. */
public final class LinuxNetworkPickerTest {
    public static void main(String[] args) {
        List<String> slash = LinuxNetworkPicker.split("*:Cafe\\\\guest:87:WPA2");
        require(slash.size() == 4 && slash.get(1).equals("Cafe\\guest"),
                "escaped backslash in SSID");
        List<String> colon = LinuxNetworkPicker.split("*:Cafe\\: guest:87:WPA2");
        require(colon.size() == 4 && colon.get(1).equals("Cafe: guest"),
                "escaped colon in SSID");

        String output = "*:Connected:73:WPA2\n" +
                ":Cafe\\: guest:53:WPA2\n" +
                ":Cafe\\: guest:80:WPA2\n" +
                ":Open cafe:25:--\n" +
                ":Hidden::WPA2\n" +
                ":Bad:unknown:WPA2\n";
        var networks = LinuxNetworkPicker.parse(output);
        require(networks.size() == 3, "expected unique visible SSIDs");
        require(networks.get(0).connected() && networks.get(0).ssid().equals("Connected"), "active first");
        require(networks.get(1).ssid().equals("Cafe: guest") && networks.get(1).signal() == 80, "strongest AP");
        require(!networks.get(2).secured(), "open network security");
        System.out.println("LinuxNetworkPickerTest passed");
    }

    private static void require(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
