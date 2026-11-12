package openhouse.mod.server;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import openhouse.mod.OpenHost;
import net.minecraft.server.entity.player.PlayerServer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

public final class HostWindow {

    static Color accentFill() { return new Color(0xF2A541); }
    static Color accent() { return Laf.dark ? new Color(0xF2A541) : new Color(0xC2610C); }
    static Color ok() { return Laf.dark ? new Color(0x4ADE80) : new Color(0x15803D); }
    static Color warn() { return Laf.dark ? new Color(0xFBBF24) : new Color(0xB45309); }
    static Color bad() { return Laf.dark ? new Color(0xF87171) : new Color(0xB91C1C); }
    static Color info() { return Laf.dark ? new Color(0x60A5FA) : new Color(0x1D4ED8); }
    static Color consoleBg() { return Laf.dark ? new Color(0x111318) : new Color(0xFFFDF8); }
    static Color consoleFg() { return Laf.dark ? new Color(0xD9DCE1) : new Color(0x2A2620); }
    static Color dim() { return Laf.dark ? new Color(0x7C8493) : new Color(0x776D60); }

    static volatile HostWindow current;
    public static final AtomicLong TICKS = new AtomicLong();
    static final long STARTED = System.currentTimeMillis();

    final JFrame frame = new JFrame("OpenHost");
    final ConcurrentLinkedQueue<LogEvent> incoming = new ConcurrentLinkedQueue<>();
    final List<Line> lines = new ArrayList<>();
    final JTextPane console = new JTextPane();
    final JTextField filter = new JTextField();
    final GhostField input = new GhostField();
    final JLabel hint = new JLabel(" ");
    final JLabel status = new JLabel();
    final JLabel code = new JLabel("-----");
    final JLabel codeNote = new JLabel(" ");

    final JLabel pwState = new JLabel(" ");
    final JCheckBox whitelistBox = new JCheckBox("Only players on the whitelist", ServerRoom.get().whitelist);
    final JLabel chipPlayers = chip(), chipTps = chip(), chipMem = chip(), chipUp = chip();
    final DefaultListModel<PlayerRow> players = new DefaultListModel<>();
    final JList<PlayerRow> playerList = new JList<>(players);
    final JTabbedPane side = new JTabbedPane();
    final JPanel playerCards = new JPanel(new java.awt.CardLayout());
    final List<String> history = new ArrayList<>();
    int historyAt = -1;
    boolean follow = true;

    final java.util.ArrayDeque<long[]> tickSamples = new java.util.ArrayDeque<>();
    boolean wasUp;
    long firstTickAt;
    final Suggest suggest = new Suggest();

    static final class Line {
        final long time; final Level level; final String text;
        Line(long time, Level level, String text) { this.time = time; this.level = level; this.text = text; }
    }

    public static void open() {
        SwingUtilities.invokeLater(() -> {
            if (current != null) { current.frame.toFront(); return; }
            Laf.apply();
            HostWindow w = new HostWindow();
            current = w;
            w.build();
        });
    }

    public static void close() {
        SwingUtilities.invokeLater(() -> {
            HostWindow w = current;
            current = null;
            if (w != null) w.frame.dispose();
        });
    }

    private static volatile boolean feedInstalled;

