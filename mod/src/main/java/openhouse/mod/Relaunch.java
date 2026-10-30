package openhouse.mod;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

public final class Relaunch {
    private Relaunch() { }

    public static Process start(File packDir, String code) throws IOException {
        List<String> cmd = command(packDir, code);
        File log = new File(OpenHouseMod.dataDir(), "restart.log");
        log.getParentFile().mkdirs();
        Process p = new ProcessBuilder(cmd)
                .directory(new File(System.getProperty("user.dir")))
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start();
        OpenHouseMod.LOG.info("restarting with room {}'s mods from {}", code, packDir);
        return p;
    }

    static List<String> command(File packDir, String autojoin) {
        boolean inPack = !OpenHouseMod.PACK.isEmpty();
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse(
                new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath()));
        String home = System.getProperty("openhouse.home");
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("-Dopenhouse.autojoin=") || a.startsWith("-Dopenhouse.pack=") || a.startsWith("-Dopenhouse.home=")
                    || a.startsWith("-agentlib:jdwp") || a.startsWith("-Xrunjdwp")) continue;
            if (a.startsWith("-Dfabric.modsFolder=") && (packDir != null || inPack)) continue;
            if (a.startsWith("-Djava.library.path=")) a = "-Djava.library.path=" + stableNatives(a.substring("-Djava.library.path=".length()));
            else if (a.startsWith("-Dorg.lwjgl.librarypath=")) a = "-Dorg.lwjgl.librarypath=" + stableNatives(a.substring("-Dorg.lwjgl.librarypath=".length()));
            cmd.add(a);
        }

        for (String k : new String[] { "minecraft.applet.TargetDirectory", "multimc.instance.title", "multimc.instance.icon" }) {
            String v = System.getProperty(k);
            boolean there = false;
            for (String c : cmd) if (c.startsWith("-D" + k + "=")) there = true;
            if (v != null && !there) cmd.add("-D" + k + "=" + v);
        }
        if (packDir != null) {

            if (home == null || home.isEmpty()) home = LocalServer.modsFolder().getAbsolutePath();
            cmd.add("-Dfabric.modsFolder=" + packDir.getAbsolutePath());
            cmd.add("-Dopenhouse.pack=" + autojoin);
            cmd.add("-Dopenhouse.home=" + home);
        } else if (inPack && home != null && !home.isEmpty()) {

            File def = new File(FabricLoader.getInstance().getGameDir().toFile(), "mods");
            if (!new File(home).getAbsoluteFile().equals(def.getAbsoluteFile())) cmd.add("-Dfabric.modsFolder=" + home);
        }
        if (autojoin != null) cmd.add("-Dopenhouse.autojoin=" + autojoin);
        cmd.add("-cp");
        cmd.add(stableClasspath(System.getProperty("java.class.path")));
        cmd.add("net.fabricmc.loader.impl.launch.knot.KnotClient");
        cmd.addAll(Arrays.asList(FabricLoader.getInstance().getLaunchArguments(false)));
        return cmd;
    }

    static File relaunchDir() {
        return new File(OpenHouseMod.dataDir(), "relaunch");
    }

    static String stableClasspath(String cp) {
        File bin = new File(FabricLoader.getInstance().getGameDir().toFile().getAbsoluteFile(), "bin");
        StringBuilder out = new StringBuilder();
        for (String e : cp.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String use = e;
            File f = new File(e).getAbsoluteFile();
            if (f.isFile() && inside(f, bin) && !inside(f, relaunchDir())) {
                try {
                    use = keep(f, new File(relaunchDir(), "bin")).getAbsolutePath();
                } catch (IOException ex) {
                    OpenHouseMod.LOG.warn("couldn't keep a copy of {} for the restart: {}", f, ex.toString());
                }
            }
            if (out.length() > 0) out.append(File.pathSeparator);
            out.append(use);
        }
        return out.toString();
    }

    static String stableNatives(String libPath) {
        File instance = FabricLoader.getInstance().getGameDir().toFile().getAbsoluteFile().getParentFile();
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (String e : libPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String use = e;
            File d = new File(e).getAbsoluteFile();
            if (instance != null && d.isDirectory() && inside(d, instance) && !inside(d, relaunchDir())) {
                try {
                    File to = new File(relaunchDir(), "natives" + (n++ == 0 ? "" : "-" + n));
                    copyTree(d, to);
                    use = to.getAbsolutePath();
                } catch (IOException ex) {
                    OpenHouseMod.LOG.warn("couldn't keep a copy of {} for the restart: {}", d, ex.toString());
                }
            }
            if (out.length() > 0) out.append(File.pathSeparator);
            out.append(use);
        }
        return out.toString();
    }

    static boolean inside(File f, File dir) {
        return f.toPath().toAbsolutePath().normalize().startsWith(dir.toPath().toAbsolutePath().normalize());
    }

    static File keep(File f, File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        File to = new File(dir, f.getName());
        if (to.isFile() && to.length() == f.length() && to.lastModified() == f.lastModified()) return to;
        java.nio.file.Files.copy(f.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        return to;
    }

    static void copyTree(File from, File to) throws IOException {
        File[] kids = from.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) copyTree(k, new File(to, k.getName()));
            else keep(k, to);
        }
    }
}
