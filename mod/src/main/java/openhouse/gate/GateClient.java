package openhouse.gate;

import net.fabricmc.api.ClientModInitializer;

public final class GateClient implements ClientModInitializer {
    @Override public void onInitializeClient() { ((ClientModInitializer) Gate.make("openhouse.mod.OpenHouseMod")).onInitializeClient(); }
}
