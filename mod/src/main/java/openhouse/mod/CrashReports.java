package openhouse.mod;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import openhouse.OhCrash;

public final class CrashReports {
    private CrashReports() { }

    static volatile boolean reported;
    static volatile String gpu;

    static File game() { return FabricLoader.getInstance().getGameDir().toFile(); }

    public static void install() {
        File g = game();
        OhCrash.journalDir(g);
        openhouse.OpenHostList.game(g);
        OhCrash.enabled(g);
        OhCrash.note("game start: OH " + openhouse.Version.VERSION + ", BTA " + bta() + (OpenHouseMod.PACK.isEmpty() ? "" : ", restarted into room " + OpenHouseMod.PACK + "'s mods"));
        OhCrash.sendPending(g);
        sections();

        openhouse.ModScan.warnAsync(mods(), w -> {
            OpenHouseMod.LOG.warn(w);
            OhCrash.note(w);
            synchronized (chat) { chat.add(w); }
            OhCrash.send(g, new OhCrash.Report("Game", "Possible malware in the mods").bta(bta()).mods(mods()).info("Found", w), false);
        });
        try { Runtime.getRuntime().addShutdownHook(new Thread(CrashReports::atExit, "OpenHouse crash check")); } catch (Exception ignored) { }
        OhCrash.softTimer(() -> quiet(false));
    }

    public static String bta() {
        try { return FabricLoader.getInstance().getModContainer("minecraft").get().getMetadata().getVersion().getFriendlyString(); }
        catch (Throwable t) { return "?"; }
    }

    public static void crash(String desc, Throwable t) {
        try {
            reported = true;
            OhCrash.note("CRASH: " + desc + ": " + t);
            List<String> lines = new ArrayList<>();
            if (t != null) for (String l : stack(t).split("\r?\n")) lines.add(l);
            OhCrash.Report r = new OhCrash.Report("Game", "The game crashed (" + desc + ")").error(t).bta(bta()).mods(mods())
                    .blame(blame(lines)).logFile(new File(game(), "logs/latest.log"), 400);
            OhCrash.send(game(), r, false);
        } catch (Throwable ignored) { }
    }

    public static void problem(String what, String detail, List<String> lines) {
        try {
            OhCrash.note("PROBLEM: " + what + (detail == null ? "" : ": " + detail));
            OhCrash.Report r = new OhCrash.Report("Game", what).bta(bta()).mods(mods()).info("Shown", detail)
                    .logFile(new File(game(), "logs/latest.log"), 300);
            if (lines != null && !lines.isEmpty()) r.trace(OhCrash.story(lines, 150)).blame(blame(lines));
            OhCrash.send(game(), r, false);
        } catch (Throwable ignored) { }
    }

    static final String[] DIED = { "A mod crashed on startup", "Could not execute entrypoint", "Mixin apply failed", "MixinApplyError",
            "Critical injection failure", "An exception has occurred while trying to start the game", "Exception in thread \"main\"",
            "Better than Adventure has crashed" };

    static synchronized void quiet(boolean wait) {
        OhCrash.softFile(new File(game(), "logs/latest.log"), 0);
        OhCrash.softFile(new File(OpenHouseMod.dataDir(), "server/console.log"), OhCrashStart.T);
        OhCrash.flushSoft(game(), new OhCrash.Report("Game", "Things went wrong quietly").bta(bta()).mods(mods()), wait);
    }
    static final class OhCrashStart { static final long T = System.currentTimeMillis(); }

    static void atExit() {
        try { if (!reported) died(); } catch (Throwable ignored) { }
        try { quiet(true); } catch (Throwable ignored) { }
    }

    static void died() {
        try {
            File log = new File(game(), "logs/latest.log");
            if (!log.isFile()) return;
            List<String> lines;
            try { lines = Files.readAllLines(log.toPath(), StandardCharsets.UTF_8); }
            catch (Exception e) { lines = java.util.Arrays.asList(new String(Files.readAllBytes(log.toPath()), StandardCharsets.ISO_8859_1).split("\r?\n")); }
            int at = -1;
            for (int i = 0; i < lines.size() && at < 0; i++) for (String d : DIED) if (lines.get(i).contains(d)) { at = i; break; }
            if (at < 0) return;
            List<String> from = lines.subList(Math.max(0, at - 5), lines.size());
            OhCrash.Report r = new OhCrash.Report("Game", "The game closed after an error while starting").bta(bta()).mods(mods())
                    .trace(OhCrash.story(from, 200)).blame(blame(lines)).log(lines, 400);

            OhCrash.send(game(), r, true);
        } catch (Throwable ignored) { }
    }

    static File mods() {
        String f = System.getProperty("fabric.modsFolder", "");
        File d = f.isEmpty() ? new File(game(), "mods") : new File(f);
        return d.isDirectory() ? d : new File(game(), "mods");
    }

