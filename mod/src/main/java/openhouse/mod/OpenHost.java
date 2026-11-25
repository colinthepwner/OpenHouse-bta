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
        File self = Rtc.self();
        File here = serverFolder(self, args);
        File data = new File(here, "openhouse");
        crashReports(here);
        say("OpenHost " + Version.VERSION + ": a Better than Adventure server that players join by room code");
        say("Server folder: " + here);
        removeUpdateCopy(self);
        if (!noUpdate && System.getenv("OPENHOUSE_UPDATED") == null && selfUpdate(self, data, here, args)) return;

        noDialogs = pass.contains("nogui") || pass.contains("--nogui");
        if (!safeToStart(here, pass)) System.exit(1);

        openhouse.OpenHostList.add(here);

        File lib = new File(data, "lib");
        File serverJar;
        List<File> loader;
        try {
            Consumer<String> status = progress();
            String own = Rtc.adoptBtaServer(lib, here, self);
            if (own != null) say("Using this folder's own BTA server (" + own + "), no download needed");
            serverJar = Rtc.btaServer(lib, status);
            loader = Rtc.babricLoader(lib, status);
        } catch (IOException e) {
            report(here, new openhouse.OhCrash.Report("OpenHost", "Couldn't get the server ready").error(e), true);
            say("Can't get the server ready: " + e.getMessage());
            say("(It needs the internet the first time, to download BTA's server and the mod loader. Try again in a moment.)");
            System.exit(1);
            return;
        }
        File mods = new File(here, "mods");
        if (!mods.isDirectory() && !mods.mkdirs()) say("(can't create " + mods + ", starting without a mods folder)");

        setAsideServerCrashers(mods);

        new File(here, "texturepacks").mkdirs();

        String ui = uiMode(here, pass);
        boolean console = hasTerminal() || System.getenv("OPENHOST_CONSOLE") != null;

        boolean askedNogui = pass.contains("nogui") || pass.contains("--nogui");
        if (ui.equals("console") && !console && !askedNogui && !java.awt.GraphicsEnvironment.isHeadless()) {
            if (relaunchInConsole(self, here, args)) return;

            say("Couldn't open a terminal for the console look, so using the OpenHost window this time");
            ui = "modern";
        }
        File flat = null;
        if (ui.equals("modern")) {
            try { flat = Rtc.flatLaf(lib, progress()); } catch (IOException e) { say("(the window will look plainer: " + e.getMessage() + ")"); }
        }

        if (!ui.equals("classic") && !pass.contains("nogui") && !pass.contains("--nogui")) pass.add(0, "nogui");

        int exit;
        boolean first = true, wasUp = false;
        while (true) {
            applyModChanges(here);
            HERE = here;
            state("starting");
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBinary());

            String mem = memorySetting(here);
            for (String o : jvmOptions()) if (mem == null || !(o.startsWith("-Xmx") || o.startsWith("-Xms"))) cmd.add(o);
            if (mem != null) {
                cmd.add("-Xmx" + mem);
                say("Server memory: " + mem.replace("G", " GB").replace("M", " MB") + " (" + MEMORY_KEY + " in server.properties)");
            }
            OpenHost.no_b64(cmd);
            server_jvm(cmd, data);
            cmd.addAll(modOptions(self, mods, data));
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
                watchControl(here);
                Runtime.getRuntime().addShutdownHook(new Thread(OpenHost::stopServer, "OpenHost stop"));
                first = false;
            }
            exit = p.waitFor();
            state(null);
            echo.join(5000);
            server = null;
            synchronized (startLines) { wasUp |= up[0]; }
            if (exit == RESTART_EXIT) { say("Restarting the server..."); continue; }
            boolean diedStarting;
            synchronized (startLines) { diedStarting = !up[0] && crashed[0]; }
            if (diedStarting) {
                List<String> sl;
                synchronized (startLines) { sl = new ArrayList<>(startLines); }
                report(here, new openhouse.OhCrash.Report("OpenHost", "The server couldn't start").bta(btaVer(serverJar)).mods(mods)
                        .trace(openhouse.OhCrash.story(sl, 200)).blame(blameStart(mods, sl)).log(sl, 600), false);
            }
            if (diedStarting && ++failedStarts <= 15 && setAsideCulprits(mods, startLines)) { say("Starting again..."); continue; }
            if (crashedRunning[0] && exit != RESTART_EXIT) {
                List<String> tl;
                synchronized (startLines) { tl = new ArrayList<>(tail); }
                report(here, new openhouse.OhCrash.Report("OpenHost", "The server crashed while running").bta(btaVer(serverJar)).mods(mods)
                        .trace(openhouse.OhCrash.story(tl, 200)).log(tl, 400), false);
                if (++runningCrashes <= 5 && setAsideRunningCrash(mods, tail)) { say("Starting again..."); continue; }
            }
            break;
        }
        System.exit(exit);
    }

    private static int failedStarts, runningCrashes;

    static void crashReports(File here) {
        openhouse.OhCrash.journalDir(here);
        openhouse.OhCrash.enabled(here);
        openhouse.OhCrash.sendPending(here);
        openhouse.OhCrash.section("OpenHost", () -> {
            StringBuilder b = new StringBuilder();
            b.append("Folder: ").append(here.getName()).append(", ui ").append(uiMode(here, new ArrayList<>())).append(", memory setting ").append(memorySetting(here)).append('\n');
            b.append("Starts that failed: ").append(failedStarts).append(", crashes while running: ").append(runningCrashes).append('\n');
            File[] off = new File(here, "mods").listFiles((d, n) -> n.endsWith(".disabled"));
            if (off != null && off.length > 0) { b.append("Set aside:"); for (File f : off) b.append(' ').append(f.getName()); b.append('\n'); }
            return b.toString();
        });
        openhouse.OhCrash.section("Players who joined (their own games)", () -> {
            File d = new File(here, "openhouse/players-diag.txt");
            return openhouse.OhCrash.readText(d);
        });
        openhouse.OhCrash.section("Malware check", () -> openhouse.ModScan.text(new File(here, "mods")));

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            report(here, new openhouse.OhCrash.Report("OpenHost", "OpenHost itself crashed (" + t.getName() + ")").error(e).mods(new File(here, "mods")), true);
            e.printStackTrace();
            if (t.getName().equals("main")) System.exit(1);
        });
        openhouse.ModScan.warnAsync(new File(here, "mods"), OpenHost::say);

        Runnable quiet = () -> openhouse.OhCrash.flushSoft(here, new openhouse.OhCrash.Report("OpenHost", "Things went wrong quietly").mods(new File(here, "mods")), true);
        openhouse.OhCrash.softTimer(quiet);
        Runtime.getRuntime().addShutdownHook(new Thread(quiet, "OpenHost quiet problems"));
    }

    static String btaVer(File jar) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)+(?:_\\d+)?").matcher(jar.getName());
        return m.find() ? m.group() : jar.getName();
    }

    static void report(File here, openhouse.OhCrash.Report r, boolean wait) {
        try { openhouse.OhCrash.send(here, r, wait); } catch (Throwable ignored) { }
    }

    static String blameStart(File mods, List<String> lines) {
        try {
            File[] jars = mods.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
            if (jars == null) return null;
            Map<String, String> why = new java.util.LinkedHashMap<>();
            Set<String> bad = ModCrash.culprits(lines, ModCrash.idsOf(java.util.Arrays.asList(jars)), new java.util.HashSet<>(), why);
            List<String> out = new ArrayList<>();
            for (String id : bad) out.add(why.containsKey(id) ? id + " (breaks " + why.get(id) + ")" : id);
            return out.isEmpty() ? null : String.join(", ", out);
        } catch (Throwable t) { return null; }
    }

    static Thread echoOutput(Process p, List<String> startLines, boolean[] up, boolean[] crashed, List<String> tail, boolean[] crashedRunning) {
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    System.out.println(l);
                    openhouse.OhCrash.soft(l);
                    synchronized (startLines) {
                        if (up[0]) {
                            tail.add(l);
                            if (tail.size() > 400) tail.remove(0);
                            if (l.contains(openhouse.mod.server.ServerRoom.CRASH_MARKER)) crashedRunning[0] = true;
                            continue;
                        }
                        if (l.contains("Done (") && l.contains("For help")) { up[0] = true; startLines.clear(); state("up"); continue; }
                        startLines.add(l);
                        if (startLines.size() > 4000) startLines.remove(0);
                        if (!crashed[0] && ModCrash.failedStartLine(l)) {
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
        if (id == null) { say("The server crashed. The error is above. (No mod in mods/ to blame for it.)"); return false; }
        if (!ModCrash.clientOnlyCrash(story)) {
            say("The server crashed in the mod " + id + ". The error is above. It may hold blocks or items of your world, so it's left in: update or remove it yourself.");
            return false;
        }
        boolean any = false;
        for (File j : byId.getOrDefault(id, java.util.Collections.emptyList())) {
            File off = new File(mods, j.getName() + ".disabled");
            if (off.exists()) off.delete();
            if (j.renameTo(off)) {
                any = true;
                say("mods/" + j.getName() + " (" + id + ") crashed the server reaching for a part of the game only players have. It's for players' own games. Set it aside as " + off.getName() + ".");
            }
        }
        return any;
    }

    static void setAsideServerCrashers(File mods) {
        if (Boolean.getBoolean("openhouse.allServerMods")) return;
        File[] jars = mods.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        if (jars == null) return;
        Arrays.sort(jars);
        for (File j : jars) {
            if (UpdateSwap.isOpenHouseMod(j)) continue;
            String why = ClientCode.serverCrasher(j);
            if (why == null) continue;
            File off = new File(mods, j.getName() + ".disabled");
            if (off.exists()) off.delete();
            if (j.renameTo(off))
                say("mods/" + j.getName() + " is for players' own games: " + why + ", which a server hasn't got, so it would crash the server as soon as it ran. Set it aside as " + off.getName() + ".");
            else
                say("mods/" + j.getName() + " can't run on a server (" + why + "), and it can't be renamed. Take it out of mods/.");
        }
    }

    static boolean setAsideCulprits(File mods, List<String> lines) {
        File[] jars = mods.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        if (jars == null || jars.length == 0) return false;
        Map<String, List<File>> byId = ModCrash.idsOf(java.util.Arrays.asList(jars));
        Set<String> bad;
        Map<String, String> why = new java.util.LinkedHashMap<>();
        synchronized (lines) { bad = ModCrash.culprits(new ArrayList<>(lines), byId, new java.util.HashSet<>(), why); }
        boolean any = false;
        for (String id : bad) {
            for (File j : byId.getOrDefault(id, java.util.Collections.emptyList())) {
                File off = new File(mods, j.getName() + ".disabled");
                if (off.exists()) off.delete();
                if (j.renameTo(off)) {
                    any = true;
                    String v = why.get(id);
                    if (v != null) say("mods/" + j.getName() + " (" + id + ") breaks " + v + ": both change the same part of the game. Set it aside as " + off.getName() + ". Put it back once one of them updates.");
                    else say("mods/" + j.getName() + " (" + id + ") can't run on a server. It's for players' own games. Set it aside as " + off.getName() + ".");
                } else {
                    say("mods/" + j.getName() + " (" + id + ") stops the server from starting, and it can't be renamed. Take it out of mods/.");
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
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return relaunchInTerminal(self, here, args);
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

    private static final String[][] TERMINALS = {
        { "x-terminal-emulator", "-e" },
        { "gnome-terminal", "--" },
        { "kgx", "--" },
        { "ptyxis", "--" },
        { "konsole", "-e" },
        { "xfce4-terminal", "-x" },
        { "mate-terminal", "-x" },
        { "lxterminal", "-e" },
        { "tilix", "-e" },
        { "kitty" },
        { "alacritty", "-e" },
        { "foot" },
        { "wezterm", "start", "--" },
        { "xterm", "-e" },
    };
    private static final Set<String> ONE_STRING = new java.util.HashSet<>(Arrays.asList("lxterminal", "tilix"));

    static boolean relaunchInTerminal(File self, File here, String[] args) {
        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        File script = new File(new File(here, "openhouse"), mac ? "OpenHost.command" : "console.sh");
        try {
            StringBuilder run = new StringBuilder(shq(new File(new File(System.getProperty("java.home"), "bin"), "java").getPath()));
            for (String o : jvmOptions()) run.append(' ').append(shq(o));
            run.append(" -jar ").append(shq(self.getAbsolutePath()));
            for (String a : args) run.append(' ').append(shq(a));
            String text = "#!/bin/sh\n"
                    + "cd " + shq(here.getAbsolutePath()) + " || exit 1\n"
                    + "OPENHOST_CONSOLE=1; OPENHOUSE_UPDATED=1; export OPENHOST_CONSOLE OPENHOUSE_UPDATED\n"
                    + run + "\n"
                    + "code=$?\n"
                    + "if [ \"$code\" -ne 0 ]; then echo; echo \"OpenHost stopped (exit code $code). Press Enter to close this window.\"; read reply; fi\n";
            script.getParentFile().mkdirs();
            java.nio.file.Files.write(script.toPath(), text.getBytes(StandardCharsets.UTF_8));
            script.setExecutable(true, false);
        } catch (IOException e) {
            say("(couldn't write " + script + ": " + e.getMessage() + ")");
            return false;
        }
        if (mac) return startTerminal(Arrays.asList("open", "-a", "Terminal", script.getAbsolutePath()));
        List<String[]> order = new ArrayList<>();
        String mine = System.getenv("TERMINAL");
        if (mine != null && !mine.trim().isEmpty()) {
            String name = new File(mine.trim()).getName();
            String[] known = null;
            for (String[] t : TERMINALS) if (t[0].equals(name)) known = t;
            String[] t = known == null ? new String[] { mine.trim(), "-e" } : known.clone();
            t[0] = mine.trim();
            order.add(t);
        }
        order.addAll(Arrays.asList(TERMINALS));
        for (String[] t : order) {
            List<String> cmd = new ArrayList<>(Arrays.asList(t));
            if (ONE_STRING.contains(new File(t[0]).getName())) cmd.add("sh " + shq(script.getAbsolutePath()));
            else { cmd.add("sh"); cmd.add(script.getAbsolutePath()); }
            if (startTerminal(cmd)) {
                say("Opened " + new File(t[0]).getName() + " for the console look");
                return true;
            }
        }
        return false;
    }

    private static boolean startTerminal(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) return true;
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static void say(String s) {
        openhouse.OhCrash.note(s);
        System.out.println("[OpenHost] " + s);
        System.out.flush();
    }

    private static Consumer<String> progress() {
        return s -> { if (Rtc.coarseProgress(s)) say(s); };
    }

    private static String javaBinary() {
        File bin = new File(System.getProperty("java.home"), "bin");
        File javaw = new File(bin, "javaw.exe");
        return !hasTerminal() && javaw.isFile() ? javaw.getPath() : new File(bin, "java").getPath();
    }

    static boolean hasTerminal() {
        java.io.Console c = System.console();
        if (c == null) return false;
        try {
            return (Boolean) java.io.Console.class.getMethod("isTerminal").invoke(c);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    static File serverFolder(File self, String[] args) {
        File cwd = new File(System.getProperty("user.dir")).getAbsoluteFile();
        File jarDir = self.getAbsoluteFile().getParentFile();
        if (jarDir == null || same(cwd, jarDir)) return cwd;
        if (args.length > 0 || hasTerminal() || System.getenv("OPENHOST_CONSOLE") != null) return cwd;
        boolean desktopPlace = cwd.getParentFile() == null || same(cwd, new File(System.getProperty("user.home", "")));
        if (!desktopPlace && new File(cwd, "server.properties").isFile()) return cwd;
        if (!java.nio.file.Files.isWritable(jarDir.toPath()) || under(jarDir, new File(System.getProperty("java.io.tmpdir", "")))) return cwd;
        return jarDir;
    }

    private static boolean same(File a, File b) {
        try { return a.getCanonicalFile().equals(b.getCanonicalFile()); } catch (IOException e) { return a.getAbsoluteFile().equals(b.getAbsoluteFile()); }
    }

    private static boolean under(File f, File dir) {
        if (dir.getPath().isEmpty()) return false;
        try {
            File d = dir.getCanonicalFile();
            for (File p = f.getCanonicalFile(); p != null; p = p.getParentFile()) if (p.equals(d)) return true;
        } catch (IOException ignored) { }
        return false;
    }

    static final String NO_B64 = "-XX:-UseBASE64Intrinsics";
    static void no_b64(List<String> cmd) {
        if (!cmd.contains(NO_B64)) cmd.add(1, NO_B64);
    }

    static final String[] SERVER_GC = {
            "-XX:+IgnoreUnrecognizedVMOptions", "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
            "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40",
            "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4",
            "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1MixedGCLiveThresholdPercent=90", "-XX:G1RSetUpdatingPauseTimePercent=5",
            "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1" };

    static void server_jvm(List<String> cmd, File data) {
        boolean own_gc = false;
        for (String a : cmd) if (a.startsWith("-XX:+Use") && a.endsWith("GC")) own_gc = true;
        List<String> add = new ArrayList<>();
        if (!own_gc) java.util.Collections.addAll(add, SERVER_GC);
        try {
            if (Runtime.version().feature() >= 19 && data != null) {
                data.mkdirs();
                add.add("-XX:+AutoCreateSharedArchive");
                add.add("-XX:SharedArchiveFile=" + new File(data, "server-classes.jsa").getAbsolutePath());
                add.add("-Xlog:cds*=off");
            }
        } catch (Throwable ignored) { }

        add.removeIf(cmd::contains);
        cmd.addAll(1, add);
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

    static List<String> modOptions(File self, File mods, File data) {
        List<String> out = new ArrayList<>();
        File[] jars = mods.listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        List<File> others = new ArrayList<>();
        boolean ours = false;
        if (jars != null) {
            Arrays.sort(jars);
            for (File j : jars) {
                if (!UpdateSwap.isOpenHouseMod(j)) { others.add(j); continue; }
                ours = true;
                say("mods/" + j.getName() + " is OpenHouse, and OpenHost brings its own, so it's left out of this run (the file stays as it is)");
            }
        }
        if (!ours) {
            out.add("-Dfabric.addMods=" + self.getAbsolutePath());
            return out;
        }
        File empty = new File(data, "no-mods");
        File[] stray = empty.listFiles();
        if (stray != null) for (File f : stray) f.delete();
        empty.mkdirs();
        StringBuilder add = new StringBuilder(self.getAbsolutePath());
        for (File j : others) add.append(File.pathSeparator).append(j.getAbsolutePath());
        out.add("-Dfabric.modsFolder=" + empty.getAbsolutePath());
        out.add("-Dfabric.addMods=" + add);
        out.add("-Dopenhouse.openhost.mods=" + mods.getAbsolutePath());
        return out;
    }

    static final int WORLD_FORMAT = 19135;
    static final String CONVERT_KEY = "openhost-convert-world";

    static boolean safeToStart(File here, List<String> pass) {
        java.util.Properties props = new java.util.Properties();
        File sp = new File(here, "server.properties");
        if (sp.isFile()) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(sp)) { props.load(in); } catch (IOException ignored) { }
        }
        int port = 25565;
        try { port = Integer.parseInt(props.getProperty("server-port", "25565").trim()); } catch (NumberFormatException ignored) { }
        String worldName = props.getProperty("level-name", "world").trim();
        if (worldName.isEmpty()) worldName = "world";
        for (int k = 0; k + 1 < pass.size(); k++) {
            if (pass.get(k).equals("--port")) try { port = Integer.parseInt(pass.get(k + 1)); } catch (NumberFormatException ignored) { }
            if (pass.get(k).equals("--world")) worldName = pass.get(k + 1);
        }
        File world = new File(worldName);
        if (!world.isAbsolute()) world = new File(here, worldName);

        String ip = props.getProperty("server-ip", "").trim();
        boolean busy = false;
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.bind(new java.net.InetSocketAddress(ip.isEmpty() ? null : java.net.InetAddress.getByName(ip), port));
        } catch (IOException e) {

            busy = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("in use");
        }
        if (busy) {
            tell("Port " + port + " is already in use. Most likely the server this folder belongs to (or another) is running right now.\n\n"
                    + "Stop it first. Two servers can't share one world, and the world would be damaged.");
            return false;
        }

        int format = worldFormat(new File(world, "level.dat"));
        if (format > WORLD_FORMAT) {
            tell("The world \"" + worldName + "\" was made by a newer Better than Adventure than OpenHost runs"
                    + " (save format " + format + "), so it can't open it.\n\nUse a newer OpenHost, or another world (level-name in server.properties).");
            return false;
        }
        if (format > 0 && format < WORLD_FORMAT) return convertOlderWorld(here, world, worldName, format, props);
        return true;
    }

    static boolean convertOlderWorld(File here, File world, String worldName, int format, java.util.Properties props) {
        File copy = new File(world.getParentFile(), world.getName() + "-before-bta-" + Rtc.BTA_SERVER_VERSION);
        String what = "The world \"" + worldName + "\" was made by an older Better than Adventure (save format " + format + ").\n\n"
                + "OpenHost converts the world when it opens it. After that, the older\n"
                + "server can't open it any more. OpenHost keeps a copy of it as it is now, in \"" + copy.getName() + "\".";

        boolean go = "true".equalsIgnoreCase(props.getProperty(CONVERT_KEY, "").trim()) || copy.exists();
        if (!go && canAsk()) {
            try {
                Object[] options = { "Convert it (keep a copy)", "Don't start" };
                int pick = javax.swing.JOptionPane.showOptionDialog(null, what, "OpenHost", javax.swing.JOptionPane.DEFAULT_OPTION,
                        javax.swing.JOptionPane.WARNING_MESSAGE, null, options, options[1]);
                go = pick == 0;
            } catch (Throwable ignored) { }
        }
        if (!go) {
            say(what.replace("\n", " "));
            say("To go ahead: add " + CONVERT_KEY + "=true to server.properties and start OpenHost again. Not starting.");
            return false;
        }
        if (!copy.exists()) {
            say("Copying the world as it is to " + copy.getName() + " before converting it...");
            try {
                copyTree(world.toPath(), copy.toPath());
            } catch (IOException e) {
                tell("Couldn't copy the world before converting it (" + e.getMessage() + "). Not starting, nothing was changed.");
                return false;
            }
        }
        return true;
    }

    static void copyTree(java.nio.file.Path from, java.nio.file.Path to) throws IOException {
        try (java.util.stream.Stream<java.nio.file.Path> all = java.nio.file.Files.walk(from)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) all::iterator) {
                java.nio.file.Path t = to.resolve(from.relativize(p).toString());

                if (p.getFileName() != null && p.getFileName().toString().equals("session.lock")) continue;
                if (java.nio.file.Files.isDirectory(p)) java.nio.file.Files.createDirectories(t);
                else java.nio.file.Files.copy(p, t, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    static int worldFormat(File levelDat) {
        if (!levelDat.isFile()) return 0;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.util.zip.GZIPInputStream(new java.io.FileInputStream(levelDat))))) {
            if (in.readByte() != 10) return 0;
            in.readUTF();
            Integer v = findDataVersion(in, 0);
            return v == null ? 0 : v;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static Integer findDataVersion(java.io.DataInputStream in, int depth) throws IOException {
        while (true) {
            byte t = in.readByte();
            if (t == 0) return null;
            String name = in.readUTF();
            if (depth == 0 && t == 10 && name.equals("Data")) return findDataVersion(in, 1);
            if (depth == 1 && t == 3 && name.equals("version")) return in.readInt();
            skipTag(in, t);
        }
    }

    private static void skipTag(java.io.DataInputStream in, byte t) throws IOException {
        switch (t) {
            case 1: in.skipBytes(1); break;
            case 2: in.skipBytes(2); break;
            case 3: case 5: in.skipBytes(4); break;
            case 4: case 6: in.skipBytes(8); break;
            case 7: in.skipBytes(in.readInt()); break;
            case 8: in.skipBytes(in.readUnsignedShort()); break;
            case 9: { byte et = in.readByte(); int n = in.readInt(); for (int k = 0; k < n; k++) skipTag(in, et); break; }
            case 10: { byte c; while ((c = in.readByte()) != 0) { in.readUTF(); skipTag(in, c); } break; }
            case 11: in.skipBytes(4 * in.readInt()); break;
            case 12: in.skipBytes(8 * in.readInt()); break;
            default: throw new IOException("NBT tag " + t);
        }
    }

    static boolean noDialogs;

    static boolean canAsk() {
        return !noDialogs && !java.awt.GraphicsEnvironment.isHeadless() && !hasTerminal() && System.getenv("OPENHOST_CONSOLE") == null;
    }

    static void tell(String msg) {
        say(msg.replace("\n\n", " ").replace("\n", " "));
        if (!canAsk()) return;
        try {
            javax.swing.JOptionPane.showMessageDialog(null, msg, "OpenHost", javax.swing.JOptionPane.WARNING_MESSAGE);
        } catch (Throwable ignored) { }
    }

    static volatile File HERE;
    static void state(String what) {
        File h = HERE;
        if (h == null) return;
        File f = new File(h, "openhouse/running.txt");
        try {
            if (what == null) { f.delete(); return; }
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), (what + " " + ProcessHandle.current().pid() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    static void applyModChanges(File here) {
        File f = new File(here, "openhouse/mod-changes.txt");
        if (!f.isFile()) return;
        try {
            for (String l : java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                String[] w = l.trim().split(" ", 2);
                if (w.length < 2 || w[1].contains("/") || w[1].contains("\\") || w[1].contains("..")) continue;
                File jar = new File(here, "mods/" + w[1]);
                if (w[0].equals("rm")) { if (jar.delete()) say("Took " + w[1] + " out of mods/ (the game no longer has it)"); }
                else if (w[0].equals("put")) {
                    File staged = new File(here, "openhouse/mod-staged/" + w[1]);
                    if (staged.isFile()) {
                        java.nio.file.Files.move(staged.toPath(), jar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        say("Updated mods/" + w[1] + " from the game");
                    }
                }
            }
        } catch (IOException e) {
            say("(couldn't finish the game's mod changes: " + e.getMessage() + ")");
        }
        f.delete();
    }

    private static void watchControl(File here) {
        File f = new File(here, "openhouse/control.txt");
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    if (!f.isFile()) continue;
                    String what = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
                    f.delete();
                    OutputStream o = serverIn;
                    if (what.equals("restart") && o != null) {
                        say("Restarting for new mods from the game...");
                        o.write("oh restart\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        o.flush();
                    } else if (what.equals("stop") && o != null) {
                        say("Stopping (asked by the game)...");
                        o.write("stop\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        o.flush();
                    }
                } catch (InterruptedException e) { return; } catch (Exception ignored) { }
            }
        }, "OpenHost control");
        t.setDaemon(true);
        t.start();
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
            Update.Release r = Update.openhost_to_offer();
            if (r == null || !r.newer()) return false;
            say("OpenHost " + r.version + " is out (this is " + Version.VERSION + "), updating...");
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
            say("(couldn't check for or get a newer OpenHost: " + (e.getMessage() == null ? e.toString() : e.getMessage()) + ", carrying on with " + Version.VERSION + ")");
            return false;
        }
    }

    static void removeUpdateCopy(File self) {
        try {
            if (self.isFile() && !self.getName().equals(UPDATE_COPY)) new File(self.getParentFile(), UPDATE_COPY).delete();
        } catch (Exception ignored) { }
    }
}
