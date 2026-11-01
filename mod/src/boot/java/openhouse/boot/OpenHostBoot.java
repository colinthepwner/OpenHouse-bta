package openhouse.boot;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class OpenHostBoot {
    private OpenHostBoot() { }

    static final int NEED = 17;

    public static void main(String[] args) throws Throwable {
        if (major(System.getProperty("java.specification.version")) >= NEED) {
            try {
                Class.forName("openhouse.mod.OpenHost").getMethod("main", String[].class).invoke(null, (Object) args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            return;
        }
        File home = newestJava();
        if (home == null) {
            explain();
            System.exit(1);
            return;
        }
        System.exit(relaunch(home, args));
    }

    static int major(String v) {
        if (v == null) return 0;
        v = v.trim().replace("\"", "");
        String[] p = v.split("[._+\\-]");
        try {
            int a = Integer.parseInt(p[0]);
            return a == 1 && p.length > 1 ? Integer.parseInt(p[1]) : a;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    static File javaBin(File home, boolean windowless) {
        File bin = new File(home, "bin");
        if (windows()) {
            File w = new File(bin, "javaw.exe");
            if (windowless && w.isFile()) return w;
            return new File(bin, "java.exe");
        }
        return new File(bin, "java");
    }

    static int versionOf(File home) {
        File rel = new File(home, "release");
        if (!rel.isFile()) return 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(rel), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("JAVA_VERSION=")) return major(line.substring("JAVA_VERSION=".length()));
            }
        } catch (Exception ignored) { }
        return 0;
    }

    static File newestJava() {
        List<File> found = new ArrayList<>();
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isEmpty()) scan(new File(jh), 0, found);
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(File.pathSeparator)) if (!d.isEmpty()) scan(new File(d).getParentFile(), 0, found);
        String user = System.getProperty("user.home", "");
        List<Object[]> roots = new ArrayList<>();
        if (windows()) {
            for (String pf : new String[] { System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("ProgramW6432") }) {
                if (pf == null) continue;
                for (String vendor : new String[] { "Java", "Eclipse Adoptium", "Eclipse Foundation", "AdoptOpenJDK", "Zulu", "Microsoft", "Amazon Corretto",
                        "BellSoft", "Semeru", "OpenJDK", "SapMachine", "Liberica" })
                    roots.add(new Object[] { new File(pf, vendor), 2 });
                roots.add(new Object[] { new File(pf, "Minecraft Launcher/runtime"), 4 });
            }
            String local = System.getenv("LOCALAPPDATA"), appdata = System.getenv("APPDATA");
            if (local != null) roots.add(new Object[] { new File(local, "Packages/Microsoft.4297127D64EC6_8wekyb3d8bbwe/LocalCache/Local/runtime"), 4 });
            if (appdata != null) {
                roots.add(new Object[] { new File(appdata, ".minecraft/runtime"), 4 });
                roots.add(new Object[] { new File(appdata, "PrismLauncher/java"), 2 });
            }
        } else {
            for (String d : new String[] { "/Library/Java/JavaVirtualMachines", user + "/Library/Java/JavaVirtualMachines" }) roots.add(new Object[] { new File(d), 3 });
            for (String d : new String[] { "/usr/lib/jvm", "/usr/java", "/opt/java", "/opt", user + "/.sdkman/candidates/java" }) roots.add(new Object[] { new File(d), 2 });
            roots.add(new Object[] { new File(user, ".minecraft/runtime"), 4 });
            roots.add(new Object[] { new File(user, "Library/Application Support/minecraft/runtime"), 4 });
        }
        roots.add(new Object[] { new File(user, ".jdks"), 2 });
        for (Object[] r : roots) scan((File) r[0], (Integer) r[1], found);
        File best = null;
        int bestRank = Integer.MAX_VALUE;
        for (File h : found) {
            int v = versionOf(h);
            if (v < NEED || !javaBin(h, false).isFile()) continue;
            int r = rank(v);
            if (r < bestRank) { best = h; bestRank = r; }
        }
        return best;
    }

    static int rank(int v) {
        if (v == 21) return 0;
        if (v <= 24) return 100 - v;
        return 100 + v;
    }

    static void scan(File dir, int depth, List<File> out) {
        if (dir == null || !dir.isDirectory()) return;
        if (new File(dir, "release").isFile() && (new File(dir, "bin/java").isFile() || new File(dir, "bin/java.exe").isFile())) {
            out.add(dir);
            return;
        }
        if (depth <= 0) return;
        File[] kids = dir.listFiles(File::isDirectory);
        if (kids == null) return;
        for (File k : kids) scan(k, depth - 1, out);
    }

    static int relaunch(File home, String[] args) throws Exception {
        File self = new File(OpenHostBoot.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin(home, System.console() == null).getPath());
        try {
            for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments())
                if (a.startsWith("-X") || a.startsWith("-D")) cmd.add(a);
        } catch (Throwable ignored) { }
        cmd.add("-jar");
        cmd.add(self.getAbsolutePath());
        cmd.addAll(Arrays.asList(args));
        System.out.println("[OpenHost] This is Java " + System.getProperty("java.version") + " - starting OpenHost on the Java " + versionOf(home) + " at " + home);
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        return p.waitFor();
    }

    static void explain() {
        String have = System.getProperty("java.version");
        String msg = "OpenHost needs Java " + NEED + " or newer, and this computer only has Java " + have + ".\n\n"
                + "Install Java 21 (free), then open OpenHost again.";
        String url = "https://adoptium.net/temurin/releases/?version=21&package=jre";
        System.err.println("[OpenHost] " + msg.replace("\n\n", " ") + " Get it from " + url);
        if (java.awt.GraphicsEnvironment.isHeadless()) return;
        try {
            Object[] options = { "Get Java 21", "Close" };
            int pick = javax.swing.JOptionPane.showOptionDialog(null, msg, "OpenHost", javax.swing.JOptionPane.DEFAULT_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            if (pick == 0 && java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (Throwable ignored) { }
    }
}
