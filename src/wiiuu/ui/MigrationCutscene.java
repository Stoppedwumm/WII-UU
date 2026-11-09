package wiiuu.ui;

import java.awt.*;
import java.awt.geom.Path2D;

/** A four-chapter, one-minute mascot story shared by live migration and its developer preview. */
final class MigrationCutscene {
    static final double SECONDS = 60;
    static final int WIDTH = 720, HEIGHT = 390;
    private static final int FLOOR = 286;
    private static final Color INK = new Color(0x203348);
    private static final Color BLUE = new Color(0x58d4f4);
    private static final Color PAPER = new Color(0xf1f7fd);
    private static final Color GOLD = new Color(0xffc772);
    private final Mascot mascot = new Mascot();
    private final boolean network;
    private long started;
    private boolean complete;

    MigrationCutscene(boolean network) {
        this.network = network;
    }

    void start() {
        started = System.nanoTime();
    }

    void complete() {
        complete = true;
    }

    double elapsed() {
        return started == 0 ? 0 : (System.nanoTime() - started) / 1e9;
    }

    static double endOfLoop(double elapsed) {
        return (Math.floor(elapsed / SECONDS) + 1) * SECONDS;
    }

    void paint(Graphics2D g) {
        paintScene(g, elapsed() % SECONDS);
    }