    static synchronized void installFeed() {
        if (feedInstalled) return;
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration cfg = ctx.getConfiguration();
            AbstractAppender a = new AbstractAppender("OpenHostWindow", null, null, true, Property.EMPTY_ARRAY) {
                @Override public void append(LogEvent e) {
                    HostWindow w = current;
                    if (w != null && e.getLevel().isMoreSpecificThan(Level.INFO)) w.incoming.add(e.toImmutable());
                }
            };
            a.start();
            cfg.addAppender(a);
            cfg.getRootLogger().addAppender(a, null, null);
            ctx.updateLoggers();
            feedInstalled = true;
        } catch (Throwable t) {
            ServerRoom.LOG.warn("couldn't show the log in the OpenHost window: {}", t.toString());
        }
    }

    void build() {
        installFeed();
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { askToStop(); }
        });
        Image icon = icon();
        if (icon != null) frame.setIconImage(icon);
        JPanel root = new JPanel(new BorderLayout());
        root.add(header(icon), BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, consolePanel(), sidebar());
        split.setResizeWeight(1.0);
        split.setBorder(null);
        split.setDividerSize(6);
        root.add(split, BorderLayout.CENTER);
        root.add(inputPanel(), BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.setSize(1100, 680);
        frame.setMinimumSize(new Dimension(760, 460));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        SwingUtilities.invokeLater(() -> split.setDividerLocation(frame.getWidth() - 330));
        input.requestFocusInWindow();
        new Timer(100, e -> drainLog()).start();
        new Timer(1000, e -> refresh()).start();
        refresh();

        String demo = System.getProperty("openhouse.openhost.demo");
        if (demo != null) SwingUtilities.invokeLater(() -> { input.setText(demo); input.setCaretPosition(demo.length()); });

        String tab = System.getProperty("openhouse.openhost.tab");
        if (tab != null) SwingUtilities.invokeLater(() -> {
            String[] t = tab.split(":");
            for (int i = 0; i < side.getTabCount(); i++) {
                if (!side.getTitleAt(i).startsWith(t[0])) continue;
                side.setSelectedIndex(i);
                java.awt.Component c = side.getComponentAt(i);
                if (t.length > 1 && c instanceof JScrollPane) ((JScrollPane) c).getVerticalScrollBar().setValue(Integer.parseInt(t[1]));
            }
        });
    }

    static Image icon() {
        try (java.io.InputStream in = HostWindow.class.getResourceAsStream("/assets/openhouse/gui/roomcode.png")) {
            return in == null ? null : javax.imageio.ImageIO.read(in);
        } catch (Exception e) {
            return null;
        }
    }

    JComponent header(Image icon) {
        JPanel h = new JPanel(new BorderLayout(16, 0)) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);

                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(Laf.dark
                        ? new java.awt.GradientPaint(0, 0, new Color(0x1C1F26), 0, getHeight(), new Color(0x2B2419))
                        : new java.awt.GradientPaint(0, 0, new Color(0xFFFFFF), 0, getHeight(), new Color(0xFBEFD9)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                g.setColor(accentFill());
                g.fillRect(0, getHeight() - 2, getWidth(), 2);
            }
        };
        h.setBorder(BorderFactory.createEmptyBorder(12, 16, 14, 16));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));

        JLabel sub = new JLabel("OpenHouse " + openhouse.Version.VERSION + "  ·  Better than Adventure " + openhouse.Rtc.BTA_SERVER_VERSION);
        sub.setForeground(dim());
        sub.setFont(Laf.ui(Font.PLAIN, 12));
        left.add(sub);
        left.add(Box.createHorizontalStrut(18));
        status.setFont(Laf.ui(Font.BOLD, 12));
        left.add(status);
        h.add(left, BorderLayout.WEST);

        JPanel chips = new JPanel();
        chips.setOpaque(false);
        chips.setLayout(new BoxLayout(chips, BoxLayout.X_AXIS));
        JLabel[] all = { chipPlayers, chipTps, chipMem, chipUp };
        for (int i = 0; i < all.length; i++) {
            all[i].setFont(Laf.ui(Font.PLAIN, 13));
            all[i].setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
            chips.add(all[i]);
            if (i < all.length - 1) chips.add(Box.createHorizontalStrut(10));
        }
        JPanel middle = new JPanel(new java.awt.GridBagLayout());
        middle.setOpaque(false);
        middle.add(chips);
        h.add(middle, BorderLayout.CENTER);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        JPanel codeBox = new JPanel(new BorderLayout());
        codeBox.setOpaque(false);
        JLabel cap = new JLabel("ROOM CODE");
        cap.setFont(Laf.ui(Font.BOLD, 10));
        cap.setForeground(dim());
        code.setFont(Laf.mono(Font.BOLD, 24));
        code.setForeground(accent());
        codeNote.setFont(Laf.ui(Font.PLAIN, 11));
        codeNote.setForeground(dim());
        codeBox.add(cap, BorderLayout.NORTH);
        codeBox.add(code, BorderLayout.CENTER);
        codeBox.add(codeNote, BorderLayout.SOUTH);
        right.add(codeBox);
        right.add(Box.createHorizontalStrut(10));
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> {
            String c = ServerRoom.get().liveCode;
            if (c == null) return;
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(c), null);
            copy.setText("Copied!");
            Timer t = new Timer(1400, x -> copy.setText("Copy"));
            t.setRepeats(false);
            t.start();
        });
        JButton change = new JButton("Change…");
        change.addActionListener(e -> changeCode());
        JPanel btns = new JPanel(new GridLayout(2, 1, 0, 4));
        btns.setOpaque(false);
        btns.add(copy);
        btns.add(change);
        right.add(btns);
        h.add(right, BorderLayout.EAST);
        return h;
    }

    static JLabel chip() {
        JLabel l = new JLabel(" ") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int h = getHeight();
                if (Laf.dark) {
                    g2.setColor(new Color(0x262A33));
                    g2.fillRoundRect(0, 0, getWidth(), h, h, h);
                } else {

                    g2.setColor(Color.WHITE);
                    g2.fillRoundRect(0, 0, getWidth() - 1, h - 1, h, h);
                    g2.setColor(new Color(0xE3D6C1));
                    g2.drawRoundRect(0, 0, getWidth() - 1, h - 1, h, h);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        l.setOpaque(false);
        l.setFont(Laf.ui(Font.PLAIN, 12));
        l.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        return l;
    }

    JComponent consolePanel() {
        JPanel p = new JPanel(new BorderLayout());
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(8, 12, 6, 8));
        JLabel t = new JLabel("Console");
        t.setFont(Laf.ui(Font.BOLD, 13));
        bar.add(t, BorderLayout.WEST);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        filter.setColumns(16);
        filter.putClientProperty("JTextField.placeholderText", "Filter the log…");
        filter.putClientProperty("JTextField.showClearButton", true);
        filter.getDocument().addDocumentListener(onChange(this::rebuildConsole));
        JCheckBox auto = new JCheckBox("Follow", true);
        auto.addActionListener(e -> follow = auto.isSelected());
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> { lines.clear(); rebuildConsole(); });
        tools.add(filter);
        tools.add(auto);
        tools.add(clear);
        bar.add(tools, BorderLayout.EAST);
        p.add(bar, BorderLayout.NORTH);
        console.setEditable(false);
        console.setBackground(consoleBg());
        console.setForeground(consoleFg());
        console.setFont(Laf.mono(Font.PLAIN, 13));
        console.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JScrollPane sp = new JScrollPane(console);
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Laf.dark ? new Color(0x2A2E37) : new Color(0xE3E5E8)));
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    JComponent sidebar() {
        side.setPreferredSize(new Dimension(320, 100));

        playerList.setCellRenderer(new PlayerCell());
        playerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        playerList.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { pop(e); }
            @Override public void mouseReleased(MouseEvent e) { pop(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) { PlayerRow r = playerList.getSelectedValue(); if (r != null) prefill("msg " + r.name + " "); }
            }
            void pop(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int i = playerList.locationToIndex(e.getPoint());
                if (i < 0) return;
                playerList.setSelectedIndex(i);
                playerMenu(players.get(i).name).show(playerList, e.getX(), e.getY());
            }
        });
        JPanel pp = new JPanel(new BorderLayout());
        JLabel empty = new JLabel("<html><div style='text-align:center'>No one's on yet.<br><br>Send your friends the room code -<br>they join with Multiplayer › Room Code.</div></html>", JLabel.CENTER);
        empty.setForeground(dim());
        empty.setFont(Laf.ui(Font.PLAIN, 13));
        playerCards.add(new JScrollPane(playerList), "list");
        playerCards.add(empty, "empty");
        pp.add(playerCards, BorderLayout.CENTER);
        JLabel tip = new JLabel("Right-click a player for actions · double-click to message");
        tip.setFont(Laf.ui(Font.PLAIN, 11));
        tip.setForeground(dim());
        tip.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        pp.add(tip, BorderLayout.SOUTH);
        side.addTab("Players", pp);

        DefaultListModel<String> mods = new DefaultListModel<>();
        try {
            List<ModContainer> all = new ArrayList<>(FabricLoader.getInstance().getAllMods());
            all.sort((a, b) -> a.getMetadata().getName().compareToIgnoreCase(b.getMetadata().getName()));
            for (ModContainer m : all) {
                String id = m.getMetadata().getId();
                if (id.equals("java") || id.equals("minecraft") || id.equals("fabricloader") || id.equals("mixinextras")) continue;
                mods.addElement(m.getMetadata().getName() + "   " + m.getMetadata().getVersion().getFriendlyString());
            }
        } catch (Throwable ignored) { }
        JList<String> modList = new JList<>(mods);
        modList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, s, f);
                c.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
                return c;
            }
        });
        JPanel mp = new JPanel(new BorderLayout());
        mp.add(new JScrollPane(modList), BorderLayout.CENTER);
        JLabel mtip = new JLabel("Add mods to the mods folder and restart · players who join get them");
        mtip.setFont(Laf.ui(Font.PLAIN, 11));
        mtip.setForeground(dim());
        mtip.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        mp.add(mtip, BorderLayout.SOUTH);
        side.addTab("Mods (" + mods.size() + ")", mp);
        side.addTab("Settings", settings());

        String demoTab = System.getProperty("openhouse.openhost.demotab");
        if (demoTab != null) try { side.setSelectedIndex(Integer.parseInt(demoTab)); } catch (RuntimeException ignored) { }
        return side;
    }

    JComponent settings() {

        JPanel p = new WidthTracking();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        p.add(section("Quick actions"));
        JPanel q = new JPanel(new GridLayout(0, 2, 6, 6));
        q.setAlignmentX(0);

        q.add(tint(action("Save world", "save all"), 0x4ADE80, 0x15803D));
        q.add(tint(action("Day", "time set day"), 0xFBBF24, 0xB45309));
        q.add(tint(action("Night", "time set night"), 0xA78BFA, 0x6D28D9));
        q.add(tint(action("Clear weather", "weather clear"), 0x38BDF8, 0x0369A1));
        q.add(tint(action("List players", "list"), 0x2DD4BF, 0x0F766E));
        JButton stop = new JButton("Stop server");
        stop.addActionListener(e -> askToStop());
        q.add(solidRed(stop, 0xDC2626));
        q.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        p.add(q);
        p.add(Box.createVerticalStrut(16));

        p.add(section("Room code"));
        JCheckBox on = new JCheckBox("Players can join with the room code", ServerRoom.get().enabled);
        on.setAlignmentX(0);
        on.addActionListener(e -> run(on.isSelected() ? "oh on" : "oh off"));
        p.add(on);
        JButton pick = new JButton("Pick a code of your own…");
        pick.setAlignmentX(0);
        pick.addActionListener(e -> changeCode());
        p.add(Box.createVerticalStrut(6));
        p.add(pick);
        p.add(Box.createVerticalStrut(16));

        p.add(section("Who can join"));
        pwState.setAlignmentX(0);
        pwState.setFont(Laf.ui(Font.PLAIN, 12));
        p.add(pwState);
        p.add(Box.createVerticalStrut(6));
        JPanel pw = new JPanel(new GridLayout(1, 2, 6, 0));
        pw.setAlignmentX(0);
        JButton setPw = new JButton("Set password…");
        setPw.addActionListener(e -> setPassword());
        JButton noPw = new JButton("Remove");
        noPw.addActionListener(e -> {
            if (ServerRoom.get().password.isEmpty()) return;
            ServerRoom.get().setPassword("");
            ServerRoom.LOG.info("the server password was removed");
            note("Password removed - anyone with the code can join.");
            refresh();
        });
        pw.add(setPw);
        pw.add(noPw);
        pw.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        p.add(pw);
        p.add(Box.createVerticalStrut(10));
        whitelistBox.setAlignmentX(0);
        whitelistBox.addActionListener(e -> {
            ServerRoom.get().setWhitelist(whitelistBox.isSelected());
            note(whitelistBox.isSelected() ? "Whitelist on - only players on it (and operators) can join." : "Whitelist off - anyone with the code can join.");
        });
        p.add(whitelistBox);
        JButton editWl = new JButton("Edit whitelist…");
        editWl.setAlignmentX(0);
        editWl.addActionListener(e -> editWhitelist());
        p.add(Box.createVerticalStrut(6));
        p.add(editWl);
        p.add(Box.createVerticalStrut(16));

        p.add(section("Memory"));
        long ram = totalRam();
        JLabel memNow = new JLabel("This run: " + gb(Runtime.getRuntime().maxMemory()) + (ram > 0 ? "  ·  this computer: " + gb(ram) : ""));
        memNow.setFont(Laf.ui(Font.PLAIN, 12));
        memNow.setForeground(dim());
        memNow.setAlignmentX(0);
        p.add(memNow);
        p.add(Box.createVerticalStrut(6));
        java.util.List<String[]> choices = memoryChoices(ram);
        javax.swing.JComboBox<String> mem = new javax.swing.JComboBox<>();
        String cur = OpenHost.memoryValue(HostUi.read(OpenHost.MEMORY_KEY, "auto"));
        int sel = 0;
        for (int i = 0; i < choices.size(); i++) {
            mem.addItem(choices.get(i)[0]);
            if (cur != null && cur.equalsIgnoreCase(choices.get(i)[1])) sel = i;
        }
        mem.setSelectedIndex(sel);
        mem.setAlignmentX(0);
        mem.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        mem.addActionListener(e -> {
            String[] c = choices.get(mem.getSelectedIndex());
            HostUi.write(OpenHost.MEMORY_KEY, c[1]);
            note("Memory: " + c[0] + " from the next start - Restart now to use it straight away.");
        });
        p.add(mem);
        p.add(Box.createVerticalStrut(6));
        JButton restart = new JButton("Restart now");
        restart.setAlignmentX(0);
        restart.addActionListener(e -> askToRestart());
        p.add(restart);
        p.add(Box.createVerticalStrut(16));

        p.add(section("Window"));
        ButtonGroup g = new ButtonGroup();
        JRadioButton modern = new JRadioButton("Modern (this one)", true);
        JRadioButton classic = new JRadioButton("Classic BTA server window");
        JRadioButton cons = new JRadioButton("Console only (next start)");
        for (JRadioButton b : new JRadioButton[] { modern, classic, cons }) { g.add(b); b.setAlignmentX(0); p.add(b); }
        classic.addActionListener(e -> HostUi.switchTo("classic", null));
        cons.addActionListener(e -> {
            HostUi.save("console");
            note("The next start runs in console mode (openhost-ui=console in server.properties). This window stays for now.");
        });
        p.add(Box.createVerticalStrut(10));
        JCheckBox dark = new JCheckBox("Dark theme", Laf.dark);
        dark.setAlignmentX(0);
        dark.addActionListener(e -> { Laf.setDark(dark.isSelected()); recolor(); });
        p.add(dark);
        p.add(Box.createVerticalGlue());

        javax.swing.JTextArea file = new javax.swing.JTextArea("Saved as openhost-ui in server.properties: modern, classic or console.");
        file.setLineWrap(true);
        file.setWrapStyleWord(true);
        file.setEditable(false);
        file.setFocusable(false);
        file.setOpaque(false);
        file.setBorder(null);
        file.setFont(Laf.ui(Font.PLAIN, 11));
        file.setForeground(dim());
        file.setAlignmentX(0);
        p.add(Box.createVerticalStrut(12));
        p.add(file);
        JScrollPane sp = new JScrollPane(p);
        sp.setBorder(null);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return sp;
    }

    static final class WidthTracking extends JPanel implements javax.swing.Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) { return r.height; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    static JLabel section(String s) {
        JLabel l = new JLabel(s.toUpperCase(Locale.ROOT));
        l.setFont(Laf.ui(Font.BOLD, 11));
        l.setForeground(accent());
        l.setAlignmentX(0);
        l.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        return l;
    }

    JButton action(String label, String cmd) {
        JButton b = new JButton(label);
        b.addActionListener(e -> run(cmd));
        return b;
    }

    final List<Object[]> tinted = new ArrayList<>();

    static JButton solidRed(JButton b, int rgb) {
        b.setBackground(new Color(rgb));
        b.setForeground(Color.WHITE);
        return b;
    }

    JButton tint(JButton b, int onDark, int onLight) {
        tinted.add(new Object[] { b, new Color(onDark), new Color(onLight) });
        b.setForeground(Laf.dark ? new Color(onDark) : new Color(onLight));
        return b;
    }

    JComponent inputPanel() {
        JPanel p = new JPanel(new BorderLayout(10, 4));
        p.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        JLabel prompt = new JLabel("›");
        prompt.setFont(Laf.mono(Font.BOLD, 20));
        prompt.setForeground(accent());
        p.add(prompt, BorderLayout.WEST);
        input.setFont(Laf.mono(Font.PLAIN, 15));
        input.putClientProperty("JTextField.placeholderText", "Type a command");
        input.setFocusTraversalKeysEnabled(false);
        input.getDocument().addDocumentListener(onChange(() -> { historyAt = -1; suggest.schedule(); }));
        input.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { key(e); }
        });
        p.add(input, BorderLayout.CENTER);
        JButton go = new JButton("Run");
        go.addActionListener(e -> submit());
        p.add(go, BorderLayout.EAST);
        JPanel under = new JPanel(new BorderLayout());
        hint.setFont(Laf.mono(Font.PLAIN, 12));
        hint.setForeground(dim());
        under.add(hint, BorderLayout.CENTER);
        JLabel keys = new JLabel("Tab  complete     ↑↓  history     Enter  run");
        keys.setFont(Laf.ui(Font.PLAIN, 11));
        keys.setForeground(dim());
        under.add(keys, BorderLayout.EAST);
        under.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));
        p.add(under, BorderLayout.SOUTH);
        return p;
    }

    static DocumentListener onChange(Runnable r) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { r.run(); }
            public void removeUpdate(DocumentEvent e) { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    void key(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_TAB:
                e.consume();
                suggest.tab(e.isShiftDown());
                break;
            case KeyEvent.VK_ENTER:
                e.consume();
                if (suggest.popupShown() && suggest.picked) suggest.accept();
                else submit();
                break;
            case KeyEvent.VK_ESCAPE:
                suggest.hide();
                break;
            case KeyEvent.VK_UP:
                e.consume();
                if (suggest.popupShown()) suggest.move(-1);
                else historyStep(1);
                break;
            case KeyEvent.VK_DOWN:
                e.consume();
                if (suggest.popupShown()) suggest.move(1);
                else historyStep(-1);
                break;
            case KeyEvent.VK_RIGHT:

                if (input.getCaretPosition() == input.getText().length() && !input.ghost.isEmpty()) { e.consume(); suggest.acceptGhost(); }
                break;
        }
    }

    void historyStep(int dir) {
        if (history.isEmpty()) return;
        historyAt = Math.max(-1, Math.min(history.size() - 1, historyAt + dir));
        String t = historyAt < 0 ? "" : history.get(history.size() - 1 - historyAt);
        int keep = historyAt;
        input.setText(t);
        historyAt = keep;
        input.setCaretPosition(t.length());
    }

    void submit() {
        String t = input.getText().trim();
        if (t.isEmpty()) return;
        suggest.hide();

        String shown = ServerRoom.hideSecret(t);
        boolean secret = !shown.equals(t);
        if (!secret && (history.isEmpty() || !history.get(history.size() - 1).equals(t))) history.add(t);
        historyAt = -1;
        input.setText("");
        echo("> " + shown);
        run(t);
    }

    void run(String cmd) {
        MinecraftServer s = MinecraftServer.getInstance();
        if (s == null) { note("The server is still starting - try again in a moment."); return; }
        String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        s.addCommand(c, new net.minecraft.core.net.ICommandListener() {
            public void logInfo(String msg) { SwingUtilities.invokeLater(() -> echo(msg)); }
            public String getUsername() { return "OpenHost"; }
        });
    }

    void prefill(String t) {
        input.setText(t);
        input.setCaretPosition(t.length());
        input.requestFocusInWindow();
    }

    static long totalRam() {
        try {
            return ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        } catch (Throwable t) {
            return 0;
        }
    }

    static String gb(long bytes) {
        double g = bytes / (1024.0 * 1024 * 1024);
        return (g >= 10 || g == Math.rint(g) ? String.format(Locale.ROOT, "%.0f", g) : String.format(Locale.ROOT, "%.1f", g)) + " GB";
    }

    static java.util.List<String[]> memoryChoices(long ram) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        out.add(new String[] { ram > 0 ? "Automatic (about " + gb(ram / 4) + " here)" : "Automatic", "auto" });
        long top = ram > 0 ? ram - 2L * 1024 * 1024 * 1024 : 16L * 1024 * 1024 * 1024;
        for (int g : new int[] { 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64 })
            if (g == 1 || (long) g * 1024 * 1024 * 1024 <= top) out.add(new String[] { g + " GB", g + "G" });
        String cur = OpenHost.memoryValue(HostUi.read(OpenHost.MEMORY_KEY, "auto"));
        boolean listed = cur == null;
        for (String[] c : out) if (c[1].equalsIgnoreCase(cur)) listed = true;
        if (!listed) out.add(new String[] { cur.replace("G", " GB").replace("M", " MB"), cur });
        return out;
    }

    void askToRestart() {
        MinecraftServer s = MinecraftServer.getInstance();
        if (s == null || stopping) return;
        int n = s.playerList == null ? 0 : s.playerList.playerEntities.size();
        String live = ServerRoom.get().liveCode;
        String msg = "<html>Restart the server now? The world is saved first."
                + (n > 0 ? "<br>" + n + (n == 1 ? " player is" : " players are") + " on - they can join again in a minute." : "")
                + (live != null ? "<br><span style='color:#888'>The room code stays " + live + ".</span>" : "")
                + "</html>";
        if (JOptionPane.showConfirmDialog(frame, msg, "Restart", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
        stopping = true;
        note("Restarting - saving the world first...");
        refresh();
        ServerRoom.restart(s);
    }

    void setPassword() {
        javax.swing.JPasswordField f = new javax.swing.JPasswordField(18);
        Object[] msg = { "<html>Players type this once when they join (OpenHouse remembers it).<br>"
                + "<span style='color:#888'>Anyone already on stays.</span></html>", f };

        SwingUtilities.invokeLater(f::requestFocusInWindow);
        int r = JOptionPane.showConfirmDialog(frame, msg, "Server password", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        String pw = new String(f.getPassword());
        if (r != JOptionPane.OK_OPTION || pw.isEmpty()) return;
        ServerRoom.get().setPassword(pw);
        ServerRoom.LOG.info("the server password was changed");
        note("Password set - players type it once when they join; anyone already on stays.");
        refresh();
    }

    void editWhitelist() {
        ServerRoom room = ServerRoom.get();
        java.util.List<String> had = room.whitelisted();
        javax.swing.JTextArea a = new javax.swing.JTextArea(String.join("\n", had), 10, 18);
        Object[] msg = { "Player names, one per line:", new JScrollPane(a) };
        int r = JOptionPane.showConfirmDialog(frame, msg, "Whitelist", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        java.util.LinkedHashSet<String> want = new java.util.LinkedHashSet<>();
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (String n : a.getText().split("[\\s,]+")) {
            if (n.isEmpty()) continue;
            if (RoomCommand.validName(n)) want.add(n.toLowerCase(Locale.ROOT)); else bad.add(n);
        }
        int added = 0, removed = 0;
        for (String n : had) if (!want.contains(n) && room.whitelistRemove(n)) removed++;
        for (String n : want) if (room.whitelistAdd(n)) added++;
        note("Whitelist: " + added + " added, " + removed + " removed - " + room.whitelisted().size() + " on it."
                + (room.whitelist ? "" : " (It's off - tick \"Only players on the whitelist\".)")
                + (bad.isEmpty() ? "" : " Not names: " + String.join(", ", bad)));
    }

    void changeCode() {
        String cur = ServerRoom.get().liveCode;
        Object v = JOptionPane.showInputDialog(frame,
                "<html>A code of your own: " + openhouse.Rtc.PICKED_RULE + ".<br>"
                        + "<span style='color:#888'>If another server already uses it, you'll be told here.</span></html>",
                "Room code", JOptionPane.PLAIN_MESSAGE, null, null, cur != null && openhouse.Rtc.isPicked(cur) ? cur : "");
        if (v == null) return;
        String c = openhouse.Rtc.normalizeCode(v.toString());
        if (c == null || !openhouse.Rtc.isPicked(c)) {
            JOptionPane.showMessageDialog(frame, "That can't be a room code - use " + openhouse.Rtc.PICKED_RULE + ", like MYWORLD.", "Room code", JOptionPane.WARNING_MESSAGE);
            return;
        }
        run("oh set " + c);
    }

    void askToStop() {
        MinecraftServer s = MinecraftServer.getInstance();
        int n = s == null ? 0 : s.playerList.playerEntities.size();
        String msg = stopping ? "Still saving the world. Force stop without waiting for it?"
                : n > 0 ? "Stop the server? " + n + (n == 1 ? " player is" : " players are") + " on - the world is saved first."
                : "Stop the server? The world is saved first.";
        int[] choice = { 0 };
        javax.swing.JDialog[] dialog = new javax.swing.JDialog[1];

        JButton save = solidRed(new JButton("Stop & save"), 0xEF5350);
        save.addActionListener(e -> { choice[0] = 1; dialog[0].dispose(); });
        JButton force = new JButton("Force stop");
        solidRed(force, 0x991B1B);
        force.setToolTipText("Close right now without saving the world");
        force.addActionListener(e -> { choice[0] = 2; dialog[0].dispose(); });
        JButton cancel = new JButton(stopping ? "Keep waiting" : "Cancel");
        cancel.addActionListener(e -> dialog[0].dispose());
        Object[] buttons = stopping ? new Object[] { force, cancel } : new Object[] { save, force, cancel };
        JOptionPane pane = new JOptionPane(msg, JOptionPane.QUESTION_MESSAGE, JOptionPane.DEFAULT_OPTION, null, buttons, stopping ? cancel : save);
        dialog[0] = pane.createDialog(frame, "OpenHost");
        dialog[0].setVisible(true);
        if (choice[0] == 2) {
            ServerRoom.LOG.warn("Force stop - closing without saving the world");

            Runtime.getRuntime().halt(0);
        }
        if (choice[0] != 1) return;
        stopping = true;
        refresh();
        Thread t = new Thread(() -> {
            if (s != null) {
                s.initiateShutdown();
                while (!s.serverStopped) { try { Thread.sleep(100); } catch (InterruptedException ignored) { } }
            }
            System.exit(0);
        }, "OpenHost window stop");
        t.setDaemon(true);
        t.start();
    }
    volatile boolean stopping;

    JPopupMenu playerMenu(String name) {
        JPopupMenu m = new JPopupMenu();
        m.add(item("Message…", () -> prefill("msg " + name + " ")));
        m.add(item("Give…", () -> prefill("give " + name + " ")));
        m.add(item("Teleport to…", () -> prefill("tp " + name + " ")));
        m.add(item("Teleport to spawn", () -> run("tp " + name + " spawn")));
        JMenu gm = new JMenu("Game mode");
        for (String mode : new String[] { "survival", "creative" }) gm.add(item(Character.toUpperCase(mode.charAt(0)) + mode.substring(1), () -> run("gamemode " + mode + " " + name)));
        m.add(gm);

        m.add(item("Heal", () -> run("heal " + name + " 32768")));
        m.addSeparator();
        m.add(item("Make operator", () -> run("op " + name)));
        m.add(item("Remove operator", () -> run("deop " + name)));
        m.add(item("Add to whitelist", () -> run("oh whitelist add " + name)));
        m.addSeparator();
        m.add(item("Kick…", () -> {
            String why = JOptionPane.showInputDialog(frame, "Kick " + name + " - reason (optional):", "Kick", JOptionPane.PLAIN_MESSAGE);
            if (why != null) run(why.trim().isEmpty() ? "kick " + name : "oh kick " + name + " " + why.trim());
        }));
        m.add(item("Ban…", () -> {
            if (JOptionPane.showConfirmDialog(frame, "Ban " + name + "?", "Ban", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) run("ban username " + name);
        }));
        return m;
    }

    static JMenuItem item(String label, Runnable r) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(e -> r.run());
        return i;
    }

    void refresh() {
        MinecraftServer s = MinecraftServer.getInstance();
        ServerRoom room = ServerRoom.get();

        boolean up = s != null && TICKS.get() > 0 && !s.serverStopped;
        if (up && !wasUp) suggest.schedule();
        wasUp = up;
        String st = stopping || (s != null && s.serverStopped) ? "Stopping…" : up ? "Online" : "Starting…";
        Color sc = stopping ? warn() : up ? ok() : info();
        status.setText(st);
        status.setForeground(sc);

        String live = room.liveCode, taken = room.takenCode;
        if (!room.enabled) { code.setText("off"); codeNote.setText("room codes are off"); }
        else if (taken != null && live == null) { code.setText(taken); code.setForeground(bad()); codeNote.setText("taken by another server - change it"); }

        else if (live != null) { code.setText(live); code.setForeground(accent()); codeNote.setText(""); }
        else { code.setText("…"); code.setForeground(accent()); codeNote.setText(up ? "registering…" : " "); }
        frame.setTitle(live != null ? "OpenHost · " + live : "OpenHost");

        pwState.setText(room.password.isEmpty() ? "No password - anyone with the code can join" : "Password set - players need it to join");
        pwState.setForeground(room.password.isEmpty() ? dim() : ok());
        if (whitelistBox.isSelected() != room.whitelist) whitelistBox.setSelected(room.whitelist);

        long now = System.currentTimeMillis(), ticks = TICKS.get();
        if (ticks > 0 && firstTickAt == 0) firstTickAt = now;
        tickSamples.addLast(new long[] { now, ticks });
        while (tickSamples.size() > 6) tickSamples.removeFirst();
        long[] old = tickSamples.peekFirst();
        double tps = now > old[0] ? Math.min(20.0, (ticks - old[1]) * 1000.0 / (now - old[0])) : 20.0;

        boolean steady = firstTickAt > 0 && now - firstTickAt > 8000;
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20, max = rt.maxMemory() >> 20;
        chipTps.setText(up && steady ? String.format(Locale.ROOT, "TPS %.1f", tps) : "TPS –");
        chipTps.setForeground(!up || !steady ? dim() : tps >= 18 ? ok() : tps >= 12 ? warn() : bad());

        double full = max > 0 ? (double) used / max : 0;
        Color uc = full < 0.5 ? ok() : full < 0.8 ? warn() : bad();
        chipMem.setText(String.format(Locale.ROOT, "<html><span style='color:#%06x'>%d</span> <span style='color:#%06x'>/ %d MB</span></html>",
                uc.getRGB() & 0xFFFFFF, used, dim().getRGB() & 0xFFFFFF, max));
        long secs = (now - STARTED) / 1000;
        chipUp.setText(String.format(Locale.ROOT, "%d:%02d:%02d", secs / 3600, (secs / 60) % 60, secs % 60));

        List<PlayerRow> rows = new ArrayList<>();
        if (s != null) {
            try {
                for (PlayerServer p : new ArrayList<>(s.playerList.playerEntities)) rows.add(new PlayerRow(p));
            } catch (Throwable ignored) { }
        }
        chipPlayers.setText(rows.size() + (rows.size() == 1 ? " player" : " players"));
        String sel = playerList.getSelectedValue() == null ? null : playerList.getSelectedValue().name;
        boolean same = rows.size() == players.size();
        for (int i = 0; same && i < rows.size(); i++) same = rows.get(i).equals(players.get(i));
        if (!same) {
            players.clear();
            for (PlayerRow r : rows) players.addElement(r);
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).name.equals(sel)) playerList.setSelectedIndex(i);
        }
        side.setTitleAt(0, "Players (" + rows.size() + ")");
        ((java.awt.CardLayout) playerCards.getLayout()).show(playerCards, rows.isEmpty() ? "empty" : "list");
    }

    static final class PlayerRow {
        final String name, detail;
        PlayerRow(PlayerServer p) {
            name = p.username;
            String mode = "";
            try { mode = p.gamemode == null ? "" : LazyCommands.shortName(p.gamemode.getId()); } catch (Throwable ignored) { }
            String dim = p.dimension == 0 ? "Overworld" : p.dimension == 1 ? "Nether" : "dimension " + p.dimension;
            detail = (mode.isEmpty() ? "" : mode + " · ") + dim + String.format(Locale.ROOT, " · %d %d %d", (int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z))
                    + " · " + p.getHealth() + " HP";
        }
        @Override public boolean equals(Object o) { return o instanceof PlayerRow && ((PlayerRow) o).name.equals(name) && ((PlayerRow) o).detail.equals(detail); }
        @Override public int hashCode() { return name.hashCode(); }
    }

    static final class PlayerCell extends JPanel implements javax.swing.ListCellRenderer<PlayerRow> {
        PlayerRow row;
        boolean selected;
        PlayerCell() { setPreferredSize(new Dimension(10, 48)); }
        public Component getListCellRendererComponent(JList<? extends PlayerRow> l, PlayerRow v, int i, boolean s, boolean f) {
            row = v;
            selected = s;
            setBackground(s ? l.getSelectionBackground() : l.getBackground());
            return this;
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (row == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            float hue = (row.name.hashCode() & 0xffff) / 65535f;
            g2.setColor(Color.getHSBColor(hue, 0.45f, 0.75f));
            g2.fillOval(10, 8, 32, 32);
            g2.setColor(Color.WHITE);
            g2.setFont(Laf.ui(Font.BOLD, 15));
            String ini = row.name.isEmpty() ? "?" : row.name.substring(0, 1).toUpperCase(Locale.ROOT);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(ini, 26 - fm.stringWidth(ini) / 2, 24 + fm.getAscent() / 2 - 2);
            g2.setColor(selected ? UIManager.getColor("List.selectionForeground") : UIManager.getColor("List.foreground"));
            g2.setFont(Laf.ui(Font.BOLD, 13));
            g2.drawString(row.name, 52, 22);
            g2.setColor(dim());
            g2.setFont(Laf.ui(Font.PLAIN, 11));
            g2.drawString(row.detail, 52, 38);
            g2.dispose();
        }
    }

    void drainLog() {
        LogEvent e;
        int n = 0;
        while ((e = incoming.poll()) != null && n++ < 500) {
            String text = e.getMessage().getFormattedMessage();
            if (e.getThrown() != null) {
                java.io.StringWriter w = new java.io.StringWriter();
                e.getThrown().printStackTrace(new java.io.PrintWriter(w));
                text += "\n" + w.toString().trim();
            }
            String logger = e.getLoggerName();
            if (logger != null && !logger.isEmpty() && !logger.equals("LogUtils") && !logger.contains(".")) text = "[" + logger + "] " + text;
            Line l = new Line(e.getTimeMillis(), e.getLevel(), text);
            lines.add(l);
            if (matches(l)) append(l);
        }
        if (lines.size() > 6000) { lines.subList(0, lines.size() - 5000).clear(); rebuildConsole(); }
    }

    boolean matches(Line l) {
        String f = filter.getText().trim().toLowerCase(Locale.ROOT);
        return f.isEmpty() || l.text.toLowerCase(Locale.ROOT).contains(f);
    }

    void rebuildConsole() {
        console.setText("");
        for (Line l : lines) if (matches(l)) append(l);
    }

    void echo(String text) {
        Line l = new Line(System.currentTimeMillis(), Level.forName("CMD", 450), text);
        lines.add(l);
        append(l);
    }

    void note(String text) {
        Line l = new Line(System.currentTimeMillis(), Level.forName("NOTE", 450), text);
        lines.add(l);
        append(l);
    }

    void append(Line l) {
        StyledDocument d = console.getStyledDocument();
        try {
            SimpleAttributeSet time = new SimpleAttributeSet();
            StyleConstants.setForeground(time, dim());
            d.insertString(d.getLength(), String.format(Locale.ROOT, "%tT  ", l.time), time);
            Color c = consoleFg();
            String lvl = l.level.name();
            if (lvl.equals("WARN")) c = warn();
            else if (lvl.equals("ERROR") || lvl.equals("FATAL")) c = bad();
            else if (lvl.equals("CMD")) c = accent();
            else if (lvl.equals("NOTE")) c = info();

            if (lvl.equals("INFO") && l.text.indexOf('§') < 0) Highlight.append(d, l.text, c);
            else appendColoured(d, l.text, c);
            d.insertString(d.getLength(), "\n", null);
        } catch (BadLocationException ignored) { }
        if (follow) console.setCaretPosition(d.getLength());
    }

    static final class Highlight {
        static final java.util.regex.Pattern TOKENS = java.util.regex.Pattern.compile(
                "(?<tag>^\\[[A-Za-z][\\w .\\-]{0,30}\\])"
                + "|(?<ran>→\\s+.*$)"
                + "|(?<code>Room code:\\s+)(?<codeval>[A-Z0-9]{5,16})"
                + "|(?<chat>^<[^>\\s]{1,32}>)"
                + "|(?<quote>\"[^\"]*\")"
                + "|(?<dimname>Name: [^\\]]+)"

                + "|(?<path>(?<![\\w/])(?:[A-Za-z]:)?(?:/[\\w.\\-]+)+/?|[\\w\\-]+\\.(?:json|jar|txt|dat|properties|cfg|toml|log|png)\\b|\\*?:\\d{2,5}\\b)"
                + "|(?<good>\\b(?:Done|Online|ready)\\b)"
                + "|(?<num>\\b\\d+(?:[.,]\\d+)*(?:%|ns|ms|MB|GB|KB|s)?)");

        static Color pick(int dark, int light) { return new Color(Laf.dark ? dark : light); }

        static void append(StyledDocument d, String s, Color base) throws BadLocationException {
            java.util.regex.Matcher m = TOKENS.matcher(s);
            int at = 0;
            while (m.find()) {
                if (m.start() > at) put(d, s.substring(at, m.start()), base, false);
                if (m.group("tag") != null) {
                    String t = m.group("tag");
                    String name = t.substring(1, t.length() - 1);
                    put(d, t, name.equals("OpenHouse") ? accent() : Color.getHSBColor((name.hashCode() & 0xffff) / 65535f, 0.45f, Laf.dark ? 0.85f : 0.55f), false);
                } else if (m.group("ran") != null) put(d, m.group("ran"), pick(0x86EFAC, 0x15803D), false);
                else if (m.group("code") != null) { put(d, m.group("code"), base, false); put(d, m.group("codeval"), accent(), true); }
                else if (m.group("chat") != null) {
                    String t = m.group("chat");
                    float hue = (t.substring(1, t.length() - 1).hashCode() & 0xffff) / 65535f;
                    put(d, t, Color.getHSBColor(hue, 0.45f, Laf.dark ? 0.85f : 0.55f), true);
                }
                else if (m.group("quote") != null) put(d, m.group("quote"), pick(0x86EFAC, 0x15803D), false);
                else if (m.group("dimname") != null) put(d, m.group("dimname"), pick(0xFCD34D, 0xA16207), false);
                else if (m.group("path") != null) put(d, m.group("path"), pick(0xC4B5FD, 0x6D28D9), false);
                else if (m.group("good") != null) put(d, m.group("good"), pick(0x4ADE80, 0x15803D), true);
                else put(d, m.group("num"), pick(0x7DD3FC, 0x0369A1), false);
                at = m.end();
            }
            if (at < s.length()) put(d, s.substring(at), base, false);
        }

        static void put(StyledDocument d, String text, Color c, boolean bold) throws BadLocationException {
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setForeground(a, c);
            if (bold) StyleConstants.setBold(a, true);
            d.insertString(d.getLength(), text, a);
        }
    }

    static final int[] MC = { 0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF };

    void appendColoured(StyledDocument d, String s, Color base) throws BadLocationException {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, base);
        int i = 0, run = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '§' && i + 1 < s.length()) {
                d.insertString(d.getLength(), s.substring(run, i), a);
                char k = Character.toLowerCase(s.charAt(i + 1));
                int idx = "0123456789abcdef".indexOf(k);
                a = new SimpleAttributeSet();
                Color col = idx >= 0 ? new Color(MC[idx]) : base;

                if (Laf.dark && idx == 0) col = new Color(0x9AA1AD);
                else if (Laf.dark && idx == 1) col = new Color(0x7B8CFF);
                else if (Laf.dark && idx == 8) col = new Color(0x8A8F98);
                else if (!Laf.dark && idx == 15) col = consoleFg();
                StyleConstants.setForeground(a, k == 'r' ? base : col);
                i += 2;
                run = i;
                continue;
            }
            i++;
        }
        d.insertString(d.getLength(), s.substring(run), a);
    }

    static final int[][] TWINS = { { 0xF2A541, 0xC2610C }, { 0x4ADE80, 0x15803D }, { 0xFBBF24, 0xB45309 },
            { 0xF87171, 0xB91C1C }, { 0x60A5FA, 0x1D4ED8 }, { 0x7C8493, 0x776D60 }, { 0xD9DCE1, 0x2A2620 } };

    static void swapTheme(Component c) {
        if (c.isForegroundSet() && c.getForeground() != null) {
            int rgb = c.getForeground().getRGB() & 0xFFFFFF;
            for (int[] t : TWINS) {
                if (Laf.dark && rgb == t[1]) { c.setForeground(new Color(t[0])); break; }
                if (!Laf.dark && rgb == t[0]) { c.setForeground(new Color(t[1])); break; }
            }
        }
        if (c instanceof java.awt.Container) for (Component k : ((java.awt.Container) c).getComponents()) swapTheme(k);
    }

    void recolor() {
        console.setBackground(consoleBg());
        console.setForeground(consoleFg());
        SwingUtilities.updateComponentTreeUI(frame);
        for (Object[] t : tinted) ((JButton) t[0]).setForeground((Color) (Laf.dark ? t[1] : t[2]));
        swapTheme(frame.getContentPane());
        refresh();
        rebuildConsole();
        suggest.recolor();
    }

    static final class GhostField extends JTextField {
        String ghost = "";
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (ghost.isEmpty() || getCaretPosition() != getText().length()) return;
            try {
                Rectangle r = modelToView(getText().length());
                if (r == null) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont());
                g2.setColor(dim());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(ghost, r.x + 1, r.y + fm.getAscent() + (r.height - fm.getHeight()) / 2);
                g2.dispose();
            } catch (BadLocationException ignored) { }
        }
    }

    final class Suggest {
        final Timer debounce = new Timer(110, e -> update());
        List<String> options = new ArrayList<>();
        int at = 0;
        boolean picked;
        String base = "";

        JScrollPane popup;
        JList<String> list;
        boolean cycling;

        Suggest() { debounce.setRepeats(false); }

        void schedule() {
            if (cycling) return;
            picked = false;
            debounce.restart();
        }

        void update() {
            MinecraftServer s = MinecraftServer.getInstance();
            String t = input.getText();
            base = t;
            options = s == null || t.trim().isEmpty() ? new ArrayList<>() : LazyCommands.complete(s, t);
            at = 0;
            ghost();
            showPopup();
            explain(s, t);
        }

        void ghost() {
            String t = input.getText();
            String g = "";
            if (!options.isEmpty()) {
                String o = options.get(at);
                if (o.toLowerCase(Locale.ROOT).startsWith(t.toLowerCase(Locale.ROOT))) g = o.substring(t.length());
            }
            input.ghost = g;
            input.repaint();
        }

        void tab(boolean back) {
            if (options.isEmpty()) { update(); if (options.isEmpty()) return; }
            String t = input.getText();
            boolean showing = options.get(at).equalsIgnoreCase(t);
            if (showing && options.size() > 1) at = (at + (back ? options.size() - 1 : 1)) % options.size();
            set(options.get(at));
        }

        void acceptGhost() { if (!options.isEmpty()) set(options.get(at)); }

        void accept() { set(options.get(at)); picked = false; hide(); }

        void move(int d) {
            if (options.isEmpty()) return;
            at = (at + d + options.size()) % options.size();
            picked = true;
            list.setSelectedIndex(at);
            list.ensureIndexIsVisible(at);
            ghost();
        }

        void set(String text) {
            cycling = true;
            input.setText(text);
            input.setCaretPosition(text.length());
            cycling = false;
            if (list != null) { list.setSelectedIndex(at); list.ensureIndexIsVisible(at); }
            ghost();
            explain(MinecraftServer.getInstance(), text);
        }

        boolean popupShown() { return popup != null && popup.isVisible(); }

        void hide() {
            if (popup == null || !popup.isVisible()) return;
            popup.setVisible(false);
            frame.getLayeredPane().repaint(popup.getBounds());
        }

        void showPopup() {
            if (options.size() < 2 || !input.isShowing()) { hide(); return; }
            if (popup == null) {
                list = new JList<>();
                list.setFont(Laf.mono(Font.PLAIN, 13));

                list.setFocusable(false);
                list.addMouseListener(new MouseAdapter() {
                    @Override public void mouseClicked(MouseEvent e) {
                        int i = list.locationToIndex(e.getPoint());
                        if (i >= 0) { at = i; accept(); input.requestFocusInWindow(); }
                    }
                });
                popup = new JScrollPane(list);
                popup.setBorder(BorderFactory.createLineBorder(accentFill()));
                popup.setVisible(false);
                frame.getLayeredPane().add(popup, javax.swing.JLayeredPane.POPUP_LAYER);
            }
            DefaultListModel<String> m = new DefaultListModel<>();
            int cut = base.lastIndexOf(' ') + 1;
            for (String o : options) m.addElement(o.substring(Math.min(cut, o.length())));
            list.setModel(m);
            list.setSelectedIndex(at);
            int rows = Math.min(8, options.size());
            int h = rows * list.getFontMetrics(list.getFont()).getHeight() + 8;

            JComponent lp = frame.getLayeredPane();
            Point p = SwingUtilities.convertPoint(input, 0, 0, lp);
            int x = p.x;
            try {
                Rectangle r = input.modelToView(Math.min(cut, input.getText().length()));
                if (r != null) x += r.x;
            } catch (BadLocationException ignored) { }
            int w = Math.min(380, lp.getWidth());
            x = Math.max(0, Math.min(x, lp.getWidth() - w));
            int y = Math.max(0, p.y - h - 4);
            popup.setBounds(x, y, w, p.y - 4 - y);
            popup.setVisible(true);
            popup.revalidate();
            lp.repaint();
        }

        void recolor() { if (popup != null) SwingUtilities.updateComponentTreeUI(popup); }

        void explain(MinecraftServer s, String t) {
            if (t.trim().isEmpty() || s == null) { hint.setText(" "); return; }
            LazyCommands.Result r = LazyCommands.resolve(s, t);
            if (r != null) {
                hint.setForeground(r.changed ? accent() : ok());

                hint.setText(r.changed ? "→ runs:  " + r.command : "ready - Enter runs it");
                return;
            }
            String u = LazyCommands.usage(s, t);
            hint.setForeground(dim());
            hint.setText(u == null ? "not a command yet" : u);
        }
    }

    static final class Laf {
        static boolean dark = !"light".equalsIgnoreCase(HostUi.theme());
        static boolean flat;

        static void apply() {
            try {
                setup();
                flat = true;
            } catch (Throwable t) {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            }
        }

        static void setup() {
            java.util.Map<String, String> extra = new java.util.HashMap<>();
            extra.put("@accentColor", "#F2A541");
            if (!dark) {
                extra.put("@background", "#F6F1E8");
                extra.put("@foreground", "#2A2620");
                extra.put("@componentBackground", "#FFFFFF");
                extra.put("@buttonBackground", "#FFFFFF");
                extra.put("@selectionBackground", "#F8D9AE");
                extra.put("@selectionForeground", "#2A2620");
                extra.put("@selectionInactiveBackground", "#F1E4D0");
                extra.put("Component.borderColor", "#E0D5C3");
                extra.put("Component.focusedBorderColor", "#E8902A");
                extra.put("Component.focusColor", "#F6C88A");
                extra.put("Button.borderColor", "#E0D5C3");
                extra.put("List.background", "#FFFCF6");
                extra.put("TabbedPane.underlineColor", "#E8902A");
                extra.put("TabbedPane.inactiveUnderlineColor", "#E0D5C3");
                extra.put("TabbedPane.hoverColor", "#EFE5D4");
                extra.put("SplitPaneDivider.gripColor", "#C9BCA6");
                extra.put("ScrollBar.thumb", "#D8CCB8");
                extra.put("ScrollBar.hoverThumbColor", "#C9BBA3");
                extra.put("Separator.foreground", "#E6DCCB");
            }
            com.formdev.flatlaf.FlatLaf.setGlobalExtraDefaults(extra);
            if (dark) com.formdev.flatlaf.themes.FlatMacDarkLaf.setup(); else com.formdev.flatlaf.themes.FlatMacLightLaf.setup();
            UIManager.put("Component.arc", 10);
            UIManager.put("Button.arc", 10);
            UIManager.put("TextComponent.arc", 10);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("TabbedPane.showTabSeparators", true);
        }

        static void setDark(boolean d) {
            dark = d;
            HostUi.saveTheme(d ? "dark" : "light");
            if (!flat) return;
            try { setup(); } catch (Throwable ignored) { }
        }

        static Font ui(int style, int size) { return new Font(pick("Segoe UI", "SF Pro Text", "Inter", "Dialog"), style, size); }
        static Font mono(int style, int size) { return new Font(pick("Consolas", "JetBrains Mono", "Cascadia Mono", "Menlo", "DejaVu Sans Mono", "Monospaced"), style, size); }

        static String[] families;
        static String pick(String... names) {
            if (families == null) {
                try { families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(); } catch (Throwable t) { families = new String[0]; }
            }
            for (String n : names) for (String f : families) if (f.equalsIgnoreCase(n)) return f;
            return names[names.length - 1];
        }
    }
}
