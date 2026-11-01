package openhouse.mod;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import openhouse.Rtc;
import openhouse.Update;
import openhouse.UpdateSwap;
import openhouse.Version;

public final class OpenHost {
    private OpenHost() { }

    static final String UPDATE_COPY = "OpenHost-update.jar";
    @SuppressWarnings("unused") private static Process swap;
    private static volatile Process server;
    private static volatile OutputStream serverIn;

    public static void main(String[] args) throws Exception {
        boolean noUpdate = false;
        List<String> pass = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--no-update")) noUpdate = true;
            else pass.add(a);
        }
        File here = new File(System.getProperty("user.dir")).getAbsoluteFile();
        File data = new File(here, "openhouse");
        File self = Rtc.self();
        say("OpenHost " + Version.VERSION + " - a Better than Adventure " + Rtc.BTA_SERVER_VERSION + " server that players join by room code");
        removeUpdateCopy(self);
        if (!noUpdate && System.getenv("OPENHOUSE_UPDATED") == null && selfUpdate(self, data, here, args)) return;

        File lib = new File(data, "lib");
        File serverJar;
        List<File> loader;
        try {
            Consumer<String> status = progress();
            serverJar = Rtc.btaServer(lib, status);
            loader = Rtc.babricLoader(lib, status);
        } catch (IOException e) {
            say("Can't get the server ready: " + e.getMessage());
            say("(It needs the internet the first time, to download BTA's server and the mod loader. Try again in a moment.)");
            System.exit(1);
            return;
        }
        File mods = new File(here, "mods");
        if (!mods.isDirectory() && !mods.mkdirs()) say("(can't create " + mods + " - starting without a mods folder)");
        setAsideOtherOpenHouse(mods);

        String ui = uiMode(here, pass);
        boolean console = System.console() != null || System.getenv("OPENHOST_CONSOLE") != null;

        boolean askedNogui = pass.contains("nogui") || pass.contains("--nogui");
        if (ui.equals("console") && !console && !askedNogui && relaunchInConsole(self, here, args)) return;
        File flat = null;
        if (ui.equals("modern")) {
            try { flat = Rtc.flatLaf(lib, progress()); } catch (IOException e) { say("(the window will look plainer: " + e.getMessage() + ")"); }
        }

        if (!ui.equals("classic") && !pass.contains("nogui") && !pass.contains("--nogui")) pass.add(0, "nogui");

        int exit;
        boolean first = true, wasUp = false;
        while (true) {
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBinary());

            String mem = memorySetting(here);
            for (String o : jvmOptions()) if (mem == null || !(o.startsWith("-Xmx") || o.startsWith("-Xms"))) cmd.add(o);
            if (mem != null) {
                cmd.add("-Xmx" + mem);
                say("Server memory: " + mem.replace("G", " GB").replace("M", " MB") + " (" + MEMORY_KEY + " in server.properties)");
            }
            cmd.add("-Dfabric.addMods=" + self.getAbsolutePath());
            cmd.add("-Dopenhouse.openhost=" + Version.VERSION);
            if (!ui.equals("console")) cmd.add("-Dopenhouse.openhost.gui=" + ui);
            if (console) cmd.add("-Dopenhouse.openhost.console=1");
            cmd.add("-Dopenhouse.parentPid=" + ProcessHandle.current().pid());

            if (wasUp) cmd.add("-Dopenhouse.openhost.restarted=true");
            cmd.add("-cp");
            StringBuilder cp = new StringBuilder();
            for (File f : loader) cp.append(f.getAbsolutePath()).append(File.pathSeparator);
            if (flat != null) cp.append(flat.getAbsolutePath()).append(File.pathSeparator);
            cp.append(serverJar.getAbsolutePath());
            cmd.add(cp.toString());
            cmd.add("net.fabricmc.loader.impl.launch.knot.KnotServer");
            cmd.addAll(pass);

