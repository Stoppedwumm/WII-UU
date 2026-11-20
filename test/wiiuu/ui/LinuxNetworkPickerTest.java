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
        previewOnAnyOs();
        System.out.println("LinuxNetworkPickerTest passed");
    }

    /** Rendering and actions use mock networks only, including with a Windows OS name. */
    private static void previewOnAnyOs() {
        String originalOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            LinuxNetworkPicker picker = new LinuxNetworkPicker(null, true);
            require(picker.isPreview(), "preview enabled on Windows");
            require(!picker.typing() && picker.visibleNetworks().size() >= 4, "mock scan loaded");
            require(picker.visibleNetworks().get(0).connected(), "mock connected network");

            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                    1100, 720, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            picker.paint(graphics, 1100, 720);
            graphics.dispose();

            picker.navigate(0, 2); // secured Home Network
            picker.activate();
            require(picker.typing(), "preview uses normal password keyboard");
            picker.keyTyped('s');
            picker.keyTyped('3');
            picker.navigate(0, 3); // command row
            picker.navigate(5, 0); // Connect
            picker.activate();
            require(!picker.typing(), "password prompt dismissed");
            require(picker.message().contains("No changes made"), "connection stays simulated");
            require(picker.visibleNetworks().get(2).connected(), "mock connection display updated");
            picker.back();
            require(picker.closed(), "preview can close without closing settings");
        } finally {
            if (originalOs == null) System.clearProperty("os.name");
            else System.setProperty("os.name", originalOs);
        }
    }

    private static void require(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
