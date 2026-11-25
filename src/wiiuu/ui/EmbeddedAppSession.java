package wiiuu.ui;

import javax.swing.JComponent;
import javax.swing.JFrame;
import wiiuu.core.Config;
import wiiuu.core.PluginStore;
import wiiuu.input.PadButton;
import wiiuu.input.PadSink;

/** Adapter for trusted, compiled UI-library apps; never loads arbitrary catalog code. */
interface EmbeddedAppSession {
    JComponent component();
    boolean gameRunning();
    void closeAndWait();
    void button(int player, PadButton button, boolean down);
    void stick(int player, int stick, float x, float y);

    static EmbeddedAppSession open(PluginStore.Entry entry, Config config, JFrame owner, Runnable exit) {
        if (!entry.embeddedPlugin() || !PluginStore.catalog().contains(entry))
            throw new IllegalArgumentException("Unknown embedded app");
        return switch (entry.id()) {
            case "choccy" -> {
                choccymilk.App app = choccymilk.App.embedded(config, owner, exit);
                yield new EmbeddedAppSession() {
                    public JComponent component() { return app; }
                    public boolean gameRunning() { return app.gameRunning(); }
                    public void closeAndWait() { app.shutdownEmbeddedAndWait(); }
                    public void button(int player, PadButton button, boolean down) { app.button(player, button, down); }
                    public void stick(int player, int stick, float x, float y) { app.stick(player, stick, x, y); }
                };
            }
            default -> throw new IllegalArgumentException("No embedded adapter for " + entry.id());
        };
    }
}
