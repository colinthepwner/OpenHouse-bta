package openhouse;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public final class Host {
    public interface Saver { void save(String code, String token); }

    private final File dataDir;
    private final String target;
    private final Consumer<String> log;
    private final Saver saver;
    private final String token;
    private final File shareManifest;
    private volatile String code;
    private volatile Process child;
    private volatile boolean stopping;

    public volatile int players;

    public Host(File dataDir, String code, String token, String target, Consumer<String> log, Saver saver) {
        this(dataDir, code, token, target, null, log, saver);
    }

    public Host(File dataDir, String code, String token, String target, File shareManifest, Consumer<String> log, Saver saver) {
        this.shareManifest = shareManifest;
        this.dataDir = dataDir;
        this.code = code == null ? "" : code;
        this.token = token == null || token.isEmpty() ? Long.toString(new java.security.SecureRandom().nextLong() & Long.MAX_VALUE, 36) : token;
        this.target = target;
        this.log = log;
        this.saver = saver;
    }

    public void start() {
        Thread t = new Thread(this::run, "openhouse host");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        stopping = true;
        Process p = child;
        if (p != null) p.destroy();
    }

    private void run() {
        try {
            List<File> libs = Rtc.libraries(new File(dataDir, "lib"), log);
            File self = Rtc.self();
            long backoff = 2000;
            while (!stopping) {
                long started = System.currentTimeMillis();
                Process p = Rtc.start(self, libs, "openhouse.rtc.HostMain",
                        Arrays.asList(code, target, token, shareManifest == null ? "" : shareManifest.getAbsolutePath()));
                child = p;
                OutputStream keep = p.getOutputStream();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("ROOM ")) {
                        String c = line.substring(5).trim();
                        if (!c.equals(code)) code = c;
                        saver.save(c, token);
                        log.accept("Room code: " + c + "   (players: Multiplayer > Room Code, or the OpenHouse helper)");
                    } else if (line.startsWith("PLAYERS ")) {
                        players = Integer.parseInt(line.substring(8).trim());
                        log.accept(players + (players == 1 ? " player" : " players") + " connected by room code");
                    } else if (line.startsWith("LOG ")) {
                        log.accept(line.substring(4));
                    } else if (!line.trim().isEmpty()) {
                        log.accept(line);
                    }
                }
                try { keep.close(); } catch (IOException ignored) { }
                int exit = p.waitFor();
                child = null;
                players = 0;
                if (stopping) break;

                backoff = System.currentTimeMillis() - started > 60000 ? 2000 : Math.min(backoff * 2, 60000);
                log.accept("room process ended (exit " + exit + "), restarting in " + backoff / 1000 + " s");
                Thread.sleep(backoff);
            }
        } catch (Exception e) {
            log.accept("room codes are off: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }
}
