package openhouse.gate;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class GatePreLaunch implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {

        if (Gate.v7() && System.getProperty("java.locale.providers") == null) System.setProperty("java.locale.providers", "COMPAT,CLDR");
        ((PreLaunchEntrypoint) Gate.make("openhouse.mod.OpenHousePreLaunch")).onPreLaunch();
    }
}
