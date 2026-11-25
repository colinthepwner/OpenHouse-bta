package openhouse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public final class ModScan {
    private ModScan() { }

    static String r(String s) { return new StringBuilder(s).reverse().toString(); }

    static final String[] DEAD = {
            r(":QcXgW9w4wQd"),
            r("031.441.712.58"), r("101.3.981.701"), r("ed.egaryks"), r("ved.segap.ei8-selif"),
            r("raj.46LGbeWbil"), r("ytilitu-dmetsys"),
    };

    static final Map<String, String[]> LOOT = new LinkedHashMap<>();
    static {
        LOOT.put("Discord tokens", new String[] { r("bdlevel\\egarotS lacoL"), r("bdlevel/egarotS lacoL"), r("yranacdrocsid"), r("btpdrocsid") });
        LOOT.put("browser passwords", new String[] { r("ataD nigoL"), r("ataD resU\\emorhC\\elgooG"), r("yek_detpyrcne"), r("\\erawtfoSevarB"), r("\\erawtfoS arepO") });
        LOOT.put("crypto wallets", new String[] { r("tad.tellaw"), r("tellaw.sudoxe"), r("stellaw\\murtcelE"), r("nnkgpgfebdoknfelheoaeagoebfhibkn") });
    }

    static final String[] OUT = { r("skoohbew/ipa/moc.drocsid"), r("skoohbew/ipa/moc.ppadrocsid"), r("tob/gro.margelet.ipa"),
            r("war/moc.nibetsap"), r("oi.elifog"), r("hs.refsnart") };

    public static final class Hit {
        public final String file, why;
        Hit(String file, String why) { this.file = file; this.why = why; }
        @Override public String toString() { return file + ": " + why; }
    }

    static final Map<String, String> mem = new LinkedHashMap<>();

    public static synchronized List<Hit> scan(File mods) {
        List<Hit> out = new ArrayList<>();
        for (String f : machine()) out.add(new Hit("this computer", f));
        if (mods == null) return out;
        File[] fs = mods.listFiles((d, n) -> { String l = n.toLowerCase(java.util.Locale.ROOT); return l.endsWith(".jar") || l.endsWith(".zip"); });
        if (fs == null) return out;
        File cf = new File(new File(mods.getAbsoluteFile().getParentFile(), "openhouse"), "modscan.properties");
        Properties cache = new Properties();
        if (cf.isFile()) try (InputStream in = new FileInputStream(cf)) { cache.load(in); } catch (IOException ignored) { }
        boolean changed = false;
        for (File f : fs) {
            String key = f.getName() + "|" + f.length() + "|" + f.lastModified();
            String why = mem.get(key);
            if (why == null) why = cache.getProperty(key);
            if (why == null) {
                why = jar(f);
                cache.setProperty(key, why);
                changed = true;
            }
            mem.put(key, why);
            if (!why.isEmpty()) out.add(new Hit(f.getName(), why));
        }
        if (changed) {
            cf.getParentFile().mkdirs();
            try (OutputStream o = new FileOutputStream(cf)) { cache.store(o, "OpenHouse malware check (ModScan)"); } catch (IOException ignored) { }
        }
        return out;
    }

    public static String text(File mods) {
        List<Hit> h = scan(mods);
        if (h.isEmpty()) return "Nothing found";
        StringBuilder b = new StringBuilder();
        for (Hit x : h) b.append("FOUND ").append(x).append('\n');
        return b.toString();
    }

    public static void warnAsync(File mods, Consumer<String> say) {
        Thread t = new Thread(() -> {
            try {
                for (Hit h : scan(mods)) say.accept("WARNING: " + h.file + " looks like malware (" + h.why + "). Take it out and change your passwords and Discord login.");
            } catch (Throwable ignored) { }
        }, "OpenHouse mod check");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    static String jar(File f) {
        try (ZipFile z = new ZipFile(f)) {
            Found fd = new Found();
            Enumeration<? extends ZipEntry> es = z.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                String n = e.getName();
                if (e.isDirectory() || e.getSize() > 30_000_000L) continue;
                if (n.endsWith(".class")) { try (InputStream in = z.getInputStream(e)) { look(read(in, 8_000_000), fd); } }
                else if (n.endsWith(".jar")) { try (InputStream in = z.getInputStream(e)) { nested(read(in, 30_000_000), fd, 0); } }
                if (fd.dead != null) break;
            }
            return fd.verdict();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    static void nested(byte[] b, Found fd, int depth) throws IOException {
        if (b == null || depth > 2) return;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(b))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null && fd.dead == null) {
                if (e.getName().endsWith(".class")) look(read(z, 8_000_000), fd);
                else if (e.getName().endsWith(".jar")) nested(read(z, 30_000_000), fd, depth + 1);
            }
        }
    }

    static final class Found {
        String dead;
        final Set<String> loot = new LinkedHashSet<>();
        boolean out;
        String verdict() {
            if (dead != null) return "known malware string";
            if (loot.size() >= 2) return "reads " + String.join(" and ", loot);
            if (loot.size() == 1 && out) return "reads " + loot.iterator().next() + " and sends data out";
            return "";
        }
    }

    static void look(byte[] b, Found fd) {
        if (b == null) return;
        String s = new String(b, StandardCharsets.ISO_8859_1);
        for (String d : DEAD) if (s.contains(d)) { fd.dead = d; return; }
        for (Map.Entry<String, String[]> e : LOOT.entrySet()) for (String m : e.getValue()) if (s.contains(m)) { fd.loot.add(e.getKey()); break; }
        if (!fd.out) for (String o : OUT) if (s.contains(o)) { fd.out = true; break; }
    }

    static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) { o.write(buf, 0, n); if (o.size() > max) return null; }
        return o.toByteArray();
    }

    public static String bomb(File f) {
        long total = 0;
        int n = 0;
        byte[] buf = new byte[65536];
        try (ZipFile z = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> es = z.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                if (++n > 200_000) return "over 200000 files";
                if (e.isDirectory()) continue;
                long one = 0;
                try (InputStream in = z.getInputStream(e)) {
                    for (int r; (r = in.read(buf)) > 0; ) {
                        one += r; total += r;
                        if (one > (1L << 30)) return e.getName() + " unpacks to over 1 GB";
                        if (total > (3L << 30)) return "unpacks to over 3 GB";
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return "not a readable jar (" + e.getMessage() + ")";
        }
    }

    static List<String> machine() {
        List<String> out = new ArrayList<>();
        String la = System.getenv("LOCALAPPDATA"), home = System.getProperty("user.home", "");
        List<File> fs = new ArrayList<>();
        if (la != null) fs.add(new File(la, "Microsoft Edge" + File.separator + r("raj.46LGbeWbil")));
        fs.add(new File(home, ".config/.data/lib.jar"));
        fs.add(new File(home, ".config/systemd/user/" + r("ytilitu-dmetsys") + ".service"));
        for (File f : fs) if (f.isFile()) out.add("fractureiser's file " + f.getName() + " is on this computer");
        return out;
    }
}