            ProcessBuilder pb = new ProcessBuilder(cmd).directory(here).redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.PIPE);
            Process p = pb.start();
            server = p;
            serverIn = p.getOutputStream();
            List<String> startLines = new ArrayList<>();
            boolean[] up = { false }, crashed = { false }, crashedRunning = { false };
            List<String> tail = new ArrayList<>();
            Thread echo = echoOutput(p, startLines, up, crashed, tail, crashedRunning);
            if (first) {
                forwardConsole();
                Runtime.getRuntime().addShutdownHook(new Thread(OpenHost::stopServer, "OpenHost stop"));
                first = false;
            }
            exit = p.waitFor();
            echo.join(5000);
            server = null;
            synchronized (startLines) { wasUp |= up[0]; }
            if (exit == RESTART_EXIT) { say("Restarting the server..."); continue; }
            boolean diedStarting;
            synchronized (startLines) { diedStarting = !up[0] && crashed[0]; }
            if (diedStarting && ++failedStarts <= 15 && setAsideCulprits(mods, startLines)) { say("Starting again..."); continue; }
            if (crashedRunning[0] && exit != RESTART_EXIT) {
                if (++runningCrashes <= 5 && setAsideRunningCrash(mods, tail)) { say("Starting again..."); continue; }
            }
            break;
        }
        System.exit(exit);
    }

    private static int failedStarts, runningCrashes;

    static Thread echoOutput(Process p, List<String> startLines, boolean[] up, boolean[] crashed, List<String> tail, boolean[] crashedRunning) {
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    System.out.println(l);
                    synchronized (startLines) {
                        if (up[0]) {
                            tail.add(l);
                            if (tail.size() > 400) tail.remove(0);
                            if (l.contains(openhouse.mod.server.ServerRoom.CRASH_MARKER)) crashedRunning[0] = true;
                            continue;
                        }
                        if (l.contains("Done (") && l.contains("For help")) { up[0] = true; startLines.clear(); continue; }
                        startLines.add(l);
                        if (startLines.size() > 4000) startLines.remove(0);
                        if (!crashed[0] && (l.contains("Failed to start the minecraft server") || l.contains("A mod crashed on startup")
                                || l.contains("Exception in thread \"main\"") || l.contains("Incompatible mods found"))) {
                            crashed[0] = true;
                            Thread k = new Thread(() -> {
                                try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                                if (p.isAlive()) p.destroyForcibly();
                            }, "OpenHost end failed start");
                            k.setDaemon(true);
                            k.start();
                        }
                    }
                }
            } catch (IOException ignored) { }
            System.out.flush();
        }, "OpenHost server output");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static boolean setAsideRunningCrash(File mods, List<String> tail) {
        List<String> t;
        synchronized (tail) { t = new ArrayList<>(tail); }

        int end = t.size();
        for (int k = 0; k < t.size(); k++) if (t.get(k).contains(openhouse.mod.server.ServerRoom.CRASH_MARKER)) { end = k; break; }
        int from = 0;
        for (int k = end - 1; k >= 0; k--) if (t.get(k).contains("/ERROR]")) { from = k; break; }
        List<String> story = t.subList(from, end);
        File[] jars = mods.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        Map<String, List<File>> byId = ModCrash.idsOf(jars == null ? java.util.Collections.<File>emptyList() : java.util.Arrays.asList(jars));
        String id = ModCrash.crashMod(story, byId);
        if (id == null) { say("The server crashed - the error is above. (No mod in mods/ to blame for it.)"); return false; }
        if (!ModCrash.clientOnlyCrash(story)) {
            say("The server crashed in the mod " + id + " - the error is above. It may hold blocks or items of your world, so it's left in: update or remove it yourself.");
            return false;
        }
        boolean any = false;
        for (File j : byId.getOrDefault(id, java.util.Collections.emptyList())) {
            File off = new File(mods, j.getName() + ".disabled");
            if (off.exists()) off.delete();
            if (j.renameTo(off)) {
                any = true;
                say("mods/" + j.getName() + " (" + id + ") crashed the server reaching for a part of the game only players have - it's for players' own games. Set it aside as " + off.getName() + ".");
            }
        }
        return any;
    }

    static boolean setAsideCulprits(File mods, List<String> lines) {
        File[] jars = mods.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        if (jars == null || jars.length == 0) return false;
        Map<String, List<File>> byId = ModCrash.idsOf(java.util.Arrays.asList(jars));
        Set<String> bad;
        synchronized (lines) { bad = ModCrash.culprits(new ArrayList<>(lines), byId, new java.util.HashSet<>()); }
        boolean any = false;
        for (String id : bad) {
            for (File j : byId.getOrDefault(id, java.util.Collections.emptyList())) {
                File off = new File(mods, j.getName() + ".disabled");
                if (off.exists()) off.delete();
                if (j.renameTo(off)) {
                    any = true;
                    say("mods/" + j.getName() + " (" + id + ") can't run on a server - it's for players' own games. Set it aside as " + off.getName() + ".");
                } else {
                    say("mods/" + j.getName() + " (" + id + ") stops the server from starting, and it can't be renamed - take it out of mods/.");
                }
            }
        }
        return any;
    }

    public static final int RESTART_EXIT = 75;

    public static final String MEMORY_KEY = "openhost-memory";

    static String memorySetting(File here) {
        File f = new File(here, "server.properties");
        if (!f.isFile()) return null;
        java.util.Properties p = new java.util.Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { p.load(in); } catch (IOException e) { return null; }
        return memoryValue(p.getProperty(MEMORY_KEY, ""));
    }

    public static String memoryValue(String v) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)^\\s*(\\d{1,6})\\s*([gm])b?\\s*$").matcher(v == null ? "" : v);
        if (!m.matches()) return null;
        long n = Long.parseLong(m.group(1));
        boolean g = m.group(2).equalsIgnoreCase("g");
        if ((g ? n * 1024 : n) < 512) return null;
        return n + (g ? "G" : "M");
    }

    static String uiMode(File here, List<String> pass) {
        if (pass.contains("nogui") || pass.contains("--nogui") || java.awt.GraphicsEnvironment.isHeadless()) return "console";
        String v = "modern";
        File f = new File(here, "server.properties");
        if (f.isFile()) {
            java.util.Properties p = new java.util.Properties();
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { p.load(in); v = p.getProperty("openhost-ui", v); } catch (IOException ignored) { }
        }
        v = v.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("classic") || v.equals("console") ? v : "modern";
    }

    static boolean relaunchInConsole(File self, File here, String[] args) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return false;
        try {
            List<String> cmd = new ArrayList<>(Arrays.asList("cmd", "/c", "start", "\"OpenHost\""));
            cmd.add(new File(new File(System.getProperty("java.home"), "bin"), "java.exe").getPath());
            cmd.addAll(jvmOptions());
            cmd.add("-jar");
            cmd.add(self.getAbsolutePath());
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(here);
            pb.environment().put("OPENHOST_CONSOLE", "1");
            pb.environment().put("OPENHOUSE_UPDATED", "1");
            pb.start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static void say(String s) {
        System.out.println("[OpenHost] " + s);
        System.out.flush();
    }

    private static Consumer<String> progress() {
        return s -> { if (Rtc.coarseProgress(s)) say(s); };
    }

    private static String javaBinary() {
        File bin = new File(System.getProperty("java.home"), "bin");
        File javaw = new File(bin, "javaw.exe");
        return System.console() == null && javaw.isFile() ? javaw.getPath() : new File(bin, "java").getPath();
    }

    static List<String> jvmOptions() {
        List<String> out = new ArrayList<>();
        try {
            for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (a.startsWith("-agentlib") || a.startsWith("-javaagent") || a.startsWith("-agentpath") || a.startsWith("-Xrunjdwp") || a.startsWith("-Xdebug")) continue;
                if (a.startsWith("-X") || a.startsWith("-D")) out.add(a);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static void setAsideOtherOpenHouse(File mods) {
        File[] jars = mods.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
        if (jars == null) return;
        for (File j : jars) {
            if (!UpdateSwap.isOpenHouseMod(j)) continue;
            File off = new File(mods, j.getName() + ".disabled");
            if (off.exists()) off.delete();
            if (j.renameTo(off)) say("mods/" + j.getName() + " is OpenHouse, which OpenHost already includes - renamed it to " + off.getName());
            else say("mods/" + j.getName() + " is OpenHouse, which OpenHost already includes - remove it, or the server can't start");
        }
    }

    private static void forwardConsole() {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                InputStream in = System.in;
                int n;
                while ((n = in.read(buf)) > 0) {
                    OutputStream o = serverIn;
                    if (o == null) continue;

                    try { o.write(buf, 0, n); o.flush(); } catch (IOException gone) { }
                }
            } catch (IOException ignored) { }
        }, "OpenHost console");
        t.setDaemon(true);
        t.start();
    }

    private static void stopServer() {
        Process p = server;
        if (p == null || !p.isAlive()) return;
        try {
            OutputStream o = serverIn;
            if (o != null) { o.write("stop\n".getBytes(StandardCharsets.UTF_8)); o.flush(); }
        } catch (IOException ignored) { }
        try {
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException ignored) { }
    }

    static boolean selfUpdate(File self, File data, File here, String[] args) {
        try {
            if (!self.isFile() || self.getName().equals(UPDATE_COPY)) return false;
            Update.Release r = Update.latestOpenHost();
            if (!r.newer()) return false;
            say("OpenHost " + r.version + " is out (this is " + Version.VERSION + ") - updating...");
            File jar = Update.download(r, new File(data, "update"), null);
            File run = new File(self.getParentFile(), UPDATE_COPY);
            java.nio.file.Files.copy(jar.toPath(), run.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            swap = Update.startSwap(self, jar, data, Collections.singletonList(self.getAbsolutePath()), Collections.<String>emptyList(), here);
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBinary());
            cmd.addAll(jvmOptions());
            cmd.add("-jar");
            cmd.add(run.getAbsolutePath());
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(here).inheritIO();
            pb.environment().put("OPENHOUSE_UPDATED", "1");
            int code = pb.start().waitFor();
            System.exit(code);
            return true;
        } catch (Exception e) {
            say("(couldn't check for or get a newer OpenHost: " + (e.getMessage() == null ? e.toString() : e.getMessage()) + " - carrying on with " + Version.VERSION + ")");
            return false;
        }
    }

    static void removeUpdateCopy(File self) {
        try {
            if (self.isFile() && !self.getName().equals(UPDATE_COPY)) new File(self.getParentFile(), UPDATE_COPY).delete();
        } catch (Exception ignored) { }
    }
}
