package openhouse.gate;

import net.fabricmc.loader.api.FabricLoader;

public final class Gate {
    private Gate() { }

    static Boolean v7;

    public static boolean v7() {
        Boolean b = v7;
        if (b == null) {
            String v = FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
            v7 = b = v.startsWith("7.");
        }
        return b;
    }

    static boolean lang_done;

    static Object make(String cls) {

        if (v7() && !lang_done && !cls.endsWith("PreLaunch")) {
            lang_done = true;
            try { Class.forName("openhouse.v7.Lang").getMethod("load", String.class).invoke(null, "openhouse"); }
            catch (ReflectiveOperationException e) { System.out.println("[openhouse] no lang on 7.3: " + e); }
        }
        String name = v7() ? cls.replace("openhouse.mod.", "openhouse.mod7.") : cls;
        try {
            return Class.forName(name).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("OpenHouse couldn't start " + name, e);
        }
    }
}