    static String blame(List<String> lines) {
        try {
            File[] js = mods().listFiles((d, n) -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
            if (js == null) return null;
            Map<String, List<File>> by = ModCrash.idsOf(java.util.Arrays.asList(js));
            java.util.Set<String> c = ModCrash.culprits(lines, by, new java.util.HashSet<>());
            if (!c.isEmpty()) return String.join(", ", c);
            return ModCrash.crashMod(lines, by);
        } catch (Throwable t) { return null; }
    }

    static String stack(Throwable t) {
        java.io.StringWriter w = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(w));
        return w.toString();
    }

    public static String diagLine() {
        StringBuilder b = new StringBuilder();
        b.append("OH ").append(openhouse.Version.VERSION).append(" | BTA ").append(bta()).append(" (proto ").append(OpenHouseMod.protocol()).append(")")
                .append(" | ").append(System.getProperty("os.name", "?")).append(" | Java ").append(System.getProperty("java.version", "?"))
                .append(" | ").append(java.util.TimeZone.getDefault().getID())
                .append(" | mem ").append(Runtime.getRuntime().maxMemory() >> 20).append(" MB");
        if (gpu != null) b.append(" | ").append(gpu);
        if (!OpenHouseMod.PACK.isEmpty()) b.append(" | running room ").append(OpenHouseMod.PACK).append("'s mods");
        b.append(" | mods: ").append(modIds());
        return b.toString();
    }

