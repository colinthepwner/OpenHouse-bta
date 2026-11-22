package openhouse.mod;

import com.mojang.nbt.tags.CompoundTag;
import com.mojang.nbt.tags.ListTag;
import com.mojang.nbt.tags.Tag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class MissingKeep {
    private MissingKeep() { }

    static final class Keep {
        final int cx, cz;

        final int[][] spots = new int[16][];
        final byte[][] metas = new byte[16][];
        final List<CompoundTag> tiles = new ArrayList<>();
        final List<CompoundTag> items = new ArrayList<>();
        Keep(int cx, int cz) { this.cx = cx; this.cz = cz; }
    }

    static final Map<Object, Keep> KEEP = Collections.synchronizedMap(new WeakHashMap<>());

    public static final ThreadLocal<CompoundTag> LOADING = new ThreadLocal<>();
    static volatile boolean said;

    public static short[][] before(short[][] secs) {
        short[][] b = new short[16][];
        for (int i = 0; i < 16 && i < secs.length; i++) if (secs[i] != null) b[i] = secs[i].clone();
        return b;
    }

    public static void after(Object chunk, short[][] was, short[][] now, byte[][] meta) {
        CompoundTag tag = LOADING.get();
        LOADING.remove();

        int cx = tag == null ? 0 : tag.getInteger("xPos"), cz = tag == null ? 0 : tag.getInteger("zPos");
        Keep k = null;
        int cnt = 0;
        for (int s = 0; s < 16; s++) {
            short[] a = was[s], b = s < now.length ? now[s] : null;
            if (a == null || b == null) continue;
            int n = 0;
            for (int i = 0; i < 4096; i++) if (a[i] != 0 && b[i] == 0) n++;
            if (n == 0) continue;
            if (k == null) k = new Keep(cx, cz);
            int[] sp = new int[n];
            byte[] mt = new byte[n];
            n = 0;
            for (int i = 0; i < 4096; i++) if (a[i] != 0 && b[i] == 0) {
                sp[n] = i << 16 | (a[i] & 0xffff);
                mt[n++] = meta[s] == null ? 0 : meta[s][i];
            }
            k.spots[s] = sp;
            k.metas[s] = mt;
            cnt += n;
        }
        if (tag != null) {
            ListTag tes = tag.getListOrDefault("TileEntities", null);
            if (tes != null) for (Tag<?> t : tes) {
                if (!(t instanceof CompoundTag)) continue;
                CompoundTag te = (CompoundTag) t;
                int x = te.getInteger("x"), y = te.getInteger("y"), z = te.getInteger("z");
                if (k != null && kept_at(k, x, y, z)) { k.tiles.add(copy(te)); continue; }
                ListTag its = te.getListOrDefault("Items", null);
                if (its == null) continue;
                for (Tag<?> it : its) {
                    if (!(it instanceof CompoundTag) || !gone_item((CompoundTag) it)) continue;
                    if (k == null) k = new Keep(cx, cz);
                    CompoundTag e = new CompoundTag();
                    e.putInt("x", x); e.putInt("y", y); e.putInt("z", z);
                    e.putCompound("item", copy(((CompoundTag) it)));
                    k.items.add(e);
                }
            }
        }
        if (k == null) return;
        KEEP.put(chunk, k);
        if (!said) {
            said = true;
            OpenHouseMod_log("blocks/items of mods that aren't here are kept in the save (first at chunk " + cx + "," + cz + ": " + cnt + " blocks, " + k.tiles.size() + " tile entities, " + k.items.size() + " items)");
        }
    }

    static boolean kept_at(Keep k, int x, int y, int z) {
        if (y < 0 || y > 255 || (x >> 4) != k.cx || (z >> 4) != k.cz) return false;
        int[] sp = k.spots[y >> 4];
        if (sp == null) return false;
        int idx = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
        for (int v : sp) if (v != -1 && v >>> 16 == idx) return true;
        return false;
    }

    static boolean gone_item(CompoundTag e) {
        if (e.getByte("Expanded") == 0) return false;
        int id = e.getShort("id");
        net.minecraft.core.item.Item[] l = net.minecraft.core.item.Item.itemsList;
        return id > 0 && (id >= l.length || l[id] == null);
    }

    public static void store(Object chunk, CompoundTag tag) {
        Keep k = KEEP.get(chunk);
        if (k == null) return;
        synchronized (k) {
            ListTag secs = tag.getListOrDefault("Sections", null);
            if (secs == null) { secs = new ListTag(); tag.put("Sections", secs); }
            for (int s = 0; s < 16; s++) {
                int[] sp = k.spots[s];
                if (sp == null) continue;
                CompoundTag cs = null;
                for (Tag<?> t : secs) if (t instanceof CompoundTag && ((CompoundTag) t).getInteger("yPos") == s) { cs = (CompoundTag) t; break; }
                if (cs == null) { cs = new CompoundTag(); cs.putInt("yPos", s); secs.addTag(cs); }
                short[] bl = cs.getShortArrayOrDefault("Blocks", null);
                byte[] dt = cs.getByteArrayOrDefault("Data", null);
                bl = bl == null || bl.length < 4096 ? new short[4096] : bl.clone();
                dt = dt == null || dt.length < 4096 ? new byte[4096] : dt.clone();
                boolean alive = false;
                for (int j = 0; j < sp.length; j++) {
                    if (sp[j] == -1) continue;
                    int i = sp[j] >>> 16;
                    if (bl[i] != 0) { sp[j] = -1; continue; }
                    bl[i] = (short) (sp[j] & 0xffff);
                    dt[i] = k.metas[s][j];
                    alive = true;
                }
                if (!alive) { k.spots[s] = null; continue; }
                cs.putShortArray("Blocks", bl);
                cs.putByteArray("Data", dt);
            }
            if (k.tiles.isEmpty() && k.items.isEmpty()) return;
            ListTag tes0 = tag.getListOrDefault("TileEntities", null);
            if (tes0 == null) { tes0 = new ListTag(); tag.put("TileEntities", tes0); }
            final ListTag tes = tes0;
            Set<Long> have = new HashSet<>();
            for (Tag<?> t : tes) if (t instanceof CompoundTag) have.add(pos((CompoundTag) t));
            k.tiles.removeIf(te -> !kept_at(k, te.getInteger("x"), te.getInteger("y"), te.getInteger("z")));
            for (CompoundTag te : k.tiles) if (have.add(pos(te))) tes.addTag(copy(te));

            k.items.removeIf(e -> {
                for (Tag<?> t : tes) {
                    if (!(t instanceof CompoundTag) || pos((CompoundTag) t) != pos(e)) continue;
                    ListTag its = ((CompoundTag) t).getListOrDefault("Items", null);
                    if (its == null) return true;
                    CompoundTag it = e.getCompound("item");
                    int slot = it.getByte("Slot") & 255;
                    for (Tag<?> o : its) if (o instanceof CompoundTag && (((CompoundTag) o).getByte("Slot") & 255) == slot) return !same(it, (CompoundTag) o);
                    its.addTag(copy(it));
                    return false;
                }
                return true;
            });
        }
    }

    static CompoundTag copy(CompoundTag t) {
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            com.mojang.nbt.NbtIo.write(t, new java.io.DataOutputStream(b));
            return com.mojang.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(b.toByteArray())));
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    static boolean same(CompoundTag a, CompoundTag b) { return a.getShort("id") == b.getShort("id") && a.getByte("Count") == b.getByte("Count"); }

    static long pos(CompoundTag t) { return ((long) t.getInteger("x") & 0x3ffffff) << 38 | ((long) t.getInteger("z") & 0x3ffffff) << 12 | (t.getInteger("y") & 0xfff); }

    public static List<CompoundTag> gone_items(ListTag l) {
        List<CompoundTag> out = null;
        if (l == null) return null;
        for (Tag<?> t : l) {
            if (!(t instanceof CompoundTag) || !gone_item((CompoundTag) t)) continue;
            if (out == null) out = new ArrayList<>();
            out.add(copy(((CompoundTag) t)));
        }
        return out;
    }

    public static void put_back(ListTag l, List<CompoundTag> gone) {
        if (gone == null || gone.isEmpty() || l == null) return;
        Set<Integer> used = new HashSet<>();
        for (Tag<?> t : l) if (t instanceof CompoundTag) used.add(((CompoundTag) t).getByte("Slot") & 255);
        for (CompoundTag it : gone) {
            int slot = it.getByte("Slot") & 255;

            boolean there = false;
            for (Tag<?> t : l) if (t instanceof CompoundTag && same(it, (CompoundTag) t) && (((CompoundTag) t).getByte("Slot") & 255) == slot) there = true;
            if (there) continue;
            if (used.contains(slot)) {
                slot = -1;
                for (int i = 0; i < 36 && slot < 0; i++) if (!used.contains(i)) slot = i;
                if (slot < 0) continue;
            }
            CompoundTag c = copy(it);
            c.putByte("Slot", (byte) slot);
            l.addTag(c);
            used.add(slot);
        }
    }

    static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("OpenHouse");
    static void OpenHouseMod_log(String s) { LOG.info(s); }
    static volatile boolean oopsed;
    public static void oops(Throwable t) {
        if (!oopsed) { oopsed = true; LOG.warn("keeping missing mods' blocks broke, BTA's own handling from here", t); }
    }
}
