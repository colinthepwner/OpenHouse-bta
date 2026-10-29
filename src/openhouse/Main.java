package openhouse;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

public final class Main {
    public static void main(String[] args) throws Exception {
        boolean mcre = false, relay = false;
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (String a : args) {
            if (a.equals("--mcre")) mcre = true;
            else if (a.equals("--relay")) relay = true;
            else rest.add(a);
        }
        File dir = dataDir();
        if (rest.isEmpty()) {
            if (!GraphicsEnvironment.isHeadless()) { window(dir); return; }
            usage();
            return;
        }
        String cmd = rest.get(0).toLowerCase();
        if (cmd.equals("join") && rest.size() >= 2) {
            join(dir, rest.get(1), rest.size() > 2 ? Integer.parseInt(rest.get(2)) : 0, mcre, relay);
        } else if (cmd.equals("host")) {
            host(dir, rest.size() > 1 ? rest.get(1) : "localhost:25565", mcre);
        } else {
            usage();
            System.exit(2);
        }
    }

    static void usage() {
        System.out.println("OpenHouse helper - join and host Better than Adventure servers by room code\n"
                + "  java -jar openhouse-helper.jar join <CODE>      join a room, then Direct Connect to the address it shows\n"
                + "  java -jar openhouse-helper.jar host [address]   give your server a room code (default localhost:25565)\n"
                + "  java -jar openhouse-helper.jar                  open the join window");
    }

    static File dataDir() {
        try {
            File self = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File parent = self.isFile() ? self.getParentFile() : self;
            if (parent != null) return new File(parent, "openhouse");
        } catch (Exception ignored) { }
        return new File("openhouse");
    }

    static void say(String s) { synchronized (System.out) { System.out.println(s); System.out.flush(); } }

    static void join(File dir, String typed, int port, boolean mcre, boolean relay) throws Exception {
        String code = Rtc.normalizeCode(typed);
        if (code == null) {
            say("\"" + typed + "\" isn't a room code - codes are 5 letters/digits, like K7QXD (no 0, 1, I or O).");
            System.exit(2);
        }
        final Object done = new Object();
        final Joiner.State[] last = { null };
        final boolean[] shown = { false };
        Joiner j = new Joiner(dir, code, (mcre ? "mcre-beta-" : Rtc.PREFIX) + code, relay, s -> say("  " + s));
        j.onChange(x -> {
            Joiner.State s = x.state;
            if (s == Joiner.State.STARTING && !x.detail.isEmpty() && !x.detail.equals(lastDetail[0])) {
                lastDetail[0] = x.detail;
                say(x.detail);
            }
            if (s == last[0]) return;
            last[0] = s;
            switch (s) {
                case SEARCHING: say("Looking for room " + code + "..."); break;
                case READY:
                    if (!shown[0]) {
                        shown[0] = true;
                        say("");
                        say("Room " + code + " found. In BTA: Multiplayer > Direct Connect, and type:");
                        say("");
                        say("    localhost:" + x.port);
                        say("");
                        say("Keep this window open while you play (Ctrl+C to stop).");
                    } else say("Room " + code + " is still there.");
                    break;
                case NOT_FOUND:
                    say("No room " + code + " right now. Check the code, and that the host's server and room are running.");
                    synchronized (done) { done.notifyAll(); }
                    break;
                case NO_ANSWER:
                    say("Room " + code + " didn't answer. Try again in a moment.");
                    synchronized (done) { done.notifyAll(); }
                    break;
                case FAILED:
                    say("Can't join by room code: " + x.detail);
                    synchronized (done) { done.notifyAll(); }
                    break;
                default:
            }
        });
        j.start();
        synchronized (done) {
            while (j.alive() && j.state != Joiner.State.NOT_FOUND && j.state != Joiner.State.NO_ANSWER) done.wait(1000);
        }
        j.stop();
        System.exit(1);
    }

    private static final String[] lastDetail = { "" };

