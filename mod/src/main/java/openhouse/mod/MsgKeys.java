package openhouse.mod;

import net.minecraft.core.net.packet.PacketMessageTranslatable;

public final class MsgKeys {
    private MsgKeys() { }

    static volatile String[] server;

    public static void got(byte[] d) {
        String all = new String(d, 1, d.length - 1, java.nio.charset.StandardCharsets.UTF_8);
        server = all.isEmpty() ? new String[0] : all.split("\n", -1);
    }

    public static void forget() { server = null; }

    public static short remap(short id) {
        String[] s = server;
        if (s == null || id < 0) return id;
        if (id >= s.length) return -1;
        return PacketMessageTranslatable.KEY2ID_MAP.getOrDefault(s[id], (short) -1);
    }

    public static byte[] wire(byte tag) {
        String[] keys;
        try { keys = PacketMessageTranslatable.ID2KEY_MAP; } catch (Throwable t) { return null; }
        if (keys == null) return null;
        byte[] b = String.join("\n", keys).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[b.length + 1];
        out[0] = tag;
        System.arraycopy(b, 0, out, 1, b.length);
        return out;
    }
}
