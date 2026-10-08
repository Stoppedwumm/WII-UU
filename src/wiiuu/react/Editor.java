package wiiuu.react;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.JTextComponent;

/**
 * WII-UU's reactions editor (WII-UU-Reactions.jar): drag videos in, play them, and type what
 * WII-UU says at each moment on the timeline (with a mood and how long it stays). Exports a pack
 * (.zip: videos, .vtt captions, thumbnails, reactions.json) that WII-UU plays as the Reactions
 * channel, with the GamePad's WII-UU logo reacting along. Projects save as .wiireact.
 *
 * <p>Keys (outside text fields): Space play / pause, ← → five seconds, Delete removes the chosen
 * reaction, Ctrl+S saves. Enter in the "WII-UU says" field adds a reaction at the playhead.
 */
public final class Editor {
    private static final String TITLE = "WII-UU Reactions";
    private static final String[][] QUICK = {
            {"Damn!", "sad"}, {"No way!", "shocked"}, {"LOL", "happy"}, {"Wait, what?", "shocked"}, {"Called it.", "smug"},
            {"This is fine.", "nervous"}, {"Let's gooo!", "happy"}, {"Hmm...", "focus"}, {"Nobody saw that.", "sneaky"},
            {"I'm not crying, you're crying.", "sad"}, {"Oh no. Oh no no no.", "nervous"}, {"Too easy.", "smug"}};

    private Project project = new Project();
    private Path projectFile;
    private boolean dirty;

    private final JFrame frame = new JFrame(TITLE);
    private final DefaultListModel<Project.Clip> clipModel = new DefaultListModel<>();
    private final JList<Project.Clip> clipList = new JList<>(clipModel);
    private final JTextField packTitle = new JTextField(project.title, 22);
    private final JTextField clipTitle = new JTextField(18);
    private final Preview preview = new Preview();
    private final Timeline timeline;
    private final JLabel time = new JLabel("0:00.0 / 0:00.0");
    private final JButton play = new JButton("▶ Play");
    private final JTextField says = new JTextField(22);
    private final JComboBox<String> mood = new JComboBox<>(Project.MOODS.toArray(new String[0]));
    private final JSpinner length = new JSpinner(new SpinnerNumberModel(3.0, 0.5, 60.0, 0.5));
    private final JLabel selectedInfo = new JLabel(" ");
    private final JButton editSel = new JButton("Edit…"), deleteSel = new JButton("Delete");

