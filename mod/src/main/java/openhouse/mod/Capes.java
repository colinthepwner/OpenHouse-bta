package openhouse.mod;

import java.util.Locale;
import java.util.Set;

public final class Capes {
    private Capes() { }

    public static final String OH_CAPE_TEXTURE = "/assets/openhouse/cape/oh_cape.png";

    private static final Set<String> OH_CAPE = Set.of("ct201158", "ixiven");

    public static boolean hasOpenHouseCape(String username) {
        return username != null && OH_CAPE.contains(username.toLowerCase(Locale.ROOT));
    }
}
