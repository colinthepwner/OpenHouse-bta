package openhouse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class OhCrash {
    private OhCrash() { }

    public static final String FILE = "OHCRASH.txt";
    static final String HEAD =
            "# OpenHouse crash reports\n"
            + "# If enabled=true, I accept that when the game, OpenHouse or OpenHost crashes, a crash report is sent to the\n"
            + "# OpenHouse developer so it can be fixed. The same goes for things that went wrong, like a room that wouldn't\n"
            + "# open, a join or mod download that failed, or mods that wouldn't load. A report holds the error, the recent log,\n"
            + "# what OpenHouse did lately, the room and the names, versions and mod lists of the players in it, block and item\n"
            + "# ids, your time zone and language, and basic system info (OS, Java, memory, graphics card).\n"
            + "# Your user name in folder paths, IP addresses and passwords are taken out.\n"
            + "# A copy of every report is kept in openhouse/crash. Set enabled=false to stop sending them.\n";

    private static final String H = "DAI4WT6TrZL1DDEHKdmyluEUclA1n6qU61N2FW+Fu8b+/BNuATDYvNj/RW8RP5S137JeaBcg09efuRkWRyCXkNrbuXQ6Vi29ntKFdHRlatGmv4pqF1dCtcjHilR5BCTn4NCOwhoCNAfvmPDMKm0YIrWNxuwLQyVA0g==";
    static String hook() {
        byte[] x = java.util.Base64.getDecoder().decode(H), k = "dirt128".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < x.length; i++) x[i] = (byte) (x[i] ^ k[i % k.length] ^ ((i * 31) & 0xff));
        return new String(x, StandardCharsets.US_ASCII);
    }

    static final long START = System.currentTimeMillis();
    static final int MAX_PER_DAY = 8;
    static final long SAME_GAP = 6 * 3600_000L;

    public static boolean enabled(File dir) {
        if (dir == null) return false;
        File f = new File(dir, FILE);
        Properties p = new Properties();
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) { p.load(in); } catch (IOException e) { return true; }
            return !"false".equalsIgnoreCase(p.getProperty("enabled", "true").trim());
        }
        try { Files.write(f.toPath(), (HEAD + "enabled=true\n").getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
        return true;
    }

    public static final class Report {
        final String product, what;
        String trace, blame, bta;
        final Map<String, String> info = new LinkedHashMap<>();
        final List<String> log = new ArrayList<>();
        File mods;
        final String id = newId();

        public Report(String product, String what) { this.product = product; this.what = what; }
        public Report error(Throwable t) {
            if (t == null) return this;
            StringWriter w = new StringWriter();
            t.printStackTrace(new PrintWriter(w));
            trace = w.toString();
            return this;
        }
        public Report trace(String t) { trace = t; return this; }
        public Report trace(List<String> lines) { trace = lines == null ? null : String.join("\n", lines); return this; }
        public Report blame(String b) { blame = b; return this; }
        public Report bta(String v) { bta = v; return this; }
        public Report info(String k, Object v) { if (v != null && !String.valueOf(v).isEmpty()) info.put(k, String.valueOf(v)); return this; }
        public Report log(List<String> lines, int max) {
            if (lines == null) return this;
            int from = Math.max(0, lines.size() - max);
            log.addAll(lines.subList(from, lines.size()));
            return this;
        }
        public Report logFile(File f, int max) {
            if (f == null || !f.isFile()) return this;
            try { return log(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8), max); }
            catch (IOException | RuntimeException e) {
                try { return log(Arrays.asList(new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1).split("\r?\n")), max); }
                catch (IOException e2) { return this; }
            }
        }
        public Report mods(File dir) { mods = dir; return this; }

        String sigOver;
        public Report sig(String s) { sigOver = s; return this; }

        String sig() {
            if (sigOver != null) return sigOver;
            StringBuilder b = new StringBuilder(product).append('|');
            String t = trace == null ? what : trace;
            int frames = 0;
            for (String l : t.split("\r?\n")) {
                String s = l.trim();
                if (s.isEmpty()) continue;
                if (b.length() == product.length() + 1) { b.append(s.replaceAll(":.*", "")).append('|'); continue; }
                if (s.startsWith("at ")) { b.append(s.replaceAll("\\(.*", "")).append('|'); if (++frames >= 5) break; }
            }
            return sha(b.toString()).substring(0, 12);
        }

        String firstError() {
            if (trace == null) return null;
            for (String l : trace.split("\r?\n")) {
                String s = l.trim();
                if (!s.isEmpty()) return s.length() > 300 ? s.substring(0, 300) + "..." : s;
            }
            return null;
        }

        String firstFrame() {
            if (trace == null) return null;
            String first = null;
            for (String l : trace.split("\r?\n")) {
                String s = l.trim();
                if (!s.startsWith("at ")) continue;
                if (first == null) first = s;

                if (!s.matches("at (java|javax|jdk|sun|com\\.sun|net\\.minecraft|org\\.spongepowered|net\\.fabricmc|com\\.mojang|org\\.lwjgl)\\..*")) return s;
            }
            return first;
        }

        String text() {
            StringBuilder b = new StringBuilder();
            b.append("OpenHouse crash report ").append(id).append('\n');
            b.append("==============================\n");
            row(b, "What", what);
            row(b, "Where", product + (bta == null ? "" : " on BTA " + bta));
            row(b, "OpenHouse", Version.VERSION);
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            row(b, "When", f.format(new Date()) + " (running " + ((System.currentTimeMillis() - START) / 60000) + " min)");
            row(b, "System", sys());
            row(b, "Region", region());
            row(b, "Blamed", blame);
            row(b, "Signature", sig());
            for (Map.Entry<String, String> e : info.entrySet()) row(b, e.getKey(), e.getValue());
            if (trace != null) b.append("\n-- Error\n").append(trace.trim()).append('\n');
            List<String> j = journal();
            if (!j.isEmpty()) {
                b.append("\n-- OpenHouse journal (last ").append(j.size()).append(", oldest first)\n");
                for (String l : j) b.append(l).append('\n');
            }

            List<Map.Entry<String, java.util.function.Supplier<String>>> secs;
            synchronized (SECTIONS) { secs = new ArrayList<>(SECTIONS.entrySet()); }
            for (Map.Entry<String, java.util.function.Supplier<String>> e : secs) if (!e.getKey().startsWith("~")) section(b, e.getKey(), e.getValue());
            if (mods != null) b.append("\n-- Mods (").append(mods.getName()).append(")\n").append(modList(mods));
            if (!log.isEmpty()) {
                b.append("\n-- Log (last ").append(log.size()).append(" lines)\n");
                for (String l : log) b.append(l).append('\n');
            }

            for (Map.Entry<String, java.util.function.Supplier<String>> e : secs) if (e.getKey().startsWith("~")) section(b, e.getKey().substring(1), e.getValue());
            return scrub(b.toString());
        }

        String msg() {
            StringBuilder b = new StringBuilder();
            b.append("**").append(what).append("** · ").append(product).append(" · OH ").append(Version.VERSION);
            if (bta != null) b.append(" · BTA ").append(bta);
            b.append("\n").append(System.getProperty("os.name", "?")).append(" · Java ").append(System.getProperty("java.version", "?"));
            b.append(" · ").append(TimeZone.getDefault().getID());
            b.append(" · id `").append(id).append("` · sig `").append(sig()).append("`\n");
            if (blame != null) b.append("Blamed: **").append(blame).append("**\n");
            String e = firstError(), fr = firstFrame();
            if (e != null) b.append("```\n").append(e.replace("```", "'''")).append(fr == null ? "" : "\n  " + fr).append("\n```");
            String s = scrub(b.toString());
            if (s.length() > 1900) s = s.substring(0, 1900) + "...";

            return s + "\n· · · · · · · · · · · · · · · ·";
        }
    }

    static void section(StringBuilder b, String name, java.util.function.Supplier<String> s) {
        String v;
        try { v = s.get(); } catch (Throwable t) { v = "(couldn't read: " + t + ")"; }
        if (v == null || v.trim().isEmpty()) return;
        b.append("\n-- ").append(name).append('\n').append(v.trim()).append('\n');
    }

    static final Map<String, java.util.function.Supplier<String>> SECTIONS = new LinkedHashMap<>();
    public static void section(String name, java.util.function.Supplier<String> s) { synchronized (SECTIONS) { SECTIONS.put(name, s); } }

    static volatile File jdir;
    static final List<String> JMEM = new ArrayList<>();
    public static void journalDir(File dir) { jdir = dir == null ? null : new File(new File(dir, "openhouse"), "crash"); }
    public static void note(String what) {
        if (what == null) return;
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT);
        String line = f.format(new Date()) + "  " + what.replace('\n', ' ');
        synchronized (JMEM) {
            JMEM.add(line);
            if (JMEM.size() > 300) JMEM.remove(0);
            File d = jdir;
            if (d == null) return;
            try {
                d.mkdirs();
                File jf = new File(d, "journal.log");
                if (jf.length() > 200_000) {
                    List<String> old = Files.readAllLines(jf.toPath(), StandardCharsets.UTF_8);
                    Files.write(jf.toPath(), old.subList(old.size() / 2, old.size()), StandardCharsets.UTF_8);
                }
                Files.write(jf.toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException ignored) { }
        }
    }
    static List<String> journal() {
        File d = jdir;
        if (d != null) {
            File jf = new File(d, "journal.log");
            try {
                List<String> l = Files.readAllLines(jf.toPath(), StandardCharsets.UTF_8);
                return new ArrayList<>(l.subList(Math.max(0, l.size() - 150), l.size()));
            } catch (IOException | RuntimeException ignored) { }
        }
        synchronized (JMEM) { return new ArrayList<>(JMEM.subList(Math.max(0, JMEM.size() - 150), JMEM.size())); }
    }

    static void row(StringBuilder b, String k, String v) {
        if (v == null || v.isEmpty()) return;
        b.append(k).append(':');
        for (int i = k.length(); i < 11; i++) b.append(' ');
        b.append(v).append('\n');
    }

    static String sys() {
        Runtime r = Runtime.getRuntime();
        return System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "") + " " + System.getProperty("os.arch", "")
                + ", Java " + System.getProperty("java.version", "?") + " (" + System.getProperty("java.vendor", "?") + ")"
                + ", " + r.availableProcessors() + " cores, memory " + ((r.totalMemory() - r.freeMemory()) >> 20) + "/" + (r.maxMemory() >> 20) + " MB"
                + ", " + Locale.getDefault();
    }

    public static String region() {
        TimeZone z = TimeZone.getDefault();
        int off = z.getOffset(System.currentTimeMillis()) / 60000;
        return z.getID() + " (UTC" + (off >= 0 ? "+" : "-") + Math.abs(off) / 60 + (Math.abs(off) % 60 == 0 ? "" : ":" + Math.abs(off) % 60) + ")"
                + ", " + Locale.getDefault().getDisplayCountry(Locale.ROOT) + " / " + Locale.getDefault().getDisplayLanguage(Locale.ROOT);
    }

    static String modList(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return "(none)\n";
        Arrays.sort(fs, (a, c) -> a.getName().compareToIgnoreCase(c.getName()));
        StringBuilder b = new StringBuilder();
        for (File f : fs) {
            String n = f.getName(), ln = n.toLowerCase(Locale.ROOT);
            if (!f.isFile() || !(ln.endsWith(".jar") || ln.endsWith(".disabled") || ln.endsWith(".zip"))) continue;
            String id = "?", ver = "";
            try (ZipFile z = new ZipFile(f)) {
                ZipEntry e = z.getEntry("fabric.mod.json");
                if (e != null) {
                    try (InputStream in = z.getInputStream(e)) {
                        Map<String, Object> j = Json.obj(Json.parse(new String(readAll(in), StandardCharsets.UTF_8)));
                        if (j != null) { id = String.valueOf(Json.str(j.get("id"))); ver = String.valueOf(Json.str(j.get("version"))); }
                    }
                }
            } catch (IOException | RuntimeException ignored) { }
            String h = "";
            try { h = sha(Files.readAllBytes(f.toPath())).substring(0, 10); } catch (IOException | OutOfMemoryError ignored) { }
            b.append(String.format(Locale.ROOT, "%-24s %-22s %s  (%d KB, %s)%n", id, ver, n, f.length() >> 10, h));
        }
        return b.length() == 0 ? "(none)\n" : b.toString();
    }

    static final Pattern HOME = Pattern.compile("(?i)([\\\\/](?:Users|home)[\\\\/])[^\\\\/\\s\"':]+");
    static final Pattern IP = Pattern.compile("\\b(?!127\\.|0\\.0\\.0\\.0\\b|255\\.)(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");
    static final Pattern SECRET = Pattern.compile("(?i)(access_?token|session|password|passwd|token|secret|webhook)(\\s*[=:]\\s*|\\s+)(\"?)[^\\s\",}]+");
    static final Pattern HOOKS = Pattern.compile("https://(?:\\w+\\.)?discord(?:app)?\\.com/api/webhooks/\\S+");
    public static String scrub(String s) {
        if (s == null) return null;
        String home = System.getProperty("user.home"), user = System.getProperty("user.name");
        if (home != null && home.length() > 3) { s = s.replace(home, "~"); s = s.replace(home.replace('\\', '/'), "~"); }
        s = HOME.matcher(s).replaceAll("$1<user>");
        if (user != null && user.length() >= 3) s = s.replaceAll("(?i)\\b" + Pattern.quote(user) + "\\b(?=[\\\\/])", "<user>");
        s = IP.matcher(s).replaceAll("<ip>");
        s = SECRET.matcher(s).replaceAll("$1$2$3<hidden>");
        s = HOOKS.matcher(s).replaceAll("<webhook>");
        return s;
    }

    public static void send(File dir, Report r, boolean wait) {
        try {
            if (dir == null || r == null || !enabled(dir)) return;
            File cd = new File(new File(dir, "openhouse"), "crash");
            cd.mkdirs();
            note("REPORT " + r.id + ": " + r.what + (r.blame == null ? "" : " (blamed " + r.blame + ")"));
            String text = r.text(), msg = r.msg(), sig = r.sig();
            File rep = new File(cd, "pending-" + r.id + ".txt"), m = new File(cd, "pending-" + r.id + ".msg");
            if (!fresh(cd, sig)) {

                Files.write(new File(cd, r.id + ".txt").toPath(), text.getBytes(StandardCharsets.UTF_8));
                trim(cd);
                return;
            }
            Files.write(rep.toPath(), text.getBytes(StandardCharsets.UTF_8));
            Files.write(m.toPath(), msg.getBytes(StandardCharsets.UTF_8));
            Runnable go = () -> sendFile(cd, rep, m);
            if (wait) go.run();
            else { Thread t = new Thread(go, "OpenHouse crash report"); t.setDaemon(true); t.start(); }
        } catch (Throwable ignored) { }
    }

    public static void sendPending(File dir) {
        if (dir == null || !enabled(dir)) return;
        File cd = new File(new File(dir, "openhouse"), "crash");
        File[] fs = cd.listFiles((d, n) -> n.startsWith("pending-") && n.endsWith(".txt"));
        if (fs == null || fs.length == 0) return;
        Thread t = new Thread(() -> {
            for (File f : fs) {
                File m = new File(cd, f.getName().replace(".txt", ".msg"));
                if (!sendFile(cd, f, m)) break;
            }
        }, "OpenHouse crash report");
        t.setDaemon(true);
        t.start();
    }

    static synchronized boolean sendFile(File cd, File rep, File m) {
        try {
            if (!rep.isFile()) return true;
            byte[] body = Files.readAllBytes(rep.toPath());
            String msg = m.isFile() ? new String(Files.readAllBytes(m.toPath()), StandardCharsets.UTF_8) : "**OpenHouse crash report** (sent late)";
            String name = rep.getName().replace("pending-", "oh-crash-");
            if (!post(msg, name, body)) return false;
            File keep = new File(cd, rep.getName().replace("pending-", ""));
            keep.delete();
            if (!rep.renameTo(keep)) rep.delete();
            m.delete();
            trim(cd);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean post(String msg, String fileName, byte[] file) throws IOException {
        String bound = "----oh" + Long.toHexString(System.nanoTime());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", msg);
        payload.put("username", "OpenHouse crashes");
        Map<String, Object> am = new LinkedHashMap<>();
        am.put("parse", new ArrayList<>());
        payload.put("allowed_mentions", am);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String nl = "\r\n";
        out.write(("--" + bound + nl + "Content-Disposition: form-data; name=\"payload_json\"" + nl
                + "Content-Type: application/json" + nl + nl).getBytes(StandardCharsets.UTF_8));
        out.write(Json.write(payload).getBytes(StandardCharsets.UTF_8));
        out.write((nl + "--" + bound + nl + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"" + fileName + "\"" + nl
                + "Content-Type: text/plain; charset=utf-8" + nl + nl).getBytes(StandardCharsets.UTF_8));
        out.write(file.length > 7_000_000 ? Arrays.copyOfRange(file, file.length - 7_000_000, file.length) : file);
        out.write((nl + "--" + bound + "--" + nl).getBytes(StandardCharsets.UTF_8));
        byte[] all = out.toByteArray();

        String to = System.getenv("OPENHOUSE_TEST_HOOK");
        HttpURLConnection c = (HttpURLConnection) new URL(to != null && to.startsWith("http://127.0.0.1") ? to : hook()).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("User-Agent", "OpenHouse/" + Version.VERSION);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bound);
        c.setFixedLengthStreamingMode(all.length);
        try (OutputStream o = c.getOutputStream()) { o.write(all); }
        int code = c.getResponseCode();
        c.disconnect();

        return code / 100 == 2 || (code / 100 == 4 && code != 429);
    }

    static synchronized boolean fresh(File cd, String sig) {
        File f = new File(cd, "sent.properties");
        Properties p = new Properties();
        if (f.isFile()) try (InputStream in = new FileInputStream(f)) { p.load(in); } catch (IOException ignored) { }
        long now = System.currentTimeMillis();
        int today = 0;
        for (String k : new ArrayList<>(p.stringPropertyNames())) {
            long t;
            try { t = Long.parseLong(p.getProperty(k)); } catch (NumberFormatException e) { p.remove(k); continue; }
            if (now - t > 7 * 86400_000L) p.remove(k);
            else if (now - t < 86400_000L) today++;
        }
        String last = p.getProperty(sig);
        if (last != null && now - Long.parseLong(last) < SAME_GAP) return false;
        if (today >= MAX_PER_DAY) return false;
        p.setProperty(sig, String.valueOf(now));
        try (OutputStream o = new FileOutputStream(f)) { p.store(o, null); } catch (IOException ignored) { }
        return true;
    }

    static void trim(File cd) {
        File[] fs = cd.listFiles((d, n) -> n.endsWith(".txt") && !n.startsWith("pending-"));
        if (fs == null || fs.length <= 20) return;
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 20; i < fs.length; i++) fs[i].delete();
    }

    static String newId() {
        String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.util.Random r = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6; i++) b.append(a.charAt(r.nextInt(a.length())));
        return b.toString();
    }

    static String sha(String s) { return sha(s.getBytes(StandardCharsets.UTF_8)); }
    static String sha(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(b);
            StringBuilder x = new StringBuilder();
            for (byte v : d) x.append(String.format("%02x", v));
            return x.toString();
        } catch (Exception e) { return "0000000000000000"; }
    }

    public static String readText(File f) {
        try { return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null; } catch (IOException e) { return null; }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
        return o.toByteArray();
    }

    static final Pattern TS = Pattern.compile("^\\[[0-9:. ]+\\] ?");
    static final Pattern NUM = Pattern.compile("\\b\\d+\\b");
    static final String[] TRIGGER = {
            "[OpenHouse/WARN]", "[OpenHouse/ERROR]", "/ERROR]", "/FATAL]",
            "not on this BTA:", "Cannot load class", "invalid dist", "environment type SERVER", "environment type CLIENT",
            "Mixin apply failed", "Critical injection failure", "Unhandled exception",
            "Could not execute entrypoint", "NoClassDefFoundError", "NoSuchMethodError", "NoSuchFieldError", "ClassCastException",
            "AbstractMethodError", "IncompatibleClassChangeError", "VerifyError" };

    static final String[] CONTEXT = { "/WARN]", "@Mixin target", "Error loading class", "stand-ins", " missing, ", "look-alikes", "may not work", "left out", "keeping ", "leaving " };

    static final String[] NOISE = { "Can't remove Log4J2 JNDI", "Failed to load directory", "Can't keep up!", "moved too quickly", "moved wrongly",
            "Reference map", "Ambiguous mapping", "Unable to find refmap", "Unable to determine username", "Couldn't set the window icon",

            "Error loading class: net/minecraft/client/", "@Mixin target net.minecraft.client.",
            "OFFLINE/INSECURE MODE", "make no attempt to authenticate", "While this makes the game possible", "set \"online-mode\" to",
            "Unrecognised gamemode", "Missing translation in default language",

            "has crashed", "Please report this to", "==========", "Invalid profiler state", "Could not find applet run directory",
            "Error loading class: com/verse/e4btavoice" };

    static final Map<String, int[]> soft = new LinkedHashMap<>();
    static final java.util.Set<String> softSent = new java.util.HashSet<>();
    static String lastTraceKey;

    public static void soft(String line) {
        if (line == null || line.isEmpty()) return;
        String l = TS.matcher(line).replaceFirst("");
        synchronized (soft) {

            if ((l.startsWith("\tat ") || l.startsWith("Caused by")) && lastTraceKey != null) {
                int[] c = soft.get(lastTraceKey);
                if (c != null && c[2] < 6) { c[2]++; String k = lastTraceKey + "\n    " + l.trim(); soft.remove(lastTraceKey); soft.put(k, c); lastTraceKey = k; }
                return;
            }
            lastTraceKey = null;
            for (String n : NOISE) if (l.contains(n)) return;
            boolean trig = false, ctx = false;
            for (String t : TRIGGER) if (l.contains(t)) { trig = true; break; }
            if (!trig) for (String c : CONTEXT) if (l.contains(c)) { ctx = true; break; }

            if (ctx && l.contains(" missing, ") && l.contains(" 0 missing, 0 missing classes")) ctx = false;
            if (!trig && !ctx) return;
            if (l.length() > 400) l = l.substring(0, 400) + "...";
            String key = l;
            for (String k : soft.keySet()) if (NUM.matcher(k.split("\n")[0]).replaceAll("#").equals(NUM.matcher(l).replaceAll("#"))) { key = k; break; }
            int[] c = soft.get(key);
            if (c == null) { if (soft.size() >= 300) return; c = new int[] { 0, 0, 0 }; soft.put(key, c); }
            c[0]++;
            if (trig) c[1] = 1;
            lastTraceKey = key;
        }
    }

    static final Map<String, Integer> softAt = new java.util.HashMap<>();
    public static void softFile(File f, long since) {
        if (f == null || !f.isFile() || f.lastModified() < since) return;
        try {
            List<String> ls;
            try { ls = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8); }
            catch (IOException e) { ls = Arrays.asList(new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1).split("\r?\n")); }
            String k = f.getAbsolutePath();
            Integer at = softAt.get(k);
            int from = at == null || at > ls.size() ? 0 : at;
            for (int i = from; i < ls.size(); i++) soft(ls.get(i));
            softAt.put(k, ls.size());
        } catch (IOException | RuntimeException ignored) { }
    }

    public static void flushSoft(File dir, Report r, boolean wait) {
        List<String> lines = new ArrayList<>();
        boolean any = false;
        StringBuilder key = new StringBuilder();
        synchronized (soft) {
            for (Map.Entry<String, int[]> e : soft.entrySet()) {
                boolean fresh = !softSent.contains(e.getKey());
                if (fresh && e.getValue()[1] == 1) any = true;
                lines.add((fresh ? "" : "(sent before) ") + (e.getValue()[0] > 1 ? "x" + e.getValue()[0] + "  " : "") + e.getKey());
                if (fresh && e.getValue()[1] == 1) key.append(NUM.matcher(e.getKey()).replaceAll("#")).append('|');
            }
            if (!any) return;
            softSent.addAll(soft.keySet());
        }
        r.trace(lines).sig("q" + sha(r.product + "|" + key).substring(0, 11));
        send(dir, r, wait);
    }

    public static void softTimer(Runnable flush) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(600_000);
                while (true) { try { flush.run(); } catch (Throwable ignored) { } Thread.sleep(1_200_000); }
            } catch (InterruptedException ignored) { }
        }, "OpenHouse quiet problems");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    public static List<String> story(List<String> lines, int max) {
        List<String> out = new ArrayList<>();
        if (lines == null) return out;
        int from = -1;

        for (int i = 0; i < lines.size() && from < 0; i++) {
            String l = lines.get(i);
            if (l.contains("/ERROR]") || l.contains("/FATAL]") || l.startsWith("Exception in thread") || l.contains("Mixin apply") || l.contains("Critical injection")) from = i;
        }
        for (int i = 0; i < lines.size() && from < 0; i++) {
            String l = lines.get(i);
            if (!l.contains("/WARN]") && !l.contains("/INFO]") && (l.contains("Exception") || l.startsWith("Caused by"))) from = i;
        }
        if (from < 0) return out;
        for (int i = from; i < lines.size() && out.size() < max; i++) out.add(lines.get(i));
        return out;
    }
}
