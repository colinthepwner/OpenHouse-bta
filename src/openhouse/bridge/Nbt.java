package openhouse.bridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class Nbt {
    private Nbt() { }

    static final class Lst extends ArrayList<Object> { int type; }

    public static Map<String, Object> unzip(byte[] gz) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(gz)))) {
            if (in.readByte() != 10) return null;
            in.readUTF();
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) read(in, 10, 0);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] zip(Map<String, Object> root) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(new GZIPOutputStream(b));
            out.writeByte(10);
            out.writeUTF("");
            write(out, 10, root);
            out.close();
            return b.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static final byte[] EMPTY = zip(new LinkedHashMap<String, Object>());

    static Object read(DataInputStream in, int t, int depth) throws IOException {
        if (depth > 64) throw new IOException("nbt too deep");
        switch (t) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: {
                Lst l = new Lst();
                l.type = in.readByte();
                int n = in.readInt();
                for (int i = 0; i < n; i++) l.add(read(in, l.type, depth + 1));
                return l;
            }
            case 10: {
                Map<String, Object> m = new LinkedHashMap<>();
                while (true) {
                    int tt = in.readByte();
                    if (tt == 0) return m;
                    String k = in.readUTF();
                    m.put(k, read(in, tt, depth + 1));
                }
            }
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("nbt tag " + t);
        }
    }

    static int type_of(Object v) {
        if (v instanceof Byte) return 1;
        if (v instanceof Short) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof Float) return 5;
        if (v instanceof Double) return 6;
        if (v instanceof byte[]) return 7;
        if (v instanceof String) return 8;
        if (v instanceof Lst) return 9;
        if (v instanceof Map) return 10;
        if (v instanceof int[]) return 11;
        if (v instanceof long[]) return 12;
        throw new IllegalArgumentException("not nbt: " + v);
    }

    @SuppressWarnings("unchecked")
    static void write(DataOutputStream out, int t, Object v) throws IOException {
        switch (t) {
            case 1: out.writeByte((Byte) v); break;
            case 2: out.writeShort((Short) v); break;
            case 3: out.writeInt((Integer) v); break;
            case 4: out.writeLong((Long) v); break;
            case 5: out.writeFloat((Float) v); break;
            case 6: out.writeDouble((Double) v); break;
            case 7: { byte[] b = (byte[]) v; out.writeInt(b.length); out.write(b); break; }
            case 8: out.writeUTF((String) v); break;
            case 9: {
                Lst l = (Lst) v;
                out.writeByte(l.type);
                out.writeInt(l.size());
                for (Object o : l) write(out, l.type, o);
                break;
            }
            case 10:
                for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                    int tt = type_of(e.getValue());
                    out.writeByte(tt);
                    out.writeUTF(e.getKey());
                    write(out, tt, e.getValue());
                }
                out.writeByte(0);
                break;
            case 11: { int[] a = (int[]) v; out.writeInt(a.length); for (int x : a) out.writeInt(x); break; }
            case 12: { long[] a = (long[]) v; out.writeInt(a.length); for (long x : a) out.writeLong(x); break; }
            default: throw new IOException("nbt tag " + t);
        }
    }

    static int num(Object v, int dflt) { return v instanceof Number ? ((Number) v).intValue() : dflt; }

    static List<Object> list() { return new Lst(); }
}
