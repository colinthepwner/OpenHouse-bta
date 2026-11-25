package openhouse.mod.server;

import java.lang.reflect.Method;
import net.minecraft.server.entity.player.PlayerServer;

public final class Say {
    private Say() { }

    private static volatile Method m;

    public static void to(PlayerServer p, String msg) {
        if (p == null || msg == null) return;
        try {
            Method k = m;
            if (k == null) {
                for (Method c : p.getClass().getMethods()) {
                    if (!c.getName().equals("sendMessage")) continue;
                    Class<?>[] t = c.getParameterTypes();
                    if (t.length == 1 && t[0] == String.class) { k = c; break; }
                    if (t.length == 3 && t[0] == boolean.class && t[2] == String.class) k = c;
                }
                m = k;
            }
            if (k == null) return;
            if (k.getParameterCount() == 1) k.invoke(p, msg);
            else k.invoke(p, false, null, msg);
        } catch (Throwable ignored) { }
    }
}
