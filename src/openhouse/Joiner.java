package openhouse;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class Joiner {
    public enum State {
        STARTING,
        SEARCHING,
        READY,
        NOT_FOUND,
        NO_ANSWER,
        FAILED,
        STOPPED
    }

    public final String code;
    private final File dataDir;
    private final String peerId;
    private final boolean relayOnly;
    private final Consumer<String> log;
    private volatile Consumer<Joiner> onChange = j -> { };

    public volatile State state = State.STARTING;
    public volatile String detail = "";
    public volatile int port;
    public volatile int connections;
    public volatile String path = "";
    public volatile boolean offline;

    private volatile Process child;
    private volatile OutputStream childIn;
    private volatile boolean stopping;

    public Joiner(File dataDir, String code, String peerId, boolean relayOnly, Consumer<String> log) {
        this.dataDir = dataDir;
        this.code = code;
        this.peerId = peerId;
        this.relayOnly = relayOnly;
        this.log = log;
    }

    public Joiner(File dataDir, String code, Consumer<String> log) {
        this(dataDir, code, Rtc.PREFIX + code, false, log);
    }

    public Joiner onChange(Consumer<Joiner> l) { onChange = l; return this; }

    public void start() {
        Thread t = new Thread(this::run, "openhouse joiner");
        t.setDaemon(true);
        t.start();
    }

    static final long RETRY_MS = 15000, RETRY_GAP_MS = 2000;
    private volatile long retryUntil;

    public void probe() {
        if (stopping) return;
        retryUntil = System.currentTimeMillis() + RETRY_MS;

        set(State.SEARCHING, "");
        sendProbe();
    }

    private void sendProbe() {
        OutputStream in = childIn;
        if (in == null) return;
        try {
            in.write("PROBE\n".getBytes(StandardCharsets.UTF_8));
            in.flush();
        } catch (IOException e) {

        }
    }

    public enum ModState { IDLE, LISTING, LISTED, DOWNLOADING, DONE, FAILED }
    public volatile ModState modState = ModState.IDLE;
    public volatile List<ModShare.Mod> modList;
    public volatile String hostVersion;
    public volatile String modError = "";
    public volatile long modBytesDone, modBytesTotal;
    private final java.util.Map<String, Long> modProgress = new java.util.concurrent.ConcurrentHashMap<>();

    public void requestModList() {
        modState = ModState.LISTING;
        modList = null;
        if (!send("MODLIST")) { modState = ModState.FAILED; modError = "the room connection isn't running"; }
    }

    public void downloadMods(File dir, List<ModShare.Mod> mods) {
        modState = ModState.DOWNLOADING;
        modProgress.clear();
        modBytesDone = 0;
        long total = 0;
        StringBuilder b = new StringBuilder();
        for (ModShare.Mod m : mods) {
            if (b.length() > 0) b.append(',');
            b.append(m.sha256).append(':').append(m.file);
            total += Math.max(0, m.size);
        }
        modBytesTotal = total;
        if (!send("MODGET\t" + dir.getAbsolutePath() + "\t" + b)) { modState = ModState.FAILED; modError = "the room connection isn't running"; }
    }

    public void downloadClose() {
        send("MODCLOSE");
    }

    private boolean send(String line) {
        OutputStream in = childIn;
        if (in == null) return false;
        try {
            synchronized (this) { in.write((line + "\n").getBytes(StandardCharsets.UTF_8)); in.flush(); }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void stop() {
        stopping = true;
        set(State.STOPPED, "");
        Process p = child;
        if (p != null) p.destroy();
    }

    public boolean alive() { return state != State.FAILED && state != State.STOPPED; }

    private void set(State s, String d) {
        if (stopping && s != State.STOPPED) return;
        state = s;
        detail = d == null ? "" : d;
        try { onChange.accept(this); } catch (Throwable ignored) { }
    }

    private void run() {
        try {
            File lib = new File(dataDir, "lib");
            List<File> libs = Rtc.libraries(lib, s -> set(State.STARTING, s));
            File self = Rtc.self();
            long backoff = 1000;
            while (!stopping) {
                long started = System.currentTimeMillis();
                List<String> args = new ArrayList<>();
                args.add(peerId);
                args.add(String.valueOf(port));
                if (relayOnly) args.add("--relay");
                Process p = Rtc.start(self, libs, "openhouse.rtc.JoinMain", args);
                child = p;
                childIn = p.getOutputStream();

                if (state != State.READY) { retryUntil = System.currentTimeMillis() + RETRY_MS; set(State.SEARCHING, ""); }
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) line(line);
                try { childIn.close(); } catch (IOException ignored) { }
                childIn = null;
                int exit = p.waitFor();
                child = null;
                connections = 0;
                if (stopping) break;

                backoff = System.currentTimeMillis() - started > 60000 ? 1000 : Math.min(backoff * 2, 30000);
                log.accept("room connection process ended (exit " + exit + "), restarting in " + backoff / 1000 + " s");
                Thread.sleep(backoff);
            }
        } catch (Exception e) {
            set(State.FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void probeAgainSoon() {
        Thread t = new Thread(() -> {
            try { Thread.sleep(RETRY_GAP_MS); } catch (InterruptedException e) { return; }
            if (!stopping && state == State.SEARCHING) sendProbe();
        }, "openhouse probe again");
        t.setDaemon(true);
        t.start();
    }

    private void line(String line) {
        int sp = line.indexOf(' ');
        String key = sp < 0 ? line : line.substring(0, sp);
        String rest = sp < 0 ? "" : line.substring(sp + 1);
        switch (key) {
            case "PORT":
                port = Integer.parseInt(rest.trim());
                set(state, detail);
                break;
            case "SIGNAL":
                if (offline) { offline = false; set(state, ""); }
                break;
            case "OFFLINE":
                offline = true;
                log.accept("can't reach the matchmaking server (" + rest + "), retrying");
                set(state, "Can't reach the matchmaking server - retrying...");
                break;
            case "FOUND":     retryUntil = 0; set(State.READY, ""); break;
            case "NOTFOUND":
            case "NOANSWER":
                if (System.currentTimeMillis() < retryUntil) { probeAgainSoon(); break; }
                set(key.equals("NOTFOUND") ? State.NOT_FOUND : State.NO_ANSWER, "");
                break;
            case "OPEN": {
                String[] f = rest.split(" ");
                connections = Integer.parseInt(f[0]);
                path = f.length > 1 ? f[1] : "";
                log.accept("connected through the room (" + path + ")");
                set(state, detail);
                break;
            }
            case "CLOSED":
                connections = Integer.parseInt(rest.trim());
                set(state, detail);
                break;
            case "LOG":
                log.accept(rest);
                break;
            case "MODLIST":
                try {
                    modList = rest.equals("none") ? new ArrayList<>() : ModShare.fromJson(rest);

                    String hv = null;
                    if (!rest.equals("none")) {
                        java.util.Map<String, Object> root = Json.obj(Json.parse(rest));
                        hv = root == null ? null : Json.str(root.get("openhouse"));
                    }
                    if (hv == null) for (ModShare.Mod m : modList) if ("openhouse".equals(m.id)) hv = m.version;
                    hostVersion = hv;
                    modState = ModState.LISTED;
                } catch (RuntimeException e) {
                    modError = "the host's mod list didn't make sense";
                    modState = ModState.FAILED;
                }
                set(state, detail);
                break;
            case "MODPROGRESS": {
                String[] f = rest.split(" ");
                if (f.length >= 2) {
                    modProgress.put(f[0], Long.parseLong(f[1]));
                    long sum = 0;
                    for (long v : modProgress.values()) sum += v;
                    modBytesDone = sum;
                }
                break;
            }
            case "MODGOT":
                break;
            case "MODSDONE":
                modBytesDone = modBytesTotal;
                modState = ModState.DONE;
                set(state, detail);
                break;
            case "MODSFAIL":
                modError = rest;
                modState = ModState.FAILED;
                log.accept("mods: " + rest);
                set(state, detail);
                break;
            default:
                if (!line.trim().isEmpty()) log.accept(line);
        }
    }
}