    private Project.Clip clip;
    private Project.Cue selected;
    private double playhead;
    private Media.Player player;
    private final Timer clock;
    private final AtomicLong frameRequest = new AtomicLong();

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // the default look is fine
        }
        SwingUtilities.invokeLater(() -> {
            Editor e = new Editor();
            for (String a : args) {
                Path p = Path.of(a);
                if (a.toLowerCase(Locale.ROOT).endsWith(".wiireact")) e.open(p);
                else e.addVideos(List.of(p.toFile()));
            }
        });
    }

    private Editor() {
        timeline = new Timeline(this::seek, this::select, this::editCue, this::changed);
        clock = new Timer(33, e -> tickPlayer());

        // ---- top: the pack
        JButton newB = new JButton("New"), openB = new JButton("Open…"), saveB = new JButton("Save"), export = new JButton("Export pack…"),
                send = new JButton("Send to WII-UU");
        newB.addActionListener(e -> newProject());
        openB.addActionListener(e -> openDialog());
        saveB.addActionListener(e -> save(false));
        export.addActionListener(e -> exportDialog());
        send.addActionListener(e -> sendToWiiuu());
        send.setToolTipText("Exports the pack straight into WII-UU's reactions folder on this computer");
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        for (JButton b : new JButton[]{newB, openB, saveB}) top.add(b);
        top.add(Box.createHorizontalStrut(12));
        top.add(new JLabel("Pack name"));
        top.add(packTitle);
        top.add(Box.createHorizontalStrut(12));
        top.add(export);
        top.add(send);
        packTitle.getDocument().addDocumentListener(onEdit(() -> {
            project.title = packTitle.getText().trim().isEmpty() ? "My WII-UU reactions" : packTitle.getText().trim();
            changed();
        }));

        // ---- left: the videos
        clipList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clipList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showClip(clipList.getSelectedValue());
        });
        JButton add = new JButton("Add videos…"), remove = new JButton("Remove");
        add.addActionListener(e -> addDialog());
        remove.addActionListener(e -> removeClip());
        clipTitle.getDocument().addDocumentListener(onEdit(() -> {
            if (clip != null && !clipTitle.getText().equals(clip.title)) {
                clip.title = clipTitle.getText();
                clipList.repaint();
                changed();
            }
        }));
        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        left.add(new JLabel("Videos (drag them in)"), BorderLayout.NORTH);
        left.add(new JScrollPane(clipList), BorderLayout.CENTER);
        JPanel leftBottom = new JPanel(new GridLayout(0, 1, 4, 4));
        JPanel row = new JPanel(new GridLayout(1, 2, 4, 4));
        row.add(add);
        row.add(remove);
        leftBottom.add(row);
        leftBottom.add(new JLabel("Title of this video"));
        leftBottom.add(clipTitle);
        left.add(leftBottom, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(250, 300));

        // ---- centre: the picture and the controls
        JButton back = new JButton("⏪ 5 s"), fwd = new JButton("5 s ⏩");
        back.addActionListener(e -> seek(Math.max(0, playhead - 5)));
        fwd.addActionListener(e -> seek(playhead + 5));
        play.addActionListener(e -> togglePlay());
        time.setFont(time.getFont().deriveFont(Font.BOLD, 14f));
        JPanel transport = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        transport.add(back);
        transport.add(play);
        transport.add(fwd);
        transport.add(Box.createHorizontalStrut(16));
        transport.add(time);
        JPanel centre = new JPanel(new BorderLayout());
        centre.add(preview, BorderLayout.CENTER);
        centre.add(transport, BorderLayout.SOUTH);

        // ---- right: what WII-UU says
        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel head = new JLabel("WII-UU says…");
        head.setFont(head.getFont().deriveFont(Font.BOLD, 15f));
        says.setFont(says.getFont().deriveFont(16f));
        says.addActionListener(e -> addCue(says.getText(), (String) mood.getSelectedItem()));
        JButton addB = new JButton("Add at the playhead (Enter)");
        addB.addActionListener(e -> addCue(says.getText(), (String) mood.getSelectedItem()));
        mood.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
                l.setIcon(dot(Timeline.MOOD_COLORS.getOrDefault(String.valueOf(value), Color.GRAY)));
                return l;
            }
        });
        JPanel moodRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        moodRow.add(new JLabel("Mood"));
        moodRow.add(mood);
        moodRow.add(new JLabel("for"));
        moodRow.add(length);
        moodRow.add(new JLabel("s"));
        JPanel quick = new JPanel(new GridLayout(0, 2, 4, 4));
        for (String[] q : QUICK) {
            JButton b = new JButton(q[0]);
            b.setToolTipText("Adds \"" + q[0] + "\" (" + q[1] + ") at the playhead");
            b.addActionListener(e -> addCue(q[0], q[1]));
            quick.add(b);
        }
        editSel.addActionListener(e -> editCue(selected));
        deleteSel.addActionListener(e -> deleteSelected());
        JPanel selRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        selRow.add(editSel);
        selRow.add(deleteSel);
        for (JComponent c : new JComponent[]{head, says, moodRow, addB, new JLabel(" "), new JLabel("Quick reactions"), quick,
                new JLabel(" "), new JLabel("The chosen reaction"), selectedInfo, selRow}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            right.add(c);
            right.add(Box.createVerticalStrut(4));
        }
        says.setMaximumSize(new Dimension(Integer.MAX_VALUE, says.getPreferredSize().height + 6));
        quick.setMaximumSize(new Dimension(Integer.MAX_VALUE, quick.getPreferredSize().height));
        right.setPreferredSize(new Dimension(320, 300));
        updateSelectedInfo();

        // ---- bottom: the timeline
        JScrollPane tl = new JScrollPane(timeline, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        tl.setPreferredSize(new Dimension(800, 170));
        tl.getHorizontalScrollBar().setUnitIncrement(30);
        JPanel bottom = new JPanel(new BorderLayout());
        JLabel tlHelp = new JLabel("  Timeline: click to move the playhead · drag a reaction to move it, its right edge to make it longer · "
                + "double-click to edit · Ctrl + wheel zooms");
        tlHelp.setFont(tlHelp.getFont().deriveFont(11f));
        bottom.add(tlHelp, BorderLayout.NORTH);
        bottom.add(tl, BorderLayout.CENTER);

        JPanel middle = new JPanel(new BorderLayout());
        middle.add(left, BorderLayout.WEST);
        middle.add(centre, BorderLayout.CENTER);
        middle.add(right, BorderLayout.EAST);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, middle, bottom);
        split.setResizeWeight(0.75);

        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.setTransferHandler(new Drop());
        root.setTransferHandler(new Drop());
        clipList.setTransferHandler(new Drop());
        preview.setTransferHandler(new Drop());
        timeline.setTransferHandler(new Drop());
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (okToDiscard()) {
                    stopPlaying();
                    System.exit(0);
                }
            }
        });
        installKeys();
        frame.setSize(1280, 800);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        if (Media.ffmpeg() == null) {
            JOptionPane.showMessageDialog(frame, "ffmpeg isn't installed, so videos can't be shown or played here.\n"
                    + "You can still place reactions by time. Install ffmpeg (sudo apt install ffmpeg, brew install ffmpeg,\n"
                    + "or ffmpeg.org on Windows) and start the editor again to see and hear the videos.", TITLE, JOptionPane.WARNING_MESSAGE);
        }
        updateTitle();
    }

    // ---- videos ---------------------------------------------------------------------------------

    private void addDialog() {
        JFileChooser fc = new JFileChooser();
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new FileNameExtensionFilter("Videos", "mp4", "mkv", "webm", "mov", "avi", "m4v", "flv", "wmv", "ts"));
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) addVideos(List.of(fc.getSelectedFiles()));
    }

    private void addVideos(List<File> files) {
        Project.Clip last = null;
        for (File f : files) {
            if (f.isDirectory()) continue;
            if (f.getName().toLowerCase(Locale.ROOT).endsWith(".wiireact")) {
                if (okToDiscard()) open(f.toPath());
                return;
            }
            Project.Clip c = new Project.Clip(f.toPath());
            project.clips.add(c);
            clipModel.addElement(c);
            last = c;
            // its length, in the background
            new Thread(() -> {
                double d = Media.duration(c.file);
                SwingUtilities.invokeLater(() -> {
                    c.duration = d;
                    if (c == clip) {
                        timeline.setClip(c);
                        updateTime();
                    }
                });
            }, "probe").start();
        }
        if (last != null) {
            clipList.setSelectedValue(last, true);
            changed();
        }
    }

    private void removeClip() {
        if (clip == null) return;
        if (!clip.cues.isEmpty() && JOptionPane.showConfirmDialog(frame, "Remove \"" + clip.title + "\" and its " + clip.cues.size()
                + " reactions?", TITLE, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        project.clips.remove(clip);
        clipModel.removeElement(clip);
        showClip(null);
        changed();
    }

    private void showClip(Project.Clip c) {
        stopPlaying();
        clip = c;
        selected = null;
        playhead = 0;
        clipTitle.setText(c == null ? "" : c.title);
        clipTitle.setEnabled(c != null);
        timeline.setClip(c);
        preview.image = null;
        updateSelectedInfo();
        updateTime();
        if (c != null) requestFrame(0);
        preview.repaint();
    }

    // ---- playing ----------------------------------------------------------------------------------

    private void seek(double t) {
        if (clip == null) return;
        double len = timeline.length();
        playhead = Math.max(0, Math.min(len, t));
        boolean was = player != null;
        stopPlaying();
        timeline.setPlayhead(playhead);
        updateTime();
        if (was) startPlaying();
        else requestFrame(playhead);
        preview.repaint();
    }

    private void togglePlay() {
        if (player != null) stopPlaying();
        else startPlaying();
    }

    private void startPlaying() {
        if (clip == null) return;
        if (clip.duration > 0 && playhead >= clip.duration - 0.1) playhead = 0;
        try {
            player = new Media.Player(clip.file, playhead, Math.max(320, Math.min(1280, preview.getWidth())), img -> SwingUtilities.invokeLater(() -> {
                if (player != null) {
                    preview.image = img;
                    preview.repaint();
                }
            }));
            play.setText("⏸ Pause");
            clock.start();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), TITLE, JOptionPane.WARNING_MESSAGE);
        }
    }

    private void stopPlaying() {
        if (player != null) {
            playhead = player.position();
            player.stop();
            player = null;
        }
        clock.stop();
        play.setText("▶ Play");
    }

    private void tickPlayer() {
        if (player == null) return;
        playhead = player.position();
        if (clip.duration > 0 && playhead >= clip.duration) {
            stopPlaying();
            playhead = clip.duration;
        }
        timeline.setPlayhead(playhead);
        updateTime();
        preview.repaint();
    }

    /** The picture at t (only the latest asked for is shown). */
    private void requestFrame(double t) {
        if (clip == null) return;
        long ticket = frameRequest.incrementAndGet();
        Project.Clip c = clip;
        int w = Math.max(320, Math.min(1280, preview.getWidth()));
        new Thread(() -> {
            BufferedImage img = Media.frame(c.file, t, w);
            SwingUtilities.invokeLater(() -> {
                if (ticket == frameRequest.get() && c == clip && player == null) {
                    preview.image = img;
                    preview.repaint();
                }
            });
        }, "frame").start();
    }

    private void updateTime() {
        double len = clip == null ? 0 : clip.duration;
        time.setText(fmt(playhead) + " / " + (len > 0 ? fmt(len) : "?"));
    }

    static String fmt(double t) {
        int tenths = (int) Math.round(t * 10);
        return String.format("%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10);
    }

    // ---- reactions --------------------------------------------------------------------------------

    private void addCue(String text, String m) {
        if (clip == null) {
            JOptionPane.showMessageDialog(frame, "Add a video first (drag one in, or Add videos…).", TITLE, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (text == null || text.isBlank()) {
            says.requestFocusInWindow();
            return;
        }
        double at = Math.round(playhead * 10) / 10.0;
        Project.Cue c = new Project.Cue(at, ((Number) length.getValue()).doubleValue(), text.trim(), m);
        clip.cues.add(c);
        if (text.equals(says.getText())) says.setText("");
        select(c);
        timeline.revalidate();
        timeline.repaint();
        clipList.repaint();
        changed();
    }

    private void select(Project.Cue c) {
        selected = c;
        timeline.setSelected(c);
        updateSelectedInfo();
    }

    private void updateSelectedInfo() {
        boolean on = selected != null;
        selectedInfo.setText(on ? "<html><b>" + esc(selected.text) + "</b><br>at " + fmt(selected.at) + ", " + selected.mood + ", for "
                + String.format(Locale.ROOT, "%.1f", selected.duration) + " s</html>" : "(click one on the timeline)");
        editSel.setEnabled(on);
        deleteSel.setEnabled(on);
    }

    private void editCue(Project.Cue c) {
        if (c == null) return;
        JTextField text = new JTextField(c.text, 26);
        JComboBox<String> m = new JComboBox<>(Project.MOODS.toArray(new String[0]));
        m.setSelectedItem(c.mood);
        JSpinner at = new JSpinner(new SpinnerNumberModel(c.at, 0.0, 100000.0, 0.1));
        JSpinner len = new JSpinner(new SpinnerNumberModel(c.duration, 0.5, 60.0, 0.5));
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("WII-UU says"));
        p.add(text);
        p.add(new JLabel("Mood"));
        p.add(m);
        p.add(new JLabel("At (seconds)"));
        p.add(at);
        p.add(new JLabel("For (seconds)"));
        p.add(len);
        if (JOptionPane.showConfirmDialog(frame, p, "Reaction", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        c.text = text.getText().trim();
        c.mood = (String) m.getSelectedItem();
        c.at = ((Number) at.getValue()).doubleValue();
        c.duration = ((Number) len.getValue()).doubleValue();
        select(c);
        timeline.revalidate();
        timeline.repaint();
        changed();
    }

    private void deleteSelected() {
        if (clip == null || selected == null) return;
        clip.cues.remove(selected);
        select(null);
        timeline.revalidate();
        timeline.repaint();
        clipList.repaint();
        changed();
    }

    private void changed() {
        dirty = true;
        updateTitle();
        preview.repaint();
    }

    // ---- files ------------------------------------------------------------------------------------

    private void newProject() {
        if (!okToDiscard()) return;
        load(new Project(), null);
    }

    private void openDialog() {
        if (!okToDiscard()) return;
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("WII-UU reactions project (.wiireact)", "wiireact"));
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) open(fc.getSelectedFile().toPath());
    }

    private void open(Path p) {
        try {
            load(Project.load(p), p);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Could not open " + p.getFileName() + ": " + e.getMessage(), TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    private void load(Project p, Path file) {
        stopPlaying();
        project = p;
        projectFile = file;
        clipModel.clear();
        for (Project.Clip c : p.clips) clipModel.addElement(c);
        packTitle.setText(p.title);
        dirty = false;
        if (!p.clips.isEmpty()) clipList.setSelectedIndex(0);
        else showClip(null);
        updateTitle();
    }

    private boolean save(boolean as) {
        Path target = projectFile;
        if (as || target == null) {
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File(safeName(project.title) + ".wiireact"));
            if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return false;
            target = fc.getSelectedFile().toPath();
            if (!target.toString().toLowerCase(Locale.ROOT).endsWith(".wiireact")) target = Path.of(target + ".wiireact");
        }
        try {
            project.save(target);
            projectFile = target;
            dirty = false;
            updateTitle();
            return true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Could not save: " + e.getMessage(), TITLE, JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private boolean okToDiscard() {
        if (!dirty || project.clips.isEmpty()) return true;
        int a = JOptionPane.showConfirmDialog(frame, "Save the changes to \"" + project.title + "\" first?", TITLE, JOptionPane.YES_NO_CANCEL_OPTION);
        if (a == JOptionPane.CANCEL_OPTION || a == JOptionPane.CLOSED_OPTION) return false;
        return a == JOptionPane.NO_OPTION || save(false);
    }

    private void exportDialog() {
        if (!readyToExport()) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(safeName(project.title) + ".zip"));
        fc.setFileFilter(new FileNameExtensionFilter("WII-UU reactions pack (.zip)", "zip"));
        if (fc.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path zip = fc.getSelectedFile().toPath();
        if (!zip.toString().toLowerCase(Locale.ROOT).endsWith(".zip")) zip = Path.of(zip + ".zip");
        export(zip, "The pack is saved as " + zip.getFileName() + ".\nPut it in ~/.wiiuu/reactions on the WII-UU computer.");
    }

    /** Exports straight into this computer's WII-UU (~/.wiiuu/reactions). */
    private void sendToWiiuu() {
        if (!readyToExport()) return;
        Path dir = wiiuu.core.Config.defaultHome().resolve("reactions");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, "Could not make " + dir + ": " + e.getMessage(), TITLE, JOptionPane.ERROR_MESSAGE);
            return;
        }
        export(dir.resolve(safeName(project.title) + ".zip"), "Sent to WII-UU (" + dir + ").\n"
                + "On WII-UU, press − (or F5) to look again: the Reactions channel is on the home screen.");
    }

    private boolean readyToExport() {
        if (project.clips.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Add a video first.", TITLE, JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        int cues = 0;
        for (Project.Clip c : project.clips) cues += c.cues.size();
        return cues > 0 || JOptionPane.showConfirmDialog(frame, "WII-UU doesn't say anything yet. Export anyway?", TITLE,
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }

    private void export(Path zip, String doneMessage) {
        stopPlaying();
        JDialog d = new JDialog(frame, "Exporting…", true);
        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setStringPainted(true);
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        p.add(new JLabel("Writing " + zip.getFileName() + " (videos, captions, thumbnails)…"), BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        d.setContentPane(p);
        d.pack();
        d.setSize(Math.max(420, d.getWidth()), d.getHeight());
        d.setLocationRelativeTo(frame);
        long total = project.packSize();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                project.export(zip, n -> SwingUtilities.invokeLater(() -> bar.setValue((int) (n * 1000 / total))));
                return null;
            }

            @Override
            protected void done() {
                d.dispose();
                try {
                    get();
                    JOptionPane.showMessageDialog(frame, doneMessage, TITLE, JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    Throwable why = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(frame, "Export failed: " + why.getMessage(), TITLE, JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
        d.setVisible(true);
    }

    private void updateTitle() {
        frame.setTitle((dirty ? "• " : "") + project.title + (projectFile != null ? " (" + projectFile.getFileName() + ")" : "") + " - " + TITLE);
    }

    private static String safeName(String s) {
        String t = s.replaceAll("[^A-Za-z0-9._ -]+", "").trim().replace(' ', '-');
        return t.isEmpty() ? "reactions" : t;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Space, ← →, Delete and Ctrl+S, while not typing. */
    private void installKeys() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED || !frame.isActive()) return false;
            if ((e.isControlDown() || e.isMetaDown()) && e.getKeyCode() == KeyEvent.VK_S) {
                save(e.isShiftDown());
                return true;
            }
            if (e.getComponent() instanceof JTextComponent || e.getComponent() instanceof JSpinner) return false;
            switch (e.getKeyCode()) {
                case KeyEvent.VK_SPACE -> togglePlay();
                case KeyEvent.VK_LEFT -> seek(Math.max(0, playhead - (e.isShiftDown() ? 1 : 5)));
                case KeyEvent.VK_RIGHT -> seek(playhead + (e.isShiftDown() ? 1 : 5));
                case KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> deleteSelected();
                default -> {
                    return false;
                }
            }
            return true;
        });
    }

    private static javax.swing.event.DocumentListener onEdit(Runnable r) {
        return new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                r.run();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                r.run();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                r.run();
            }
        };
    }

    private static javax.swing.Icon dot(Color c) {
        return new javax.swing.Icon() {
            public void paintIcon(Component comp, Graphics g, int x, int y) {
                ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(c);
                g.fillOval(x, y + 1, 10, 10);
            }

            public int getIconWidth() {
                return 12;
            }

            public int getIconHeight() {
                return 12;
            }
        };
    }

    /** Videos (or a project) dropped anywhere on the window. */
    private final class Drop extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport s) {
            return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport s) {
            try {
                addVideos((List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /**
     * The picture, with what WII-UU says at this moment as it will look: the caption under the
     * video (as on the TV), and the GamePad's logo with its speech bubble in the corner.
     */
    private final class Preview extends JComponent {
        BufferedImage image;

        Preview() {
            setPreferredSize(new Dimension(640, 360));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int w = getWidth(), h = getHeight();
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
            int vx = 0, vy = 0, vw = w, vh = h;
            if (image != null) {
                double s = Math.min(w / (double) image.getWidth(), h / (double) image.getHeight());
                vw = (int) (image.getWidth() * s);
                vh = (int) (image.getHeight() * s);
                vx = (w - vw) / 2;
                vy = (h - vh) / 2;
                g.drawImage(image, vx, vy, vw, vh, null);
            } else {
                g.setColor(new Color(0x8A9099));
                g.setFont(getFont().deriveFont(15f));
                String msg = clip == null ? "Drag videos here" : Media.ffmpeg() == null ? "Install ffmpeg to see the video" : "…";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2);
            }
            if (clip == null) return;
            Project.Cue on = null;
            for (Project.Cue c : clip.cues) if (c.at <= playhead && playhead < c.at + c.duration) on = c;
            if (on == null || on.text.isBlank()) return;
            // the caption, as mpv shows it on the TV
            String cap = "WII-UU: " + on.text;
            g.setFont(getFont().deriveFont(Font.BOLD, Math.max(14f, vh / 18f)));
            FontMetrics fm = g.getFontMetrics();
            int cx = vx + (vw - fm.stringWidth(cap)) / 2, cy = vy + vh - vh / 12;
            g.setColor(new Color(0x00A8E8));
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            java.awt.font.TextLayout tl = new java.awt.font.TextLayout(cap, g.getFont(), g.getFontRenderContext());
            java.awt.Shape outline = tl.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(cx, cy));
            g.draw(outline);
            g.setColor(Color.WHITE);
            g.fill(outline);
            // the GamePad: the logo and its bubble
            float s = Math.max(12f, vh / 22f);
            g.setFont(getFont().deriveFont(Font.BOLD, s));
            fm = g.getFontMetrics();
            int bx = vx + 12, by = vy + 12;
            int bw = fm.stringWidth(on.text) + 24, bh = fm.getHeight() + 12;
            int lw = fm.stringWidth("WII-UU") + 16;
            g.setColor(new Color(16, 20, 26, 210));
            g.fill(new RoundRectangle2D.Float(bx, by, lw + bw + 20, bh + 10, 16, 16));
            g.setColor(Color.WHITE);
            g.drawString("WII-", bx + 8, by + 5 + bh / 2 + fm.getAscent() / 2 - 2);
            g.setColor(new Color(0x00A8E8));
            g.drawString("UU", bx + 8 + fm.stringWidth("WII-"), by + 5 + bh / 2 + fm.getAscent() / 2 - 2);
            g.setColor(Color.WHITE);
            g.fill(new RoundRectangle2D.Float(bx + lw + 10, by + 5, bw, bh, bh, bh));
            g.setColor(new Color(0x202124));
            g.drawString(on.text, bx + lw + 22, by + 5 + bh / 2 + fm.getAscent() / 2 - 2);
            g.setColor(Timeline.MOOD_COLORS.getOrDefault(on.mood, Color.GRAY));
            g.fillOval(bx + lw + bw + 2, by + 8, 10, 10);
        }
    }
}
