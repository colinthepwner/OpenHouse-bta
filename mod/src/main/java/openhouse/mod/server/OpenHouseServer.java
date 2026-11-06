package openhouse.mod.server;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.core.net.command.CommandManager;

public final class OpenHouseServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        CommandManager.registerServerCommand(new RoomCommand());

        QuietLog.install();

        if ("modern".equals(HostUi.launched())) HostWindow.open();
        GuiLog.installIfWanted();
        warnServerCrashers();
    }

    private static void warnServerCrashers() {
        try {
            Set<File> seen = new HashSet<>();
            for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
                ModOrigin o;
                try { o = c.getOrigin(); } catch (Throwable t) { continue; }
                if (o.getKind() != ModOrigin.Kind.PATH) continue;
                for (java.nio.file.Path p : o.getPaths()) {
                    File f = p.toFile();

                    if (!f.isFile() || !f.getName().toLowerCase().endsWith(".jar") || !seen.add(f)) continue;
                    String why = openhouse.mod.ClientCode.serverCrasher(f);
                    if (why != null)
                        ServerRoom.LOG.warn("{} ({}) is for players' own games: {}, which a server hasn't got. It will stop this server the first time that code runs - take it out of mods/.",
                                c.getMetadata().getName(), f.getName(), why);
                }
            }
        } catch (Throwable t) {
            ServerRoom.LOG.info("couldn't read this server's mods for client-side code ({})", t.toString());
        }
    }
}
