package openhouse.mod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.net.NetworkManager;
import net.minecraft.core.net.handler.PacketHandler;
import net.minecraft.core.net.packet.Packet;

public final class ModWrap extends Packet {
    public static final int ID = openhouse.bridge.Bridge.WRAP;
    static volatile boolean on;
    static final Set<NetworkManager> marked = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public static void register() {
        if (on) return;
        try {
            Packet.addMapping(ID, true, true, ModWrap.class);
            on = true;
        } catch (IllegalArgumentException e) {
            System.out.println("[openhouse] packet id " + ID + " is taken by another mod: mods' packets won't cross BTA versions");
        }
    }

    public static void mark(NetworkManager nm) { if (on && nm != null) marked.add(nm); }

    public static Packet out(NetworkManager nm, Packet p) {
        if (!on || p == null || p instanceof ModWrap || !mod(p.getClass()) || !marked.contains(nm)) return p;
        try { return new ModWrap(p); } catch (IOException e) { return p; }
    }

    static boolean mod(Class<?> c) { return !c.getName().startsWith("net.minecraft."); }

    int inner;
    byte[] data;
    Packet pk;

    public ModWrap() { }

    ModWrap(Packet p) throws IOException {
        inner = p.getId();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        p.write(new DataOutputStream(b));
        data = b.toByteArray();
        pk = p;
        isChunkDataPacket = p.isChunkDataPacket;
    }

    @Override public void read(DataInputStream dis) throws IOException {
        inner = dis.readUnsignedByte();
        int len = dis.readInt();
        if (len < 0 || len > 1 << 24) throw new IOException("mod packet " + inner + " of " + len + " bytes");
        data = new byte[len];
        dis.readFully(data);
        pk = Packet.getNewPacket(inner);

        if (pk == null || pk instanceof ModWrap || !mod(pk.getClass())) throw new IOException("bad mod packet id " + inner);
        pk.read(new DataInputStream(new ByteArrayInputStream(data)));
    }

    @Override public void write(DataOutputStream dos) throws IOException {
        dos.writeByte(inner);
        dos.writeInt(data.length);
        dos.write(data);
    }

    @Override public void handlePacket(PacketHandler h) { pk.handlePacket(h); }

    @Override public int getEstimatedSize() { return 5 + data.length; }
}
