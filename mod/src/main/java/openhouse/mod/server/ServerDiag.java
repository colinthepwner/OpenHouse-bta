package openhouse.mod.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class ServerDiag {
    private ServerDiag() { }

    static final Map<String, String> seen = new LinkedHashMap<>();
    static final int MAX_PLAYERS = 64, MAX_LINE = 16000;

    static void got(String name, byte[] d) {
        try {
            String line;
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(d, 1, d.length - 1))) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];

                for (int n; (n = in.read(buf)) > 0 && o.size() < MAX_LINE; ) o.write(buf, 0, n);
                line = new String(o.toByteArray(), StandardCharsets.UTF_8).replaceAll("[\\r\\n\\p{Cntrl}]", " ");
            }
            if (line.length() > MAX_LINE) line = line.substring(0, MAX_LINE) + "...";
            String at = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date());
            synchronized (seen) {
                seen.remove(name);
                seen.put(name, at + "  " + name + ": " + line);
                while (seen.size() > MAX_PLAYERS) seen.remove(seen.keySet().iterator().next());
                write();
            }
        } catch (Throwable ignored) { }
    }

    static void write() {
        try {
            File f = new File("openhouse", "players-diag.txt");
            f.getParentFile().mkdirs();
            StringBuilder b = new StringBuilder();
            for (String l : seen.values()) b.append(l).append('\n');
            Files.write(f.toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
    }
}
