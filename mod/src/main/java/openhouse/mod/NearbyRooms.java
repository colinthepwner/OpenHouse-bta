package openhouse.mod;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.gui.server.ServerEntry;
import net.minecraft.client.gui.server.ServerManager;
import net.minecraft.core.lang.I18n;

public final class NearbyRooms {
    private NearbyRooms() { }

    private static final Map<ServerEntry, String> codes = new WeakHashMap<>();

    public static synchronized String code(ServerEntry e) { return e == null ? null : codes.get(e); }

    static String tag(String msg, String open, String close) {
        int a = msg.indexOf(open);
        if (a < 0) return null;
        int b = msg.indexOf(close, a + open.length());
        return b < 0 ? null : msg.substring(a + open.length(), b);
    }

    private static final Map<ServerEntry, String> keys = new WeakHashMap<>();

    public static boolean heard(ServerManager mgr, String ip, String msg) {
        String run = tag(msg, "[OHS]", "[/OHS]"), oh = tag(msg, "[OH]", "[/OH]");
        if (ip == null || (run == null && oh == null)) return false;
        String port = tag(msg, "[AD]", "[/AD]");
        if (port == null) return false;
        int p;
        try { p = Integer.parseInt(port.trim()); } catch (NumberFormatException e) { return false; }
        boolean pw = oh != null && oh.endsWith(",p");
        String code = oh == null ? null : pw ? oh.substring(0, oh.length() - 2) : oh;
        if (code != null && (code.isEmpty() || code.length() > 16)) code = null;
        if (run == null && code == null) return false;
        String key = run != null ? "s" + run : "c" + code;
        String motd = tag(msg, "[MOTD]", "[/MOTD]");
        String name = tag(msg, "[NAME]", "[/NAME]");

        ServerEntry got = null;
        synchronized (NearbyRooms.class) {

            for (ServerEntry e : mgr.getLan()) if (key.equals(keys.get(e))) got = e;
        }
        if (got == null) {
            String addr = ip + ":" + p;
            mgr.onLocalScanned(ip, p, motd, name);
            for (ServerEntry e : mgr.getLan()) if (addr.equals(e.address)) got = e;
            if (got == null) return true;
            synchronized (NearbyRooms.class) { keys.put(got, key); }
        }

        if (code != null) {

            String world = name != null && !name.trim().isEmpty() ? name.trim() : motd;
            if (world != null && world.endsWith(OpenHouseMod.LAN_MARK)) world = world.substring(0, world.length() - OpenHouseMod.LAN_MARK.length());
            if (world != null && !world.trim().isEmpty()) got.baseName = world.trim();
            got.motd = I18n.getInstance().translateKeyAndFormat(pw ? "gui.openhouse.nearby.room.password" : "gui.openhouse.nearby.room", code);
            synchronized (NearbyRooms.class) { codes.put(got, code); }
        } else {
            synchronized (NearbyRooms.class) { if (codes.remove(got) != null) got.motd = motd; }
        }
        return true;
    }
}
