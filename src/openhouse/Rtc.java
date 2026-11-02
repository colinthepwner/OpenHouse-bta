package openhouse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class Rtc {
    static final String VERSION = "0.20.0";
    static final String BASE = "https://repo1.maven.org/maven2/dev/onvoid/webrtc/webrtc-java/" + VERSION + "/";

    static final String[][] PINS = {
        { "", "7c2b8349fd1a628b7ced59106ab4dd3314dd0a0e0944ea0e740f84ac9c4ff349" },
        { "windows-x86_64", "f805996d5903b922289aef4387830b22e42d5600b4b3dfb8358edcdf0a025ee4" },
        { "windows-aarch64", "14fa6b791919ad6c066f88af19d148b59f36a04c41c9c4baf01317b49bd6a81b" },
        { "linux-x86_64", "b2f58cf5e8d27d0488ab3c5ab6e03261549850c92ed1e73527a138510df43137" },
        { "linux-aarch64", "fae8780cc769b950c735c64ec22e92789b6783dd84fabe93da4b43522383d509" },
        { "linux-aarch32", "21d9af0e3923331c00ef391b0ab6a4862d7a1c419573383ccfc1ba818beddc93" },
        { "macos-x86_64", "3ff48d08a8fb8b76ba307d4ed6d63f2c1043810ec9f49f6c9f5e01e97930d1ab" },
        { "macos-aarch64", "c9c2c1e1202b191b8e07bac48db31aef6cefdb4248a3dd58a3b04dfeaa2cfb09" },
    };

    public static final String PREFIX = "openhouse-";

    public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public static final int PICKED_MIN = 6, PICKED_MAX = 16;

    private Rtc() { }

    public static String normalizeCode(String typed) {
        if (typed == null) return null;
        StringBuilder b = new StringBuilder();
        for (char c : typed.toCharArray()) {
            if (c == ' ' || c == '-' || c == '\t' || c == '_') continue;
            b.append(Character.toUpperCase(c));
        }
        String c = b.toString();
        if (c.length() == 5) {
            for (int k = 0; k < 5; k++) if (ALPHABET.indexOf(c.charAt(k)) < 0) return null;
            return c;
        }
        return isPicked(c) ? c : null;
    }

    public static boolean isPicked(String c) {
        if (c == null || c.length() < PICKED_MIN || c.length() > PICKED_MAX) return false;
        for (int k = 0; k < c.length(); k++) {
            char ch = c.charAt(k);
            if (!((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9'))) return false;
        }
        return true;
    }

    public static String classifier() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String o = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : os.contains("linux") ? "linux" : null;
        String a = (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x86-64")) ? "x86_64"
                 : (arch.equals("aarch64") || arch.equals("arm64")) ? "aarch64"
                 : (arch.startsWith("arm") || arch.equals("aarch32")) ? "aarch32" : null;
        return o == null || a == null ? null : o + "-" + a;
    }

    static String pin(String classifier) {
        for (String[] p : PINS) if (p[0].equals(classifier)) return p[1];
        return null;
    }

    public static List<File> libraries(File libDir, Consumer<String> status) throws IOException {
        String cls = classifier();
        if (cls == null || pin(cls) == null)
            throw new IOException("room codes need WebRTC, which has no build for this system ("
                    + System.getProperty("os.name") + " " + System.getProperty("os.arch") + ")");
        List<File> out = new ArrayList<>();
        out.add(fetch(libDir, "", status));
        out.add(fetch(libDir, cls, status));
        return out;
    }

    private static File fetch(File dir, String classifier, Consumer<String> status) throws IOException {
        String name = "webrtc-java-" + VERSION + (classifier.isEmpty() ? "" : "-" + classifier) + ".jar";
        String what = classifier.isEmpty() ? "WebRTC" : "WebRTC for " + classifier;
        return fetchPinned(dir, BASE + name, name, pin(classifier), what, status);
    }

    public static final String BTA_SERVER_VERSION = "8.0.1";
    static final String BTA_SERVER_URL = "https://downloads.betterthanadventure.net/bta-server/release/v8.0.1/server.jar";
    static final String BTA_SERVER_SHA256 = "8a148b80ee71f54c328a5a0b9d4859ed6ff5310d3317405a14e74357da940106";

    public static File btaServer(File dir, Consumer<String> status) throws IOException {
        return fetchPinned(dir, BTA_SERVER_URL, "bta-server-" + BTA_SERVER_VERSION + ".jar", BTA_SERVER_SHA256, "the BTA server", status);
    }

    public static String adoptBtaServer(File dir, File folder, File skip) {
        File target = new File(dir, "bta-server-" + BTA_SERVER_VERSION + ".jar");
        if (target.isFile()) return null;
        File[] jars = folder.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        if (jars == null) return null;
        for (File j : jars) {

            if (j.equals(skip) || j.length() < 1_000_000 || j.length() > 100_000_000) continue;
            try {
                if (!BTA_SERVER_SHA256.equals(sha256(j))) continue;
                if (!dir.isDirectory() && !dir.mkdirs()) return null;
                File tmp = new File(dir, target.getName() + ".part");
                java.nio.file.Files.copy(j.toPath(), tmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return j.getName();
            } catch (IOException ignored) { }
        }
        return null;
    }

    static final String[][] LOADER = {
        { "https://maven.thesignalumproject.net/infrastructure/net/fabricmc/fabric-loader/0.18.4-bta.11/fabric-loader-0.18.4-bta.11.jar",
          "d498d0279a4a72fbc95b56a9c9f8be4ffc5838d516423ec11f21899abe86e4f9" },
        { "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.9/asm-9.9.jar",
          "03d99a74ad1ee5c71334ef67437f4ef4fe3488caa7c96d8645abc73c8e2017d4" },
        { "https://repo1.maven.org/maven2/org/ow2/asm/asm-analysis/9.9/asm-analysis-9.9.jar",
          "6a15d28e8bd29ba4fd5bca4baf9b50e8fba2d7b51fbf78cfa0c875a7214c678b" },
        { "https://repo1.maven.org/maven2/org/ow2/asm/asm-commons/9.9/asm-commons-9.9.jar",
          "db2f6f26150bbe7c126606b4a1151836bcc22a1e05a423b3585698bece995ff8" },
        { "https://repo1.maven.org/maven2/org/ow2/asm/asm-tree/9.9/asm-tree-9.9.jar",
          "42178f3775c9c63f9e5e1446747d29b4eca4d91bd6e75e5c43cfa372a47d38c6" },
        { "https://repo1.maven.org/maven2/org/ow2/asm/asm-util/9.9/asm-util-9.9.jar",
          "3842e13cfe324ee9ab7cdc4914be9943541ead397c17e26daf0b8a755bede717" },
        { "https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.17.0+mixin.0.8.7/sponge-mixin-0.17.0+mixin.0.8.7.jar",
          "e7889fcd185e4199052dcbcf0fe2128581cbf8630aea7feb37429667b1ba49a9" },
        { "https://repo1.maven.org/maven2/io/github/llamalad7/mixinextras-fabric/0.5.0/mixinextras-fabric-0.5.0.jar",
          "5f3f1313f8b3683c370a63c447c3722c5bc35e9bd16ad32e860ad9c786a60749" },
    };

    public static boolean coarseProgress(String line) {
        int at = line.lastIndexOf("... ");
        if (!line.startsWith("Downloading ") || at < 0 || !line.endsWith("%")) return true;
        try { return Integer.parseInt(line.substring(at + 4, line.length() - 1).trim()) % 25 == 0; } catch (NumberFormatException e) { return true; }
    }

    static final String FLATLAF_URL = "https://repo1.maven.org/maven2/com/formdev/flatlaf/3.6.2/flatlaf-3.6.2.jar";
    static final String FLATLAF_SHA256 = "32c79dee6d51341a3f8603598415841b4461fe535de2c34bcc8ee40e91e72267";

    public static File flatLaf(File dir, Consumer<String> status) throws IOException {
        return fetchPinned(dir, FLATLAF_URL, "flatlaf-3.6.2.jar", FLATLAF_SHA256, "the window's look (FlatLaf)", status);
    }

    public static List<File> babricLoader(File dir, Consumer<String> status) throws IOException {
        List<File> out = new ArrayList<>();
        for (String[] p : LOADER) {
            String name = p[0].substring(p[0].lastIndexOf('/') + 1);
            out.add(fetchPinned(dir, p[0], name, p[1], "the mod loader (" + name + ")", status));
        }
        return out;
    }

    private static final java.util.Map<String, Object> FETCHING = new java.util.HashMap<String, Object>();

    static File fetchPinned(File dir, String url, String name, String want, String what, Consumer<String> status) throws IOException {
        Object lock;
        synchronized (FETCHING) {
            String k = new File(dir, name).getAbsolutePath();
            lock = FETCHING.get(k);
            if (lock == null) { lock = new Object(); FETCHING.put(k, lock); }
        }
        synchronized (lock) {
            return fetchPinnedNow(dir, url, name, want, what, status);
        }
    }

    private static File fetchPinnedNow(File dir, String url, String name, String want, String what, Consumer<String> status) throws IOException {
        File f = new File(dir, name);
        if (f.isFile() && want.equals(sha256(f))) return f;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        status.accept("Downloading " + what + " (only once)...");
        File tmp = new File(dir, name + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "openhouse");
        int code;
        try { code = c.getResponseCode(); } catch (IOException e) { throw new IOException("can't download " + what + " (" + e.getMessage() + ")", e); }
        if (code != 200) throw new IOException("can't download " + what + ": HTTP " + code + " for " + name);
        long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            long got = 0;
            int lastPct = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                got += n;
                if (total > 0) {
                    int pct = (int) (got * 100 / total);

                    if (pct / 5 != lastPct / 5) { lastPct = pct; status.accept("Downloading " + what + " (only once)... " + pct + "%"); }
                }
            }
        }
        String got = sha256(tmp);
        if (!want.equals(got)) {
            tmp.delete();
            throw new IOException(name + " didn't match its expected checksum - not using it");
        }
        if (f.exists()) f.delete();
        if (!tmp.renameTo(f)) throw new IOException("can't move " + tmp + " to " + f);
        return f;
    }

    public static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    public static File self() throws IOException {
        try {
            java.security.CodeSource cs = Rtc.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null && "file".equals(cs.getLocation().getProtocol())) {
                File f = new File(cs.getLocation().toURI());
                if (f.isDirectory() ? new File(f, "openhouse/rtc/JoinMain.class").isFile() : f.isFile()) return f;
            }
        } catch (Exception ignored) { }
        throw new IOException("can't find the OpenHouse jar on disk to start WebRTC with");
    }

    static int javaMajor() {
        String v = System.getProperty("java.specification.version", "");
        try {
            return v.startsWith("1.") ? Integer.parseInt(v.substring(2)) : Integer.parseInt(v.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static Process start(File self, List<File> libs, String mainClass, List<String> args) throws IOException {
        StringBuilder cp = new StringBuilder(self.getAbsolutePath());
        for (File f : libs) cp.append(File.pathSeparator).append(f.getAbsolutePath());
        String java = new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath();
        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        cmd.add("-Xmx128m");

        if (javaMajor() >= 17) cmd.add("--enable-native-access=ALL-UNNAMED");

        cmd.add("-Djava.awt.headless=true");
        cmd.add("-cp");
        cmd.add(cp.toString());
        cmd.add(mainClass);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        return pb.start();
    }
}