    void paintScene(Graphics2D g, double time) {
        int chapter = chapterAt(time);
        g.setPaint(new GradientPaint(0, 0, INK, WIDTH, HEIGHT, new Color(chapter == 2 ? 0x334b81 : 0x28617b)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(new Color(0x13273a));
        g.fillRect(0, FLOOR, WIDTH, HEIGHT - FLOOR);
        g.setColor(new Color(0x35536b));
        g.drawLine(0, FLOOR, WIDTH, FLOOR);
        g.setFont(MenuView.font(Font.BOLD, 15));
        g.setColor(BLUE);
        g.drawString("WII-UU  /  MIGRATION", 30, 35);
        g.setColor(PAPER);
        String[] titles = {"01  Packing up", "02  All aboard", "03  In transit", "04  A new home"};
        g.drawString(titles[chapter], 30, 62);
        switch (chapter) {
            case 0 -> pack(g, time);
            case 1 -> depart(g, time - 14);
            case 2 -> travel(g, time - 28);
            case 3 -> arrive(g, time - 47);
            default -> throw new AssertionError(chapter);
        }
        g.setColor(new Color(0x101c29));
        g.fillRoundRect(30, 312, WIDTH - 60, 50, 14, 14);
        String caption = caption(chapter);
        g.setFont(MenuView.font(Font.PLAIN, 18));
        g.setColor(PAPER);
        g.drawString(caption, (WIDTH - g.getFontMetrics().stringWidth(caption)) / 2, 343);
    }

    int chapter() {
        return chapterAt(elapsed() % SECONDS);
    }

    private static int chapterAt(double time) {
        return time < 14 ? 0 : time < 28 ? 1 : time < 47 ? 2 : 3;
    }

    String caption(int chapter) {
        return switch (chapter) {
            case 0 -> "“Games, saves, settings … did I pack everything?”";
            case 1 -> "“Last box on board. Here we go!”";
            case 2 -> network ? "“So this is what the network looks like from the inside.”"
                              : "“Next stop, the new drive!”";
            default -> complete ? "“Everything made it. Welcome home!”" : "“I’ll set up while the last boxes come in.”";
        };
    }

    private void pack(Graphics2D g, double time) {
        terminal(g, 125, network ? "Computer A" : "Old drive");
        int box = Math.min(2, (int) (time / 4));
        for (int i = 0; i < 3; i++) parcel(g, 410 + i * 62, FLOOR - 25, i <= box);
        double phase = time % 4 / 4;
        float x = 256 + (float) (Math.sin(phase * Math.PI) * 125);
        drawMascot(g, x, FLOOR, (float) Math.sin(time * 12), x + (phase < .5 ? 80 : -80));
    }

    private void depart(Graphics2D g, double time) {
        terminal(g, 125, network ? "Computer A" : "Old drive");
        float x = lerp(285, 650, phase(time, 5, 14));
        float lift = (float) (Math.sin(phase(time, 5, 14) * Math.PI) * 80);
        vehicle(g, x, FLOOR - lift);
        float feet = lerp(FLOOR, FLOOR - 43, phase(time, 1, 5)) - lift;
        drawMascot(g, lerp(210, x, phase(time, 1, 5)), feet, time < 5 ? (float) Math.sin(time * 10) : 0, x + 70);
    }

    private void travel(Graphics2D g, double time) {
        g.setColor(new Color(0x567ba5));
        for (int i = 0; i < 20; i++) {
            int x = WIDTH - (int) ((i * 47 + time * (55 + i % 3 * 20)) % WIDTH);
            int y = 90 + i * 23 % 181;
            g.fillRoundRect(x, y, 12 + i % 5 * 6, 3, 3, 3);
        }
        float x = lerp(118, 614, phase(time, 0, 19));
        float y = 251 + (float) Math.sin(time * 1.2) * 13;
        vehicle(g, x, y);
        drawMascot(g, x, y - 43, 0, x + 90);
    }

    private void arrive(Graphics2D g, double time) {
        terminal(g, 578, network ? "Computer B" : "New drive");
        float x = lerp(80, 284, phase(time, 0, 4));
        float y = lerp(226, FLOOR, phase(time, 0, 4));
        vehicle(g, x, y);
        for (int i = 0; i < 3; i++) {
            if (time >= 4 + i * 1.8) parcel(g, 427 + i * 51, FLOOR - 25, true);
        }
        float feet = time < 4 ? y - 43 : FLOOR - (float) Math.sin(Math.PI * phase(time, 9, 10)) * 16;
        drawMascot(g, lerp(x, 465, phase(time, 4, 9)), feet,
                time > 4 && time < 9 ? (float) Math.sin(time * 12) : 0, 578);
    }

    private void drawMascot(Graphics2D g, float x, float y, float stride, float lookX) {
        mascot.look(lookX, y - 42);
        mascot.paintWalking(g, x, y - Math.abs(stride) * 3, 31, stride);
    }

    private static void terminal(Graphics2D g, int x, String name) {
        g.setColor(new Color(0x0e2030));
        g.fillRoundRect(x - 79, 132, 158, 105, 14, 14);
        g.setColor(BLUE);
        g.fillRoundRect(x - 68, 143, 136, 83, 8, 8);
        g.setColor(INK);
        g.fillRoundRect(x - 35, 247, 70, 8, 8, 8);
        g.fillRect(x - 7, 236, 14, 13);
        g.setFont(MenuView.font(Font.BOLD, 15));
        g.drawString(name, x - g.getFontMetrics().stringWidth(name) / 2, 189);
    }

    private static void parcel(Graphics2D g, int x, int y, boolean packed) {
        g.setColor(packed ? GOLD : new Color(0xc99e6f));
        g.fillRoundRect(x, y, 40, 25, 4, 4);
        g.setColor(new Color(0x915d3e));
        g.fillRect(x + 17, y, 6, 14);
    }

    private static void vehicle(Graphics2D g, float x, float y) {
        g.setColor(BLUE);
        var sled = new Path2D.Float();
        sled.moveTo(x - 76, y - 46);
        sled.lineTo(x + 77, y - 46);
        sled.lineTo(x + 53, y - 13);
        sled.lineTo(x - 59, y - 13);
        sled.closePath();
        g.fill(sled);
        g.setColor(PAPER);
        g.fillRoundRect((int) x - 58, (int) y - 45, 110, 7, 7, 7);
        parcel(g, (int) x + 30, (int) y - 71, true);
    }

    private static float phase(double time, double from, double to) {
        return (float) Math.max(0, Math.min(1, (time - from) / (to - from)));
    }

    private static float lerp(float from, float to, float phase) {
        return from + (to - from) * phase;
    }
}
