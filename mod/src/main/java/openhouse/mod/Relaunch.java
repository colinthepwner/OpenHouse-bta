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

    public static void start(File packDir, String code) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse(
                new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath()));
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("-Dfabric.modsFolder=") || a.startsWith("-Dopenhouse.autojoin=") || a.startsWith("-Dopenhouse.pack=")
                    || a.startsWith("-agentlib:jdwp") || a.startsWith("-Xrunjdwp")) continue;
            cmd.add(a);
        }
        cmd.add("-Dfabric.modsFolder=" + packDir.getAbsolutePath());
        cmd.add("-Dopenhouse.autojoin=" + code);
        cmd.add("-Dopenhouse.pack=" + code);
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("net.fabricmc.loader.impl.launch.knot.KnotClient");
        cmd.addAll(Arrays.asList(FabricLoader.getInstance().getLaunchArguments(false)));
        File log = new File(OpenHouseMod.dataDir(), "restart.log");
        log.getParentFile().mkdirs();
        new ProcessBuilder(cmd)
                .directory(new File(System.getProperty("user.dir")))
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start();
        OpenHouseMod.LOG.info("restarting with room {}'s mods from {}", code, packDir);
    }
}
