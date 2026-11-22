package openhouse.mod;

import net.minecraft.client.net.handler.PacketHandlerClient;
import net.minecraft.core.net.packet.PacketCustomPayload;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

public final class HaveIds {
    private HaveIds() { }

    public static final String CHANNEL = "OH|have";
    private static PacketHandlerClient sent_to;
    private static long at;
    private static int tries;

    public static void frame(PacketHandlerClient l) {
        if (l == null || !OpenHouseMod.connectingToRoom()) { sent_to = null; tries = 0; return; }
        long now = System.currentTimeMillis();

        if (l == sent_to && (tries >= 2 || now - at < 3000)) return;
        if (l != sent_to) tries = 0;
        sent_to = l; at = now; tries++;
        try { l.addToSendQueue(new PacketCustomPayload(CHANNEL, ids())); } catch (Throwable t) { OpenHouseMod.LOG.info("couldn't tell the room our ids: {}", t.toString()); }
    }

    static byte[] ids() throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        java.util.List<Integer> l = new java.util.ArrayList<>();
        net.minecraft.core.block.Block<?>[] bl = net.minecraft.core.block.Blocks.blocksList;
        for (int i = 1; i < bl.length; i++) if (bl[i] != null) l.add(i);
        net.minecraft.core.item.Item[] it = net.minecraft.core.item.Item.itemsList;
        for (int i = 16384; i < it.length; i++) if (it[i] != null) l.add(i);
        o.writeInt(l.size());
        for (int i : l) o.writeShort(i);
        return b.toByteArray();
    }
}
