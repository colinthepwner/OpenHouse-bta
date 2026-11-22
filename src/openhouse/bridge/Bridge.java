package openhouse.bridge;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class Bridge {
    public static final int P7 = 29444, P8 = 32769;

    public static String name(int proto) { return proto == P7 ? "7.3" : proto == P8 ? "8.0.1" : "protocol " + proto; }
    static boolean known(int proto) { return proto == P7 || proto == P8; }

    public static int probe(String host, int port, int timeout) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeout);
            s.setSoTimeout(timeout);
            DataOutputStream o = new DataOutputStream(s.getOutputStream());
            o.writeByte(254);
            o.writeByte(1);
            o.writeByte(250);
            utf16(o, "BTAPingHost");
            o.writeShort(7 + 2 * "127.0.0.1".length());
            o.writeByte(0);
            utf16(o, "127.0.0.1");
            o.writeInt(port);
            o.flush();
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            if (in.read() != 255) return 0;
            String[] f = rd_utf16(in, 1024).split("\0");
            return f.length > 1 ? Integer.parseInt(f[1].trim()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static final class HostInfo {
        public String[] msg_keys, msg_text;

        public final Map<Integer, String> bkind = new HashMap<>(), ikind = new HashMap<>();
        public final Map<Integer, Integer> brgb = new HashMap<>(), blight = new HashMap<>();
        public final Map<Integer, Float> bheight = new HashMap<>();
        public final Map<Integer, String> names = new HashMap<>();
        public final Map<Integer, String> worlds = new HashMap<>();
        public final Map<Integer, String> biomes = new HashMap<>();

        public static HostInfo parse(java.util.List<String> lines) {
            HostInfo h = new HostInfo();
            java.util.List<String> keys = new java.util.ArrayList<>(), text = new java.util.ArrayList<>();
            for (String l : lines) {
                String[] f = l.split("\t", -1);
                try {
                    if (f[0].equals("B") && f.length >= 7) {
                        int id = Integer.parseInt(f[1]);
                        h.bkind.put(id, f[3]);
                        h.brgb.put(id, Integer.parseInt(f[4], 16));
                        h.blight.put(id, Integer.parseInt(f[5]));
                        h.bheight.put(id, Float.parseFloat(f[6]));
                        if (f.length > 7) h.names.put(id, f[7]);
                    } else if (f[0].equals("I") && f.length >= 4) {
                        h.ikind.put(Integer.parseInt(f[1]), f[3]);
                        if (f.length > 4) h.names.put(Integer.parseInt(f[1]), f[4]);
                    } else if (f[0].equals("G") && f.length >= 3) {
                        h.biomes.put(Integer.parseInt(f[1]), f[2]);
                    } else if (f[0].equals("W") && f.length >= 3) {
                        h.worlds.put(Integer.parseInt(f[1]), f[2]);
                    } else if (f[0].equals("M") && f.length >= 3) {
                        keys.add(f[1]); text.add(f[2]);
                    }
                } catch (RuntimeException ignored) { }
            }
            h.msg_keys = keys.toArray(new String[0]);
            h.msg_text = text.toArray(new String[0]);
            return h;
        }
    }

    final int srv;
    final HostInfo info;
    final java.util.function.Consumer<String> log;
    volatile int cli;
    volatile boolean xlate;
    final CountDownLatch decided = new CountDownLatch(1);
    volatile boolean ping_fix;

    public Bridge(int server_proto, HostInfo info, java.util.function.Consumer<String> log) {
        this.srv = server_proto;
        this.info = info;
        this.log = log;

        boolean host8 = server_proto == P8;
        String[] hs = host8 ? BIOMES_8 : BIOMES_7;
        if (info != null && !info.biomes.isEmpty()) {
            int top = 0;
            for (int k : info.biomes.keySet()) top = Math.max(top, k);
            hs = new String[top + 1];
            for (Map.Entry<Integer, String> e : info.biomes.entrySet()) if (e.getKey() >= 0) hs[e.getKey()] = e.getValue();
        }
        String[] cs = host8 ? BIOMES_7 : BIOMES_8;
        bio_down = biome_map(hs, cs, host8);
        bio_up = biome_map(cs, hs, !host8);
        bio_none = (byte) index(hs, "overworld.plains");
    }

    static final String[] BIOMES_7 = {"nether.nether","overworld.birch_forest","overworld.boreal_forest","overworld.caatinga","overworld.caatinga.plains","overworld.desert","overworld.forest","overworld.glacier","overworld.grasslands","overworld.hell","overworld.meadow","overworld.outback","overworld.outback.grassy","overworld.plains","overworld.rainforest","overworld.retro","overworld.seasonal_forest","overworld.shrubland","overworld.swampland","overworld.swampland.muddy","overworld.taiga","overworld.tundra","paradise.paradise"};
    static final String[] BIOMES_8 = {"drift.drift","nether.crag","nether.crystal_forest","nether.crystal_plains","nether.legacy.hell","nether.old_world","nether.old_world.desert","nether.shelf","nether.sulfur_pools","nether.volcanic_islands","overworld.birch_forest","overworld.boreal_forest","overworld.caatinga","overworld.caatinga.plains","overworld.desert","overworld.forest","overworld.glacier","overworld.grasslands","overworld.hell","overworld.legacy.desert","overworld.legacy.forest","overworld.legacy.plains","overworld.legacy.rainforest","overworld.legacy.savanna","overworld.legacy.seasonal_forest","overworld.legacy.shrubland","overworld.legacy.swampland","overworld.legacy.taiga","overworld.legacy.tundra","overworld.meadow","overworld.outback","overworld.outback.grassy","overworld.plains","overworld.rainforest","overworld.retro","overworld.seasonal_forest","overworld.shrubland","overworld.swampland","overworld.swampland.muddy","overworld.taiga","overworld.tundra"};
    final byte[] bio_down, bio_up;
    final byte bio_none;

    static int index(String[] a, String k) {
        for (int i = 0; i < a.length; i++) if (k.equals(a[i])) return i;
        return -1;
    }

    static byte[] biome_map(String[] src, String[] dst, boolean from8) {
        byte[] m = new byte[256];
        int plains = Math.max(0, index(dst, "overworld.plains"));
        for (int i = 0; i < 256; i++) {
            String k = i < src.length ? src[i] : null;
            int to = -1;
            if (k != null) {
                to = index(dst, k);
                if (to < 0 && from8) {
                    if (k.startsWith("overworld.legacy.")) to = index(dst, k.equals("overworld.legacy.savanna") ? "overworld.plains" : "overworld." + k.substring(17));
                    else if (k.startsWith("nether.")) to = index(dst, "nether.nether");
                    else if (k.startsWith("drift.")) to = index(dst, "paradise.paradise");
                } else if (to < 0) {
                    if (k.equals("nether.nether")) to = index(dst, "nether.legacy.hell");
                    else if (k.equals("paradise.paradise")) to = index(dst, "drift.drift");
                }
            }
            m[i] = (byte) (to < 0 ? plains : to);
        }
        return m;
    }

    public boolean bridging() { return xlate; }

    static final boolean DEBUG = System.getenv("OPENHOUSE_DEBUG") != null;
    void dbg(String m) { if (DEBUG) log.accept("bridge: " + m); }

    private final Object cli_lock = new Object(), srv_lock = new Object();
    private volatile OutputStream to_cli, to_srv;

    public void up(InputStream raw, OutputStream out) throws IOException {
        to_srv = out;
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 16384));
        try {
            if (known(srv)) sniff(in, out);
        } finally {
            if (decided.getCount() > 0) decided.countDown();
        }
        if (!xlate) { copy(in, out, srv_lock); return; }
        Pk p = new Pk(in, cli == P8, srv == P8, true);
        try {
            while (true) {
                int id = in.read();
                if (id < 0) return;
                p.begin(id);
                c2s(p, id);
                p.flush_to(out, srv_lock, in.available() == 0);
            }
        } catch (IOException e) {

            if (!(e instanceof EOFException) && !(e instanceof java.net.SocketException)) tell_player(e);
            throw e;
        }
    }

    void tell_player(Exception e) {
        try {
            log.accept("bridge failed (" + name(cli) + " player, " + name(srv) + " server): " + e.getMessage());
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream w = new DataOutputStream(b);
            w.writeByte(255);
            String why = "OpenHouse couldn't translate between BTA " + name(cli) + " and " + name(srv) + ". Tell the host: " + e.getMessage();
            utf16(w, why.length() > 250 ? why.substring(0, 250) : why);
            inject_cli(b);
        } catch (Exception ignored) { }
    }

    private void sniff(DataInputStream in, OutputStream out) throws IOException {
        try {
            sniff_(in, out);
        } catch (EOFException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            if (xlate) throw e;
            dbg("start didn't parse (" + e + "), copying as it is");
            in.reset();
        }
    }

    private void sniff_(DataInputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream w = new DataOutputStream(b);
        while (true) {
            in.mark(70000);
            int id = in.read();
            dbg("player's first packets: " + id);
            if (id < 0) return;
            if (id == 2) {
                w.writeByte(2);
                utf8(w, rd_utf8(in, 16));
                send(out, srv_lock, b, true);
                continue;
            }
            if (id == 254) {
                int payload = in.readUnsignedByte(), ident = in.readUnsignedByte();
                String what = rd_utf16(in, 255);
                in.readUnsignedShort();
                int pv = in.readUnsignedByte();
                String host = rd_utf16(in, 255);
                int port = in.readInt();

                int them = pv == (P7 & 255) ? P7 : pv == (P8 & 255) ? P8 : 0;
                if (them != 0 && them != srv) { cli = them; ping_fix = true; }
                w.writeByte(254); w.writeByte(payload); w.writeByte(ident); utf16(w, what);
                w.writeShort(7 + 2 * host.length()); w.writeByte(pv); utf16(w, host); w.writeInt(port);
                send(out, srv_lock, b, true);
                return;
            }
            if (id == 1) {
                int proto = in.readInt();
                dbg("login says " + proto);
                if (proto == srv || !known(proto)) { in.reset(); return; }
                cli = proto;
                Pk p = new Pk(in, cli == P8, srv == P8, true);
                p.begin(1);
                p.o.writeInt(srv);
                login_rest(p);
                xlate = true;
                log.accept("bridging a BTA " + name(proto) + " player to this BTA " + name(srv) + " server");

                if (srv_oh) send(out, srv_lock, bridged_note(), false);
                p.flush_to(out, srv_lock, true);
                return;
            }
            in.reset();
            return;
        }
    }

    public void down(InputStream raw, OutputStream out) throws IOException {
        to_cli = out;
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 65536));
        if (known(srv)) {
            try {
                pre_login(in, out);
            } catch (EOFException e) {
                return;
            } catch (IOException | RuntimeException e) {

                dbg("server's start didn't parse (" + e + ")");
                in.reset();
                try { decided.await(); } catch (InterruptedException ie) { throw new IOException(ie); }
            }
        }
        if (!xlate) { copy(in, out, cli_lock); return; }
        Pk p = new Pk(in, srv == P8, cli == P8, false);
        try {
            while (true) {
                int id = in.read();
                if (id < 0) return;
                if (id == 1) send(out, cli_lock, bridged_note(), false);
                p.begin(id);
                s2c(p, id);
                p.flush_to(out, cli_lock, in.available() == 0);
            }
        } catch (IOException e) {

            if (!(e instanceof EOFException) && !(e instanceof java.net.SocketException)) tell_player(e);
            throw e;
        }
    }

    private void pre_login(DataInputStream in, OutputStream out) throws IOException {
        {
            while (decided.getCount() > 0 || ping_fix) {
                in.mark(70000);
                int id = in.read();
                dbg("server's first packets: " + id);
                if (id < 0) return;
                if (id == 2) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream w = new DataOutputStream(b);
                    w.writeByte(2);
                    utf8(w, rd_utf8(in, 32767));
                    send(out, cli_lock, b, true);
                    continue;
                }

                if (id == 250 || id == 0) {
                    if (id == 250) srv_oh = true;
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream w = new DataOutputStream(b);
                    w.writeByte(id);
                    if (id == 250) {
                        utf8(w, rd_utf8(in, 128));
                        int len = in.readInt();
                        w.writeInt(len);
                        if (len > 0 && len < 32768) { byte[] d = new byte[len]; in.readFully(d); w.write(d); }
                    }
                    send(out, cli_lock, b, true);
                    continue;
                }
                if (id == 255) {
                    String why = rd_utf16(in, 32767);
                    if (ping_fix && why.startsWith("§1\0")) {
                        String[] f = why.split("\0", -1);
                        if (f.length > 2) { f[1] = String.valueOf(cli); why = String.join("\0", f); }
                    }
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream w = new DataOutputStream(b);
                    w.writeByte(255);
                    utf16(w, why);
                    send(out, cli_lock, b, true);
                    continue;
                }
                in.reset();
                try { decided.await(); } catch (InterruptedException e) { throw new IOException(e); }
                break;
            }
        }
    }

    static void copy(InputStream in, OutputStream out, Object lock) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            synchronized (lock) {
                out.write(buf, 0, n);
                if (in.available() == 0) out.flush();
            }
        }
    }

    static void send(OutputStream out, Object lock, ByteArrayOutputStream b, boolean flush) throws IOException {
        synchronized (lock) {
            b.writeTo(out);
            if (flush) out.flush();
        }
        b.reset();
    }

    void inject_cli(ByteArrayOutputStream b) throws IOException { OutputStream o = to_cli; if (o != null) send(o, cli_lock, b, true); }
    void inject_srv(ByteArrayOutputStream b) throws IOException { OutputStream o = to_srv; if (o != null) send(o, srv_lock, b, true); }

    volatile int my_eid = -1;
    final Map<Integer, String> ent_names = new HashMap<>();
    final Map<Integer, Integer> state_id = new HashMap<>();
    final Map<Integer, Integer> made_state = new HashMap<>();
    volatile short action_cnt = 1;
    final Set<Integer> dropped_ents = new HashSet<>();
    final Map<Integer, String> ent_type = new java.util.concurrent.ConcurrentHashMap<>();
    volatile byte[] carried;

    final class Pk {
        final DataInputStream in;
        final boolean a8, b8, c2s;
        final ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
        final DataOutputStream o = new DataOutputStream(buf);
        int mark;

        Pk(DataInputStream in, boolean a8, boolean b8, boolean c2s) { this.in = in; this.a8 = a8; this.b8 = b8; this.c2s = c2s; }

        void begin(int id) throws IOException { mark = buf.size(); o.writeByte(id); }

        void drop() { byte[] keep = java.util.Arrays.copyOf(buf.toByteArray(), mark); buf.reset(); buf.write(keep, 0, keep.length); }
        void flush_to(OutputStream out, Object lock, boolean flush) throws IOException {
            if (buf.size() == 0) { if (flush) synchronized (lock) { out.flush(); } return; }
            send(out, lock, buf, flush);
        }

        void reid(int id) { byte[] b = buf.toByteArray(); b[mark] = (byte) id; buf.reset(); buf.write(b, 0, b.length); }

        int i8() throws IOException { int v = in.readByte(); o.writeByte(v); return v; }
        int u8() throws IOException { int v = in.readUnsignedByte(); o.writeByte(v); return v; }
        int i16() throws IOException { int v = in.readShort(); o.writeShort(v); return v; }
        int i32() throws IOException { int v = in.readInt(); o.writeInt(v); return v; }
        long i64() throws IOException { long v = in.readLong(); o.writeLong(v); return v; }
        float f32() throws IOException { float v = in.readFloat(); o.writeFloat(v); return v; }
        double f64() throws IOException { double v = in.readDouble(); o.writeDouble(v); return v; }
        void n(int count) throws IOException { byte[] b = new byte[count]; in.readFully(b); o.write(b); }
        String s8(int max) throws IOException { String s = rd_utf8(in, max); utf8(o, s); return s; }
        String s16(int max) throws IOException { String s = rd_utf16(in, max); utf16(o, s); return s; }
        String jutf() throws IOException { String s = in.readUTF(); o.writeUTF(s); return s; }
        void uuid() throws IOException { i64(); i64(); }

        byte[] rd_nbt() throws IOException { int n = in.readUnsignedShort(); if (n == 0) return null; byte[] b = new byte[n]; in.readFully(b); return b; }
        void wr_nbt(byte[] b) throws IOException { if (b == null) { o.writeShort(0); return; } o.writeShort(b.length); o.write(b); }
        void nbt() throws IOException { wr_nbt(rd_nbt()); }
    }

    void c2s(Pk p, int id) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        switch (id) {
            case 0: break;
            case 1: in.readInt(); o.writeInt(srv); login_rest(p); break;
            case 2: p.s8(16); break;
            case 3: chat(p); break;
            case 7: p.i32(); p.i32(); p.i8(); break;
            case 9: respawn(p); break;
            case 10: p.u8(); break;
            case 11: p.f64(); p.f64(); p.f64(); p.u8(); break;
            case 12: p.f32(); p.f32(); p.u8(); break;
            case 13: p.f64(); p.f64(); p.f64(); p.f32(); p.f32(); p.u8(); break;
            case 14: p.u8(); p.i32(); p.u8(); p.i32(); p.u8(); p.f64(); p.f64(); break;
            case 15: {
                p.i32(); p.u8(); p.i32(); p.u8(); p.f64(); p.f64(); p.i8();
                if (!stack_plain(p)) p.drop();
                break;
            }
            case 16: p.i16(); break;
            case 18: p.i32(); p.i8(); break;
            case 19: p.i8(); break;
            case 26: vehicle(p); break;
            case 27: held(p); break;
            case 35: {
                if (p.a8) in.readInt(); else if (p.b8) o.writeInt(my_eid);
                p.s16(256); p.i8();
                break;
            }
            case 36: {
                in.readInt(); in.readShort();
                p.drop();
                break;
            }
            case 101: p.i8(); break;
            case 102: click(p); break;
            case 106: p.i8(); p.i16(); p.u8(); if (p.b8) p.drop(); break;
            case 107: {
                in.readByte(); in.readInt(); in.readUTF();
                p.drop();
                break;
            }
            case 108: p.i8(); break;
            case 121: p.jutf(); p.jutf(); p.i32(); break;
            case 130: p.i32(); p.i16(); p.i32(); for (int i = 0; i < 4; i++) p.s16(15); p.i32(); p.i32(); break;
            case 132: p.i32(); p.i16(); p.i32(); p.i16(); break;
            case 133: p.u8(); break;
            case 137: p.i32(); p.jutf(); break;
            case 139: p.i32(); break;
            case 250: payload(p); break;
            case WRAP: wrapped(p); break;
            case 255: p.s16(255); break;
            default: throw new IOException("bridge: a BTA " + name(cli) + " player sent packet " + id + ", which has no " + name(srv) + " form");
        }
    }

    void s2c(Pk p, int id) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        switch (id) {
            case 0: break;
            case 1: my_eid = p.i32(); login_rest(p); break;
            case 2: p.s8(32767); break;
            case 3: chat(p); break;
            case 4: p.i64(); break;
            case 5: p.i32(); p.i16(); item_split(p, true); break;
            case 6: p.i32(); p.i32(); p.i32(); break;
            case 8: p.i16(); break;
            case 9: respawn(p); break;
            case 10: p.u8(); break;
            case 11: p.f64(); eye_y(p); p.f64(); p.u8(); break;
            case 12: p.f32(); p.f32(); p.u8(); break;
            case 13: p.f64(); eye_y(p); p.f64(); p.f32(); p.f32(); p.u8(); break;
            case 16: p.i16(); break;
            case 17: p.i32(); p.i8(); p.i32(); p.i32(); p.i32(); break;
            case 18: p.i32(); p.i8(); break;
            case 20: add_player(p); break;
            case 21: {
                p.i32();
                int iid = in.readShort(), cnt = in.readByte(), dmg = in.readShort();
                byte[] tag = p.rd_nbt();
                Object[] it = item(iid, dmg, tag, !p.b8);
                o.writeShort((Integer) it[0]); o.writeByte(cnt); o.writeShort((Integer) it[1]); p.wr_nbt((byte[]) it[2]);
                p.i32(); p.i32(); p.i32(); p.i8(); p.i8(); p.i8();
                break;
            }
            case 22: p.i32(); p.i32(); break;
            case 23: add_entity(p); break;
            case 24: add_mob(p); break;
            case 25: p.i32(); p.s8(32767); p.i32(); p.i32(); p.i32(); p.i32(); p.i32(); p.i32(); break;
            case 26: vehicle(p); break;
            case 27: held(p); break;
            case 28: if (skip_ent(p, 6)) break; p.i16(); p.i16(); p.i16(); break;
            case 29: { int e = p.i32(); ent_type.remove(e); if (dropped_ents.remove(e)) p.drop(); break; }
            case 30: if (skip_ent(p, 0)) break; break;
            case 31: if (skip_ent(p, 3)) break; p.n(3); break;
            case 32: if (skip_ent(p, 2)) break; p.n(2); break;
            case 33: if (skip_ent(p, 5)) break; p.n(5); break;
            case 34: if (skip_ent(p, 14)) break; p.i32(); p.i32(); p.i32(); p.u8(); p.u8(); break;
            case 35: {
                int e = p.a8 ? in.readInt() : my_eid;
                if (p.b8) o.writeInt(e);
                p.s16(256); p.i8();

                if (!p.b8 && e != my_eid) p.drop();
                break;
            }
            case 36:
                if (p.a8) { in.readInt(); in.readShort(); p.drop(); break; }
                p.reid(37); fling(p); break;
            case 37: p.reid(36); fling(p); break;
            case 38: if (skip_ent(p, 5)) break; p.i8(); p.f32(); break;
            case 39: {
                p.i32();
                if (p.u8() != 0) { p.i32(); p.i32(); p.i32(); } else p.i32();
                break;
            }
            case 40: { int e = p.i32(); boolean gone = dropped_ents.contains(e); ent_data(p, ent_type.get(e)); if (gone) p.drop(); break; }
            case 41: p.i32(); gamemode(p); break;
            case 42: if (skip_ent_nbt(p)) break; p.wr_nbt(tag_fix(p.rd_nbt(), !p.b8)); break;
            case 50: p.i32(); p.i32(); p.u8(); break;
            case 51: region(p); break;
            case 52: {
                p.i32(); p.i32();
                int n = p.i16() & 0xffff;
                p.n(n * 4);
                int[] ids = new int[n];
                for (int i = 0; i < n; i++) ids[i] = in.readShort() & 0xffff;
                byte[] meta = new byte[n];
                in.readFully(meta);
                for (int i = 0; i < n; i++) {
                    int[] r = blk_meta(ids[i], meta[i] & 255, !p.b8);
                    o.writeShort(r[0]);
                    meta[i] = (byte) r[1];
                }
                o.write(meta);
                break;
            }
            case 53: {
                p.i32(); p.i16(); p.i32();
                int b = in.readShort() & 0xffff, m = in.readUnsignedByte();
                int[] r = blk_meta(b, m, !p.b8);
                o.writeShort(r[0]); o.writeByte(r[1]);
                break;
            }
            case 54: p.i32(); p.i16(); p.i32(); p.i8(); p.i8(); break;
            case 60: {
                p.f64(); p.f64(); p.f64(); p.f32();
                int n = p.i32();
                p.n(n * 3);
                p.u8();
                break;
            }
            case 61: {
                int ev = p.i32(); p.i32(); p.i32(); p.i32();
                int data = in.readInt();
                if (ev == 2001 && data > 0) data = blk(data & 0x3fff, !p.b8) | data & ~0x3fff;
                o.writeInt(data);
                break;
            }
            case 62: p.i16(); p.i8(); p.f32(); p.f32(); p.f32(); p.f32(); p.f32(); break;
            case 63: particle(p); break;
            case 70: message70(p); break;
            case 71: p.i32(); p.i8(); p.i32(); p.i32(); p.i32(); break;
            case 72: p.s8(16); p.s16(256); p.uuid(); p.i32(); p.i8(); p.u8(); p.u8(); break;
            case 73: p.i32(); p.i32(); p.i32(); p.i64(); p.f32(); p.f32(); break;
            case 74: p.nbt(); break;
            case 75: in.readUTF(); in.readLong(); p.drop(); break;
            case 100: p.i8(); p.i8(); p.jutf(); p.i8(); break;
            case 101: p.i8(); break;
            case 103: set_slot(p); break;
            case 104: set_content(p); break;
            case 105: p.i8(); p.i16(); p.i16(); break;
            case 106: {
                int w = in.readByte(), act = in.readShort(), ok = in.readUnsignedByte();
                p.drop();

                if (!p.a8 && ok == 0) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    DataOutputStream w2 = new DataOutputStream(b);
                    w2.writeByte(106); w2.writeByte(w); w2.writeShort(act); w2.writeByte(1);
                    inject_srv(b);
                }
                break;
            }
            case 108: p.i8(); break;
            case 120: p.jutf(); break;
            case 131: map_data(p); break;
            case 132: p.i32(); p.i16(); p.i32(); p.i16(); break;
            case 136: p.s8(392); break;
            case 138: { int n = p.i32(); for (int i = 0; i < n; i++) { p.s16(256); p.s16(256); } break; }
            case 140: p.wr_nbt(tag_fix(p.rd_nbt(), !p.b8)); break;
            case 142: p.i8(); p.i32(); p.i16(); p.i32(); break;
            case 143: p.u8(); break;
            case 200: p.s8(Integer.MAX_VALUE); p.i8(); break;
            case 201: sync_ids(p); break;
            case 250: payload(p); break;
            case WRAP: wrapped(p); break;
            case 253: { int n = in.readInt(); skip(in, n); p.drop(); break; }
            case 255: p.s16(32767); break;
            default: throw new IOException("bridge: the BTA " + name(srv) + " server sent packet " + id + ", which has no " + name(cli) + " form");
        }
    }

    void login_rest(Pk p) throws IOException {
        DataInputStream in = p.in;
        p.s8(16); p.uuid(); p.s8(392); p.i64();
        int dim = p.a8 ? in.readInt() : in.readByte();
        int wt = p.a8 ? in.readInt() : in.readByte();
        if (p.b8) { p.o.writeInt(dim); p.o.writeInt(world(wt, p.a8)); }
        else { p.o.writeByte(dim); p.o.writeByte(world(wt, p.a8)); }
        p.i8();
    }

    void respawn(Pk p) throws IOException {
        int dim = p.a8 ? p.in.readInt() : p.in.readByte();
        int wt = p.a8 ? p.in.readInt() : p.in.readByte();
        if (p.b8) { p.o.writeInt(dim); p.o.writeInt(world(wt, p.a8)); }
        else { p.o.writeByte(dim); p.o.writeByte(world(wt, p.a8)); }
    }

    static final String[] W7 = sorted(Tables.WORLD_7), W8 = sorted(Tables.WORLD_8);
    static String[] sorted(String[] a) { String[] b = a.clone(); java.util.Arrays.sort(b); return b; }

    int world(int id, boolean from8) {
        String[] src = from8 ? W8 : W7, dst = from8 ? W7 : W8;
        String k = info != null ? info.worlds.get(id) : null;
        if (k == null) {
            if (id < 0 || id >= src.length) return id;
            k = src[id];
        }
        if (k.equals("drift.default")) k = "paradise.default";
        else if (k.equals("paradise.default")) k = "drift.default";
        else if (k.equals("nether.beta_173")) k = "nether.default";
        else if (k.equals("overworld.beta_173")) k = "overworld.retro";
        for (int i = 0; i < dst.length; i++) if (dst[i].equals(k)) return i;
        for (int i = 0; i < dst.length; i++) if (dst[i].equals("overworld.default")) return i;
        return 0;
    }

    void chat(Pk p) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        int type;
        boolean enc;
        String msg;
        if (p.a8) {
            type = in.readByte();
            enc = in.readBoolean();
            if ((type & 0x80) != 0) in.readShort();
            type &= 127;
            msg = rd_utf8(in, 1024);
        } else {
            type = in.readByte();
            msg = rd_utf16(in, 1024);
            enc = in.readBoolean();
        }
        if (p.b8) {
            o.writeByte(type);
            o.writeBoolean(enc);
            utf8(o, cut_utf8(msg, 1024));
        } else {
            o.writeByte(type);
            utf16(o, msg.length() > 1024 ? msg.substring(0, 1024) : msg);
            o.writeBoolean(enc);
        }
    }

    void message70(Pk p) throws IOException {
        DataInputStream in = p.in;
        if (!p.a8) {
            int state = in.readByte();
            p.drop();
            if (state == 0) plain_chat(p, "Your home bed was missing or obstructed");
            return;
        }
        int key = in.readShort();
        int type = in.readByte();
        if ((type & 0x80) != 0) in.readShort();
        int argc = Math.min(in.readUnsignedByte(), 4);
        int[] lens = new int[argc];
        for (int i = 0; i < argc; i++) lens[i] = in.readUnsignedByte();
        String[] args = new String[argc];
        for (int i = 0; i < argc; i++) { byte[] b = new byte[lens[i]]; in.readFully(b); args[i] = new String(b, StandardCharsets.UTF_8); }
        p.drop();
        HostInfo h = info;
        if (h == null || h.msg_text == null || key < 0 || key >= h.msg_text.length) return;
        String text = h.msg_text[key];
        try { text = String.format(text, (Object[]) args); } catch (Exception ignored) { }
        plain_chat(p, text);
    }

    void plain_chat(Pk p, String text) throws IOException {
        DataOutputStream o = p.o;
        p.begin(3);
        if (p.b8) { o.writeByte(0); o.writeBoolean(false); utf8(o, cut_utf8(text, 1024)); }
        else { o.writeByte(0); utf16(o, text.length() > 1024 ? text.substring(0, 1024) : text); o.writeBoolean(false); }
    }

    void gamemode(Pk p) throws IOException {
        int gm;
        if (p.a8) {
            String k = rd_utf16(p.in, 256);
            gm = 0;
            for (int i = 0; i < GAMEMODES.length; i++) if (GAMEMODES[i].equals(k)) gm = i;
        } else gm = p.in.readByte();
        if (p.b8) utf16(p.o, GAMEMODES[gm >= 0 && gm < GAMEMODES.length ? gm : 0]);
        else p.o.writeByte(gm);
    }
    static final String[] GAMEMODES = { "minecraft:gamemode/survival", "minecraft:gamemode/creative", "minecraft:gamemode/hardcore",
            "minecraft:gamemode/adventure", "minecraft:gamemode/spectator" };

    void add_player(Pk p) throws IOException {
        DataInputStream in = p.in;
        p.i32(); p.s8(16); p.uuid(); p.i32(); p.i32(); p.i32(); p.i8(); p.i8();
        int cur = in.readShort();
        p.o.writeShort(cur > 0 ? (Integer) item(cur, 0, null, !p.b8)[0] : cur);
        p.s16(256); p.i8();
        if (p.a8) in.readShort();
        else if (p.b8) p.o.writeShort(0);
        gamemode(p);
        if (p.u8() == 1) p.nbt();
    }

    void add_entity(Pk p) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        int eid = p.i32();
        int type = in.readShort();
        String name = ent_names.get(type);
        if (name != null) ent_type.put(eid, name);
        boolean drop = false;
        if (!p.b8 && name != null && !ENTS_7.contains(name)) {
            if (name.equals("minecraft:arrow_flaming")) {
                Integer arrow = id_of("minecraft:arrow");
                if (arrow != null) type = arrow; else drop = true;
            } else drop = true;
        }
        o.writeShort(type);
        p.i32(); p.i32(); p.i32(); p.f32(); p.f32();
        ent_data(p, name);
        int opt = p.u8();
        if ((opt & 1) != 0) { p.i16(); p.i16(); p.i16(); }
        if ((opt & 2) != 0) p.i32();
        if ((opt & 4) != 0) p.i32();
        if ((opt & 8) != 0) {
            byte[] tag = p.rd_nbt();
            if (tag != null && "minecraft:falling_block".equals(name) && !p.b8) {
                Map<String, Object> m = Nbt.unzip(tag);
                if (m != null && m.get("Tile") instanceof Short) {
                    int[] r = blk_meta(((Short) m.get("Tile")) & 0xffff, Nbt.num(m.get("TileData"), 0), true);
                    m.put("Tile", (short) r[0]);
                    m.put("TileData", (short) r[1]);
                    m.remove("TileEntity");
                    tag = Nbt.zip(m);
                }
            }
            p.wr_nbt(tag);
        }
        if (drop) { dropped_ents.add(eid); p.drop(); }
    }

    void add_mob(Pk p) throws IOException {
        DataInputStream in = p.in;
        int eid = p.i32();
        int type = in.readShort();
        String name = ent_names.get(type);
        boolean drop = false;
        if (name != null) ent_type.put(eid, name);
        if (!p.b8 && name != null && !ENTS_7.contains(name)) {
            Integer cow = name.equals("minecraft:deer") ? id_of("minecraft:cow") : null;
            if (cow != null) type = cow; else drop = true;
        }
        p.o.writeShort(type);
        p.i32(); p.i32(); p.i32(); p.i8(); p.i8();
        ent_data(p, name);
        p.s16(256); p.i8();
        if (drop) { dropped_ents.add(eid); p.drop(); }
    }

    Integer id_of(String name) {
        for (Map.Entry<Integer, String> e : ent_names.entrySet()) if (e.getValue().equals(name)) return e.getKey();
        return null;
    }

    boolean skip_ent(Pk p, int rest) throws IOException {
        int e = p.i32();
        if (!dropped_ents.contains(e)) return false;
        skip(p.in, rest);
        p.drop();
        return true;
    }
    boolean skip_ent_nbt(Pk p) throws IOException {
        int e = p.i32();
        if (!dropped_ents.contains(e)) return false;
        p.rd_nbt();
        p.drop();
        return true;
    }

    static final String[][] DATA_CLASH = { { "minecraft:wolf", "19" } };
    static boolean clash(String ent, int slot) {
        if (ent == null) return false;
        for (String[] c : DATA_CLASH) if (c[0].equals(ent) && Integer.parseInt(c[1]) == slot) return true;
        return false;
    }

    void ent_data(Pk p, String ent) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        while (true) {
            int id = in.readUnsignedByte();
            int at = p.buf.size();
            o.writeByte(id);
            if (id == 255) return;
            int type = p.u8();
            int flags = p.i8();
            if ((flags & 1) == 0) ent_value(p, type);
            if (clash(ent, id)) {

                byte[] all = p.buf.toByteArray();
                p.buf.reset();
                p.buf.write(all, 0, at);
            }
        }
    }

    void ent_value(Pk p, int type) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        switch (type) {
            case 0: p.i8(); break;
            case 1: p.i16(); break;
            case 2: p.i32(); break;
            case 3: p.f32(); break;
            case 4: p.s8(1024); break;
            case 5: {
                int iid = in.readShort(), cnt = in.readByte(), dmg = in.readShort();
                byte[] tag = p.rd_nbt();
                Object[] it = item(iid, dmg, tag, !p.b8);
                o.writeShort((Integer) it[0]); o.writeByte(cnt); o.writeShort((Integer) it[1]); p.wr_nbt((byte[]) it[2]);
                break;
            }
            case 6: p.i32(); p.i32(); p.i32(); break;
            case 7: p.uuid(); break;
            default: throw new IOException("bridge: entity data type " + type);
        }
    }

    void fling(Pk p) throws IOException { p.i32(); p.f32(); p.f32(); p.f32(); p.f32(); p.u8(); }

    void vehicle(Pk p) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        p.i32();
        double xd, yd = 0, zd;
        float rot, fall = 0;
        if (p.a8) { xd = in.readDouble(); yd = in.readDouble(); zd = in.readDouble(); rot = in.readFloat(); fall = in.readFloat(); }
        else { xd = in.readFloat(); zd = in.readFloat(); rot = in.readFloat(); }
        if (p.b8) { o.writeDouble(xd); o.writeDouble(yd); o.writeDouble(zd); o.writeFloat(rot); o.writeFloat(fall); }
        else { o.writeFloat((float) xd); o.writeFloat((float) zd); o.writeFloat(rot); }
    }

    void held(Pk p) throws IOException { p.i32(); if (p.u8() == 1) p.wr_nbt(tag_fix(p.rd_nbt(), !p.b8)); }

    byte[] tag_fix(byte[] gz, boolean to7) {
        if (gz == null) return null;
        Map<String, Object> m = Nbt.unzip(gz);
        if (m == null) return gz;
        boolean[] changed = { false };
        walk(m, to7, changed, 0);
        return changed[0] ? Nbt.zip(m) : gz;
    }

    @SuppressWarnings("unchecked")
    void walk(Object o, boolean to7, boolean[] changed, int depth) {
        if (depth > 32) return;
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            Object id = m.get("id");
            if (id instanceof Short && (m.containsKey("Count") || m.containsKey("Damage"))) {
                int iid = (Short) id, dmg = Nbt.num(m.get("Damage"), 0);
                Object[] it = item(iid, dmg, null, to7);
                if ((Integer) it[0] != iid || (Integer) it[1] != dmg) {
                    m.put("id", (short) (int) (Integer) it[0]);
                    m.put("Damage", (short) (int) (Integer) it[1]);
                    changed[0] = true;
                }
            }
            if (m.get("Block") instanceof Integer) {
                int b = (Integer) m.get("Block"), to = blk(b, to7);
                if (to != b) { m.put("Block", to); changed[0] = true; }
            }
            if (to7 && m.get("EntityId") instanceof String) {
                String e = (String) m.get("EntityId");
                if (!e.equals("none") && !ENTS_7.contains(e) && !ENTS_7.contains("minecraft:" + e.toLowerCase(java.util.Locale.ROOT))) {
                    m.put("EntityId", "none");
                    changed[0] = true;
                }
            }
            for (Object v : m.values()) walk(v, to7, changed, depth + 1);
        } else if (o instanceof List) {
            for (Object v : (List<Object>) o) walk(v, to7, changed, depth + 1);
        }
    }

    void particle(Pk p) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        String key = p.jutf();
        p.f64(); p.f64(); p.f64(); p.f64(); p.f64(); p.f64();
        int data = p.a8 ? in.readInt() : 0;
        if (p.b8) o.writeInt(data);
        p.f64();
        if (p.u8() != 0) { p.i8(); p.f32(); p.f32(); p.f32(); p.f32(); p.f32(); p.f32(); }
        if (!p.b8 && !PARTICLES_7.contains(key)) p.drop();
        else if (key.equals("block") || key.equals("item")) p.drop();
    }

    void map_data(Pk p) throws IOException {
        p.i16(); p.i16(); p.i8();
        int n = p.i8();
        for (int i = 0; i < n; i++) { p.i8(); p.i8(); p.i32(); p.i32(); p.i32(); p.n(9); }
        int len = p.u8();
        p.n(len);
    }

    void sync_ids(Pk p) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        int dest = in.readUnsignedByte();
        int n = in.readInt();
        Map<Integer, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) { int id = in.readUnsignedShort(); m.put(id, rd_utf8(in, 128)); }
        if (dest == 1) ent_names.putAll(m);
        if (dest == 1 && !p.b8) m.values().retainAll(ENTS_7_NS);
        o.writeByte(dest);
        o.writeInt(m.size());
        for (Map.Entry<Integer, String> e : m.entrySet()) { o.writeShort(e.getKey()); utf8(o, e.getValue()); }
    }

    public static final int WRAP = 247;

    public static final String BRIDGED = "OH|bridged";

    volatile boolean srv_oh;
    static ByteArrayOutputStream bridged_note() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream w = new DataOutputStream(b);
        w.writeByte(250); utf8(w, BRIDGED); w.writeInt(0);
        return b;
    }

    void wrapped(Pk p) throws IOException {
        p.u8();
        int len = p.i32();
        if (len < 0 || len > 1 << 24) throw new IOException("bridge: mod packet of " + len + " bytes");
        p.n(len);
    }

    void payload(Pk p) throws IOException {
        DataInputStream in = p.in;
        String ch = p.s8(128);
        int len = p.i32();
        if (p.c2s && HAVE.equals(ch) && len > 0 && len < 1 << 20) {
            byte[] b = new byte[len];
            in.readFully(b);
            have(b);
            p.drop();
            return;
        }
        if (len > 0 && len < 32768) p.n(len);
    }

    public static final String HAVE = "OH|have";
    final java.util.BitSet cli_has = new java.util.BitSet();

    void have(byte[] b) {
        try (DataInputStream d = new DataInputStream(new java.io.ByteArrayInputStream(b))) {
            int n = d.readInt();
            for (int i = 0; i < n && i < 65536; i++) cli_has.set(d.readUnsignedShort());
        } catch (IOException ignored) { }

        java.util.Arrays.fill(bmap7, 0);
        java.util.Arrays.fill(bmap8, 0);
        log.accept("bridge: the player has " + cli_has.cardinality() + " block / item ids of their own");
    }

    static final double EYES = 1.62F;
    void eye_y(Pk p) throws IOException {
        double y = p.in.readDouble();
        p.o.writeDouble(p.a8 == p.b8 ? y : p.a8 ? y + EYES : y - EYES);
    }

    public volatile boolean cli_pack;
    boolean cli(int id) { return cli_has.get(id) || cli_pack && host_has(id); }

    boolean host_has(int id) { return info != null && (info.bkind.containsKey(id) || info.ikind.containsKey(id)); }

    boolean to_player(boolean to7) { return to7 == (srv == P8); }

    void set_slot(Pk p) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        int w = p.i8();
        if (p.a8) state_id.put(w, in.readInt());
        if (p.b8) o.writeInt(next_state(w));
        int slot = p.i16();
        int before = p.buf.size();
        stack_nbt(p);
        if (!p.a8 && w == -1 && slot == -1) carried = java.util.Arrays.copyOfRange(p.buf.toByteArray(), before, p.buf.size());
    }

    void set_content(Pk p) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        int w = p.i8();
        byte[] carried_now = null;
        if (p.a8) {
            state_id.put(w, in.readInt());

            int before = p.buf.size();
            stack_nbt(p);
            byte[] all = p.buf.toByteArray();
            carried_now = java.util.Arrays.copyOfRange(all, before, all.length);
            p.buf.reset();
            p.buf.write(all, 0, before);
        }
        if (p.b8) {
            o.writeInt(next_state(w));
            byte[] c = carried;
            if (c != null) o.write(c); else o.writeShort(-1);
        }
        int n = p.i16();
        for (int i = 0; i < n; i++) stack_nbt(p);
        if (carried_now != null && !p.b8) {
            p.begin(103);
            o.writeByte(-1); o.writeShort(-1); o.write(carried_now);
        }
    }

    int next_state(int w) {
        synchronized (made_state) {
            int s = made_state.containsKey(w) ? made_state.get(w) + 1 : 1;
            made_state.put(w, s);
            return s;
        }
    }

    void click(Pk p) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        int w = p.i8();
        int act = p.i8();
        int argc = in.readByte();
        int[] args = new int[Math.max(0, argc)];
        for (int i = 0; i < args.length; i++) args[i] = p.a8 ? in.readInt() : in.read();

        if (!p.a8 && p.b8 && (act == 13 || act == 14) && args.length >= 2) {
            in.mark(16);
            in.readShort();
            int iid = in.readShort();
            int dmg = 0;
            if (iid >= 0) { in.readByte(); dmg = in.readShort(); }
            in.reset();
            if (iid >= 0) {
                Object[] it = item(iid, dmg, null, false);
                if (it != null) args = new int[] { args[0], args[1], (Integer) it[0], (Integer) it[1] };
            }
        }
        o.writeByte(args.length);
        for (int a : args) { if (p.b8) o.writeInt(a); else o.write(a); }
        if (p.a8) {

            in.readInt();
            int changed = in.readShort();
            boolean bad = false;
            Pk q = new Pk(in, true, false, true);
            for (int i = 0; i < changed; i++) { in.readShort(); if (!stack_nbt(q)) bad = true; }
            int iid = in.readShort();
            int cnt = 0, dmg = 0;
            byte[] tag = null;
            if (iid >= 0) { cnt = in.readByte(); dmg = in.readShort(); tag = q.rd_nbt(); }
            o.writeShort(action_cnt++);
            if (iid >= 0 && unknown_up(iid)) bad = true;
            Object[] it = iid >= 0 ? item(iid, dmg, tag, true) : null;
            if (it == null) o.writeShort(-1);
            else { o.writeShort((Integer) it[0]); o.writeByte(cnt); o.writeShort((Integer) it[1]); }
            if (bad) p.drop();
        } else {

            int ack = in.readShort();
            int iid = in.readShort();
            if (iid >= 0) { in.readByte(); in.readShort(); }
            Integer st = state_id.get(w);
            o.writeInt(st == null ? 0 : st);
            o.writeShort(0);
            o.writeShort(-1);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream w2 = new DataOutputStream(b);
            w2.writeByte(106); w2.writeByte(w); w2.writeShort(ack); w2.writeByte(1);
            inject_cli(b);
        }
    }

    static final int[] B87 = new int[16384], B87_KEEP = new int[16384];
    static final Map<Integer, Integer> I87 = new HashMap<>();
    static final java.util.BitSet HAS_B7 = bits(Tables.BLOCKS_7), HAS_I7 = bits(Tables.ITEMS_7), HAS_B8 = bits(Tables.BLOCKS_8), HAS_I8 = bits(Tables.ITEMS_8);
    static {
        java.util.Arrays.fill(B87, -1);
        for (int[] r : Tables.BLOCK_8_TO_7) { B87[r[0]] = r[1]; B87_KEEP[r[0]] = r[2]; }
        for (int[] r : Tables.ITEM_8_TO_7) I87.put(r[0], r[1]);
    }
    static java.util.BitSet bits(int[] ids) { java.util.BitSet b = new java.util.BitSet(); for (int i : ids) b.set(i); return b; }

    final int[] bmap7 = new int[16384], bmap8 = new int[16384];

    int bmap(int id, boolean to7) {
        int[] m = to7 ? bmap7 : bmap8;
        int v = m[id];
        if (v != 0) return v;
        boolean has = (to7 ? HAS_B7 : HAS_B8).get(id) || id == 0 || to_player(to7) && cli(id) || !to_player(to7) && host_has(id);
        int to = id, keep = 1;
        if (!has) {
            if (to7 && B87[id] >= 0) { to = B87[id]; keep = B87_KEEP[id]; }
            else { int[] r = stand_in(id); to = r[0]; keep = r[1]; }
        }
        v = to << 1 | keep;
        m[id] = v;
        if (!has) dbg("block " + id + " -> " + to + (keep != 0 ? " (keeps its data)" : ""));
        return v;
    }

    int[] stand_in(int id) {
        HostInfo h = info;
        String kind = h == null ? null : h.bkind.get(id);
        if (kind == null) return new int[] { 1, 0 };
        if (kind.equals("cube") || kind.equals("other")) {
            if (h.blight.getOrDefault(id, 0) > 0) kind = "lit";
            else if (kind.equals("other")) {
                float ht = h.bheight.getOrDefault(id, 1f);
                if (ht < 0.3f) return new int[] { kind_to("layer")[0], 0 };
                if (ht < 0.7f) return new int[] { kind_to("slab")[0], 0 };
            }
        }
        int[] k = kind_to(kind);
        if (k != null) return k;
        int rgb = h.brgb.getOrDefault(id, 0x808080);
        int[] best = Tables.PALETTE[0];
        long bd = Long.MAX_VALUE;
        for (int[] c : Tables.PALETTE) {
            long dr = (rgb >> 16 & 255) - (c[2] >> 16 & 255), dg = (rgb >> 8 & 255) - (c[2] >> 8 & 255), db = (rgb & 255) - (c[2] & 255);
            long d = 3 * dr * dr + 4 * dg * dg + 2 * db * db;
            if (d < bd) { bd = d; best = c; }
        }

        return new int[] { best[0] | best[1] << 20, 0 };
    }
    static int[] kind_to(String kind) {
        for (int i = 0; i < Tables.KINDS.length; i++) if (Tables.KINDS[i].equals(kind)) return Tables.KIND_TO[i];
        return null;
    }

    int[] blk_meta(int id, int meta, boolean to7) {
        id &= 0xffff;
        if (id >= 16384) return new int[] { 1, 0 };
        int v = bmap(id, to7);
        int to = v >>> 1;
        if ((to >>> 20) != 0) return new int[] { to & 0xfffff, to >>> 20 };
        return new int[] { to, (v & 1) != 0 ? meta : 0 };
    }
    int blk(int id, boolean to7) { return blk_meta(id, 0, to7)[0]; }

    static final int BUCKET7 = 16453, BUCKET7_WATER = 16454, BUCKET7_LAVA = 16455, BUCKET7_MILK = 16463, BUCKET7_CREAM = 16516;
    static final int BUCKET8_IRON = 16562, BUCKET8_STEEL = 16563;

    Object[] item(int id, int meta, byte[] tag, boolean to7) {
        if (id < 16384) {
            int[] r = blk_meta(id, meta, to7);
            return new Object[] { r[0], r[1], r[0] != id ? named(tag, id) : tag };
        }
        if (to7) {
            if (id == BUCKET8_IRON || id == BUCKET8_STEEL) {
                Map<String, Object> m = tag == null ? null : Nbt.unzip(tag);
                Object st = m == null ? null : m.get("State");
                String s = st instanceof String ? (String) st : "";
                int b = s.endsWith(":water") ? BUCKET7_WATER : s.endsWith(":lava") ? BUCKET7_LAVA : s.endsWith(":milk") ? BUCKET7_MILK
                        : s.endsWith(":icecream") ? BUCKET7_CREAM : BUCKET7;
                return new Object[] { b, 0, Nbt.EMPTY };
            }
            Integer m = I87.get(id);
            if (m != null) return new Object[] { m, 0, m != id ? named(tag, id) : tag };
            if (HAS_I7.get(id) || to_player(true) && cli(id) || !to_player(true) && host_has(id)) return new Object[] { id, meta, tag };
            return new Object[] { item_stand_in(id), 0, named(tag, id) };
        }

        String st = id == BUCKET7_WATER ? "minecraft:water" : id == BUCKET7_LAVA ? "minecraft:lava" : id == BUCKET7_MILK ? "minecraft:milk"
                : id == BUCKET7_CREAM ? "minecraft:icecream" : id == BUCKET7 ? "" : null;
        if (st != null) {
            Map<String, Object> m = tag == null ? null : Nbt.unzip(tag);
            if (m == null) m = new java.util.LinkedHashMap<>();
            if (!st.isEmpty()) m.put("State", st);
            return new Object[] { BUCKET8_IRON, 0, Nbt.zip(m) };
        }
        if (HAS_I8.get(id) || to_player(false) && cli(id) || !to_player(false) && host_has(id)) return new Object[] { id, meta, tag };
        return new Object[] { item_stand_in(id), 0, named(tag, id) };
    }

    byte[] named(byte[] tag, int id) {
        HostInfo h = info;
        String n = h == null ? null : h.names.get(id);
        if (n == null || n.isEmpty()) return tag;
        Map<String, Object> m = tag == null ? null : Nbt.unzip(tag);
        if (m == null) m = new java.util.LinkedHashMap<>();
        if (Boolean.TRUE.equals(m.get("overrideName")) || Byte.valueOf((byte) 1).equals(m.get("overrideName"))) return tag;
        m.put("overrideName", (byte) 1);
        m.put("name", n);
        return Nbt.zip(m);
    }

    int item_stand_in(int id) {
        HostInfo h = info;
        String k = h == null ? null : h.ikind.get(id);
        if (k == null) k = "item";
        for (int i = 0; i < Tables.ITEM_KINDS.length; i++) if (Tables.ITEM_KINDS[i].equals(k)) return Tables.ITEM_KIND_TO[i];
        return Tables.ITEM_KIND_TO[Tables.ITEM_KIND_TO.length - 1];
    }

    boolean stack_nbt(Pk p) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        int id = in.readShort();
        if (id < 0) { o.writeShort(-1); return true; }
        int cnt = in.readByte(), dmg = in.readShort();
        byte[] tag = p.rd_nbt();
        Object[] it = item(id, dmg, tag, !p.b8);
        o.writeShort((Integer) it[0]); o.writeByte(cnt); o.writeShort((Integer) it[1]); p.wr_nbt((byte[]) it[2]);
        return !(p.c2s && unknown_up(id));
    }
    boolean stack_plain(Pk p) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        int id = in.readShort();
        if (id < 0) { o.writeShort(-1); return true; }
        int cnt = in.readByte(), dmg = in.readShort();
        Object[] it = item(id, dmg, null, !p.b8);
        o.writeShort((Integer) it[0]); o.writeByte(cnt); o.writeShort((Integer) it[1]);
        return !(p.c2s && unknown_up(id));
    }

    void item_split(Pk p, boolean nbt) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        int id = in.readShort(), meta = in.readShort();
        byte[] tag = nbt ? p.rd_nbt() : null;
        if (id < 0) { o.writeShort(id); o.writeShort(meta); if (nbt) p.wr_nbt(tag); return; }
        Object[] it = item(id, meta, tag, !p.b8);
        o.writeShort((Integer) it[0]); o.writeShort((Integer) it[1]);
        if (nbt) p.wr_nbt((byte[]) it[2]);
    }

    boolean unknown_up(int id) {
        if (!known(srv)) return false;

        if (info != null && (info.bkind.containsKey(id) || info.ikind.containsKey(id))) return false;
        boolean to7 = srv == P7;
        if (id < 16384) return id != 0 && !(to7 ? HAS_B7 : HAS_B8).get(id);
        if (!to7) return false;
        return !HAS_I7.get(id) && id != BUCKET8_IRON;
    }

    void region(Pk p) throws IOException {
        DataInputStream in = p.in;
        DataOutputStream o = p.o;
        int x = p.i32(), y = p.i16(), z = p.i32();
        int xs = p.u8() + 1, ys = p.u8() + 1, zs = p.u8() + 1;
        int len = in.readInt();
        byte[] def = new byte[len];
        in.readFully(def);
        byte[] src = new byte[xs * ys * zs * 8];
        Inflater inf = new Inflater();
        try { inf.setInput(def); inf.inflate(src); }
        catch (DataFormatException e) { throw new IOException("bridge: bad chunk data"); }
        finally { inf.end(); }
        byte[] dst = new byte[xs * ys * zs * 8 + 8192];
        int[] at = { 0, 0 };
        int minCX = Math.floorDiv(x, 16), minCZ = Math.floorDiv(z, 16), maxCX = Math.floorDiv(x + xs - 1, 16), maxCZ = Math.floorDiv(z + zs - 1, 16);
        int minY = Math.max(0, y), maxY = Math.min(256, y + ys);
        for (int cx = minCX; cx <= maxCX; cx++) {
            int minX = Math.max(0, x - cx * 16), maxX = Math.min(16, x + xs - cx * 16);
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                int minZ = Math.max(0, z - cz * 16), maxZ = Math.min(16, z + zs - cz * 16);
                int minS = minY / 16, maxS = (int) Math.ceil(maxY / 16.0);
                for (int s = minS; s <= maxS; s++) {
                    if (s < 0 || s >= 16) continue;
                    int y0 = Math.max(0, minY - s * 16), y1 = Math.min(16, maxY - s * 16);
                    dst = section(src, dst, at, minX, y0, minZ, maxX, y1, maxZ, p.a8, p.b8);
                }
                int cols = (maxX - minX) * (maxZ - minZ) * 3;
                dst = room(dst, at[1] + cols);
                System.arraycopy(src, at[0], dst, at[1], cols);
                at[0] += cols; at[1] += cols;
            }
        }
        Deflater d = new Deflater(-1);
        byte[] out = new byte[Math.max(xs * ys * zs * 8, at[1] + 1024)];
        int n;
        try {

            d.setInput(dst, 0, Math.min(at[1], xs * ys * zs * 8));
            d.finish();
            n = d.deflate(out);
        } finally { d.end(); }
        o.writeInt(n);
        o.write(out, 0, n);
    }

    static byte[] room(byte[] b, int need) { return need <= b.length ? b : java.util.Arrays.copyOf(b, Math.max(need, b.length * 2)); }

    byte[] section(byte[] src, byte[] dst, int[] at, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean a8, boolean b8) {

        int xs = maxX - minX, ys = Math.max(0, maxY - minY), zs = maxZ - minZ;
        int n = xs * ys * zs;
        boolean full = n == 4096;
        int rows = ys * zs;
        int brows = full ? 2 * 16 : Math.max(0, (maxY >> 3) - (minY >> 3)) * zs;
        int bl = full ? 512 : brows * xs;
        dst = room(dst, at[1] + n * 4 + 1024);
        int si = at[0], di = at[1];

        int f = src[si++]; dst[di++] = (byte) f;
        if (f != 0) {
            for (int i = 0; i < n; i++) {
                int id = blk((src[si] & 255) << 8 | src[si + 1] & 255, !b8);
                dst[di] = (byte) (id >> 8); dst[di + 1] = (byte) id;
                si += 2; di += 2;
            }
        }

        int df = src[si++]; dst[di++] = (byte) df;
        int data_at = di;
        if (df != 0) { System.arraycopy(src, si, dst, di, n); si += n; di += n; }
        if (f != 0) {

            if (df != 0) for (int i = 0; i < n; i++) {
                int orig = id_at(src, at[0] + 1 + i * 2) & 0x3fff;
                if (orig == 0) continue;
                int v = bmap(orig, true);
                if ((v & 1) == 0) dst[data_at + i] = (byte) ((v >>> 1) >>> 20);
            }
        }

        int nib = full ? 2048 : rows * (xs / 2);
        if (a8 == b8) throw new IllegalStateException();
        if (a8) {

            int lf = src[si++];
            if (lf == 0) { dst[di++] = 0; dst[di++] = 0; }
            else {
                byte[] sky = new byte[nib], blk = new byte[nib];
                nibbles(src, si, sky, blk, minX, xs, rows, full);
                si += n;
                dst[di++] = 1; System.arraycopy(sky, 0, dst, di, nib); di += nib;
                dst[di++] = 1; System.arraycopy(blk, 0, dst, di, nib); di += nib;
            }

            si++;
            dst[di++] = 1;
            byte[] bm = srv == P8 ? bio_down : bio_up;
            for (int i = 0; i < bl; i++) dst[di + i] = bm[src[si + i] & 255];
            si += bl; di += bl;
        } else {
            int sf = src[si++];
            int sky_at = si; if (sf != 0) si += nib;
            int bf = src[si++];
            int blk_at = si; if (bf != 0) si += nib;
            if (sf == 0 && bf == 0) dst[di++] = 0;
            else {
                dst[di++] = 1;
                bytes_of(src, sf != 0 ? sky_at : -1, bf != 0 ? blk_at : -1, dst, di, minX, xs, rows, full);
                di += n;
            }
            int bfl = src[si++];
            dst[di++] = 1;
            byte[] bm = srv == P8 ? bio_up : bio_down;
            if (bfl != 0) { for (int i = 0; i < bl; i++) dst[di + i] = bm[src[si + i] & 255]; si += bl; }
            else java.util.Arrays.fill(dst, di, di + bl, srv == P8 ? bio_none : bm[index(BIOMES_7, "overworld.plains")]);
            di += bl;
        }
        at[0] = si; at[1] = di;
        return dst;
    }

    static int id_at(byte[] b, int i) { return (b[i] & 255) << 8 | b[i + 1] & 255; }

    static void nibbles(byte[] src, int si, byte[] sky, byte[] blk, int minX, int xs, int rows, boolean full) {
        if (full) {
            for (int i = 0; i < 4096; i += 2) {
                int a = src[si + i] & 255, b = src[si + i + 1] & 255;
                sky[i >> 1] = (byte) (a >> 4 | (b >> 4) << 4);
                blk[i >> 1] = (byte) (a & 15 | (b & 15) << 4);
            }
            return;
        }
        int x0 = minX & ~1, per = xs / 2;
        for (int r = 0; r < rows; r++) {
            for (int j = 0; j < per; j++) {
                int lo = x0 + 2 * j, hi = lo + 1;
                int a = lo >= minX && lo - minX < xs ? src[si + r * xs + lo - minX] & 255 : 0;
                int b = hi >= minX && hi - minX < xs ? src[si + r * xs + hi - minX] & 255 : 0;
                sky[r * per + j] = (byte) (a >> 4 | (b >> 4) << 4);
                blk[r * per + j] = (byte) (a & 15 | (b & 15) << 4);
            }
        }
    }

    static void bytes_of(byte[] src, int sky, int blk, byte[] dst, int di, int minX, int xs, int rows, boolean full) {
        if (full) {
            for (int i = 0; i < 4096; i++) {
                int s = sky < 0 ? 0 : (src[sky + (i >> 1)] >> ((i & 1) * 4)) & 15;
                int b = blk < 0 ? 0 : (src[blk + (i >> 1)] >> ((i & 1) * 4)) & 15;
                dst[di + i] = (byte) (s << 4 | b);
            }
            return;
        }
        int x0 = minX & ~1, per = xs / 2;
        for (int r = 0; r < rows; r++) {
            for (int k = 0; k < xs; k++) {
                int x = minX + k, pos = x - x0;
                int s = 0, b = 0;
                if (pos / 2 < per) {
                    if (sky >= 0) s = (src[sky + r * per + pos / 2] >> ((pos & 1) * 4)) & 15;
                    if (blk >= 0) b = (src[blk + r * per + pos / 2] >> ((pos & 1) * 4)) & 15;
                }
                dst[di + r * xs + k] = (byte) (s << 4 | b);
            }
        }
    }

    static final Set<String> ENTS_7 = new HashSet<>(), ENTS_7_NS = ENTS_7;
    static final Set<String> PARTICLES_7 = new HashSet<>(java.util.Arrays.asList(
            "arrowtrail", "block", "blueflame", "bubble", "dripLava", "dripWater", "explode", "fallingleaf", "fireflyBlue",
            "fireflyGreen", "fireflyOrange", "fireflyRed", "flame", "footstep", "heart", "item", "largesmoke", "lava", "note",
            "portal", "puffrgb", "reddust", "slimechunk", "smoke", "snowshovel", "soulflame", "splash"));
    static {
        for (String e : ("item painting lightning falling_block minecart boat primed_tnt pebble egg arrow arrow_golden "
                + "arrow_purple snowball fireball cannonball human zombie zombie_armored zombie_pigman creeper skeleton spider "
                + "giant slime ghast snowman scorpion pig sheep cow chicken squid wolf firefly_cluster").split(" "))
            ENTS_7.add("minecraft:" + e);
    }

    static String rd_utf8(DataInputStream in, int max) throws IOException {
        int n = in.readShort();
        if (n < 0 || n > max) throw new IOException("bridge: string length " + n);
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
    static void utf8(DataOutputStream o, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.writeShort(b.length);
        o.write(b);
    }
    static String rd_utf16(DataInputStream in, int max) throws IOException {
        int n = in.readShort();
        if (n < 0 || n > max) throw new IOException("bridge: string length " + n);
        byte[] b = new byte[n * 2];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_16BE);
    }
    static void utf16(DataOutputStream o, String s) throws IOException {
        o.writeShort(s.length());
        o.write(s.getBytes(StandardCharsets.UTF_16BE));
    }

    static String cut_utf8(String s, int max) {
        if (s.getBytes(StandardCharsets.UTF_8).length <= max) return s;
        int n = s.length();
        while (n > 0 && s.substring(0, n).getBytes(StandardCharsets.UTF_8).length > max) n--;
        return s.substring(0, n);
    }
    static void skip(DataInputStream in, int n) throws IOException {
        while (n > 0) {
            int k = in.skipBytes(n);
            if (k <= 0) { if (in.read() < 0) throw new EOFException(); k = 1; }
            n -= k;
        }
    }
}
