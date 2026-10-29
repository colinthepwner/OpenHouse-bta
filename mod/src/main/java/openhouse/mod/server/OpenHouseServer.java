package openhouse.mod.server;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.minecraft.core.net.command.CommandManager;

public final class OpenHouseServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        CommandManager.registerServerCommand(new RoomCommand());
    }
}