    static String modIds() {
        List<String> l = new ArrayList<>();
        for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
            String id = m.getMetadata().getId();
            if (id.equals("java") || id.equals("minecraft") || id.equals("fabricloader") || id.startsWith("mixinextras")) continue;
            l.add(id + "@" + m.getMetadata().getVersion().getFriendlyString());
        }
        java.util.Collections.sort(l);
        return l.size() + " " + l;
    }

    public static void tick(net.minecraft.client.Minecraft mc) {
        if (gpu == null) {
            try { gpu = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER) + " / GL " + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION); }
            catch (Throwable t) { gpu = "?"; }
        }
        watch(mc);
        if (mc.hudIngame != null && mc.thePlayer != null) synchronized (chat) {
            for (String c : chat) mc.hudIngame.addChatMessage("§c[OpenHouse] " + c);
            chat.clear();
        }
    }
    static final List<String> chat = new ArrayList<>();

    static Object lastFailScreen;
    static openhouse.Joiner.State lastJoin;
    static Object lastJoiner;
    static LocalServer.State lastOtf;
    static Object lastOtfObj;
    static void watch(net.minecraft.client.Minecraft mc) {
        Object s = mc.currentScreen;
        if (s instanceof net.minecraft.client.gui.ScreenConnectFailed && s != lastFailScreen) {
            lastFailScreen = s;
            String[] why = OpenHouseMod.disconnectReason((net.minecraft.client.gui.Screen) s);
            String text = why == null ? "?" : (why[0] + ": " + why[1]);
            String room = OpenHouseMod.connectRoom;
            OhCrash.note("disconnected from " + (room != null ? "room " + room : OpenHouseMod.connectTarget) + ": " + text);

            if (room != null && !benign(text) && !(why != null && ScreenReconnect.dropped(why))) problem("Lost or couldn't reach a room (" + room + ")", text, null);
        }
        openhouse.Joiner j = OpenHouseMod.joinerNow();
        if (j != lastJoiner) { lastJoiner = j; lastJoin = null; }
        if (j != null && j.state != lastJoin) {
            lastJoin = j.state;
            OhCrash.note("room " + j.code + ": " + j.state + (j.detail.isEmpty() ? "" : " (" + j.detail + ")"));
            if (j.state == openhouse.Joiner.State.FAILED && !benign(j.detail)) problem("Couldn't join room " + j.code, j.detail, null);
        }
        LocalServer ls = LocalServer.current();
        if (ls != lastOtfObj) { lastOtfObj = ls; lastOtf = null; }
        if (ls != null && ls.state != lastOtf) {
            lastOtf = ls.state;
            OhCrash.note("open to friends (" + ls.worldDir + "): " + ls.state + (ls.detail.isEmpty() ? "" : " (" + ls.detail + ")"));
            if (ls.state == LocalServer.State.FAILED) {
                List<String> lines = ls.consoleTail(600);
                OhCrash.Report r = new OhCrash.Report("Open to Friends", ls.crash != null ? "The opened world's server crashed" : "Couldn't open the world to friends")
                        .bta(bta()).mods(mods()).info("Shown", ls.detail).info("Crashed in", ls.crashMod)
                        .trace(OhCrash.story(lines, 200)).blame(ls.crashMod != null ? ls.crashMod : blame(lines))
                        .log(lines, 500);
                OhCrash.send(game(), r, false);
            }
        }
    }

    static boolean benign(String t) {
        if (t == null) return true;
        String l = t.toLowerCase(java.util.Locale.ROOT);
        return l.contains("whitelist") || l.contains("password") || l.contains("banned") || l.contains("kicked") || l.contains("server closed")
                || l.contains("closed the world") || l.contains("server is full") || l.contains("stopped") || l.contains("outdated")
                || l.contains("no room") || l.contains("not found") || l.contains("cancel") || l.contains("quit") || l.contains("left the game");
    }

    static void sections() {
        OhCrash.section("Game", () -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            StringBuilder b = new StringBuilder();
            b.append("Screen: ").append(mc == null || mc.currentScreen == null ? "none" : mc.currentScreen.getClass().getName()).append('\n');
            if (mc != null) {
                b.append("World: ").append(mc.currentWorld == null ? "none" : mc.isMultiplayerWorld() ? "multiplayer" : "single player " + OpenHouseMod.singlePlayerWorld).append('\n');
                if (mc.thePlayer != null) b.append("Me: ").append(mc.thePlayer.username).append(" in dim ").append(mc.thePlayer.dimension)
                        .append(String.format(java.util.Locale.ROOT, " at %.0f %.0f %.0f", mc.thePlayer.x, mc.thePlayer.y, mc.thePlayer.z)).append('\n');
            }
            b.append("Connected to: ").append(OpenHouseMod.connectTarget).append(OpenHouseMod.connectRoom == null ? "" : " (room " + OpenHouseMod.connectRoom + ")").append('\n');
            b.append("Graphics: ").append(gpu).append('\n');
            b.append("This game: ").append(diagLine()).append('\n');
            if (!OpenHouseMod.PACK.isEmpty()) b.append("Restarted into room ").append(OpenHouseMod.PACK).append("'s mods (").append(System.getProperty("fabric.modsFolder", "")).append(")\n");
            b.append("Launcher: ").append(System.getProperty("minecraft.launcher.brand", System.getProperty("multimc.instance.title", "?"))).append('\n');
            return b.toString();
        });
        OhCrash.section("Room joined", () -> {
            openhouse.Joiner j = OpenHouseMod.joinerNow();
            if (j == null) return null;
            StringBuilder b = new StringBuilder();
            b.append("Code ").append(j.code).append(", ").append(j.state).append(" ").append(j.detail).append(", path ").append(j.path)
                    .append(", connections ").append(j.connections).append(j.offline ? ", matchmaking unreachable" : "").append('\n');
            b.append("Host: OH ").append(j.hostVersion).append(", proto ").append(j.hostProto).append(", BTA proto ").append(j.hostBta).append('\n');
            b.append("Mod download: ").append(j.modState).append(j.modError.isEmpty() ? "" : " (" + j.modError + ")").append('\n');
            List<openhouse.ModShare.Mod> ml = j.modList;
            if (ml != null) {
                b.append("Host's mods (").append(ml.size()).append("):\n");
                for (openhouse.ModShare.Mod m : ml) b.append(String.format(java.util.Locale.ROOT, "  %-24s %-20s %-8s %s (%d KB)%n", m.id, m.version, m.environment, m.file, m.size >> 10));
            }
            return b.toString();
        });
        OhCrash.section("Open to Friends", () -> {
            LocalServer ls = LocalServer.current();
            if (ls == null) return null;
            StringBuilder b = new StringBuilder();
            b.append("World ").append(ls.worldDir).append(", ").append(ls.state).append(" ").append(ls.detail).append(", code ").append(ls.code).append('\n');
            if (ls.roomProblem != null) b.append("Room codes: ").append(ls.roomProblem).append('\n');
            if (!ls.leftOut.isEmpty()) b.append("Left out of the server: ").append(ls.leftOut).append('\n');
            if (ls.crash != null) b.append("Crash: ").append(ls.crash).append(" in ").append(ls.crashMod).append('\n');
            b.append(ls.serverModText());
            File d = new File(ls.serverDir(), "openhouse/players-diag.txt");
            String dt = OhCrash.readText(d);
            if (dt != null) b.append("Players who joined (their own games):\n").append(dt);
            return b.toString();
        });
        OhCrash.section("Players around", () -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc == null || mc.currentWorld == null) return null;
            List<String> n = new ArrayList<>();
            for (net.minecraft.core.entity.player.Player p : new ArrayList<>(mc.currentWorld.players)) n.add(p.username);
            return n.size() + ": " + String.join(", ", n);
        });
        OhCrash.section("Malware check", () -> openhouse.ModScan.text(mods()));
        OhCrash.section("~Mod block and item ids", () -> {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Integer> e : IdFix.known().entrySet()) b.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
            return b.toString();
        });
    }
}
