package openhouse.gate;

import net.fabricmc.api.DedicatedServerModInitializer;

public final class GateServer implements DedicatedServerModInitializer {
    @Override public void onInitializeServer() { ((DedicatedServerModInitializer) Gate.make("openhouse.mod.server.OpenHouseServer")).onInitializeServer(); }
}