    static void host(File dir, String address, boolean mcre) throws Exception {
        if (mcre) {
            say("(--mcre is for joining only; hosting for MCRE is the MCRE bridge's job)");
            System.exit(2);
        }
        String target = address.startsWith("localhost") ? "127.0.0.1" + address.substring(9) : address;
        if (target.indexOf(':') < 0) target += ":25565";
        final File props = new File(dir, "host.properties");
        final Properties p = new Properties();
        if (props.isFile()) try (FileInputStream in = new FileInputStream(props)) { p.load(in); }
        say("Giving the BTA server at " + target + " a room code (Ctrl+C to stop)...");
        Host h = new Host(dir, p.getProperty("code", ""), p.getProperty("token", ""), target, Main::say, (c, t) -> {
            if (c.equals(p.getProperty("code")) && t.equals(p.getProperty("token"))) return;
            p.setProperty("code", c);
            p.setProperty("token", t);
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(props)) { p.store(out, "OpenHouse host"); } catch (Exception ignored) { }
        });
        h.start();
        Thread.currentThread().join();
    }

    static void window(File dir) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            JFrame f = new JFrame("OpenHouse");
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            JPanel p = new JPanel(new GridBagLayout());
            p.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(4, 4, 4, 4);
            g.fill = GridBagConstraints.HORIZONTAL;

            JTextField codeField = new JTextField(8);
            codeField.setFont(codeField.getFont().deriveFont(Font.BOLD, 18f));
            JButton joinButton = new JButton("Join");
            JLabel status = new JLabel("Type the room code the host gave you.");
            JTextField address = new JTextField(16);
            address.setEditable(false);
            address.setFont(address.getFont().deriveFont(Font.BOLD, 16f));
            JButton copy = new JButton("Copy");
            copy.setEnabled(false);
            JLabel hint = new JLabel(" ");

            g.gridx = 0; g.gridy = 0; p.add(new JLabel("Room code:"), g);
            g.gridx = 1; p.add(codeField, g);
            g.gridx = 2; p.add(joinButton, g);
            g.gridx = 0; g.gridy = 1; g.gridwidth = 3; p.add(status, g);
            g.gridy = 2; g.gridwidth = 2; p.add(address, g);
            g.gridx = 2; g.gridwidth = 1; p.add(copy, g);
            g.gridx = 0; g.gridy = 3; g.gridwidth = 3; p.add(hint, g);

            final Joiner[] current = { null };
            Consumer<Joiner> show = j -> SwingUtilities.invokeLater(() -> {
                if (current[0] != j) return;
                switch (j.state) {
                    case STARTING: status.setText(j.detail.isEmpty() ? "Starting..." : j.detail); break;
                    case SEARCHING: status.setText(j.offline ? j.detail : "Looking for room " + j.code + "..."); break;
                    case READY:
                        status.setText(j.connections > 0 ? "Connected (" + j.connections + ")." : "Room " + j.code + " found. In BTA: Multiplayer > Direct Connect:");
                        address.setText("localhost:" + j.port);
                        copy.setEnabled(true);
                        hint.setText("Keep this window open while you play.");
                        break;
                    case NOT_FOUND: status.setText("No room " + j.code + " right now - check the code."); joinButton.setEnabled(true); break;
                    case NO_ANSWER: status.setText("Room " + j.code + " didn't answer - try again."); joinButton.setEnabled(true); break;
                    case FAILED: status.setText("Can't join: " + j.detail); joinButton.setEnabled(true); break;
                    default:
                }
            });
            Runnable go = () -> {
                String code = Rtc.normalizeCode(codeField.getText());
                if (code == null) { status.setText("Codes are 5 letters/digits, like K7QXD (no 0, 1, I or O)."); return; }
                if (current[0] != null && current[0].code.equals(code) && current[0].alive()) {
                    current[0].probe();
                    return;
                }
                if (current[0] != null) current[0].stop();
                address.setText("");
                copy.setEnabled(false);
                hint.setText(" ");
                joinButton.setEnabled(false);
                Joiner j = new Joiner(dir, code, s -> System.out.println(s));
                current[0] = j;
                j.onChange(show);
                j.start();
                status.setText("Starting...");

                new javax.swing.Timer(1500, e -> { joinButton.setEnabled(true); ((javax.swing.Timer) e.getSource()).stop(); }).start();
            };
            joinButton.addActionListener(e -> go.run());
            codeField.addActionListener(e -> go.run());
            copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(address.getText()), null));

            f.getContentPane().add(p, BorderLayout.CENTER);
            f.pack();
            f.setResizable(false);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
