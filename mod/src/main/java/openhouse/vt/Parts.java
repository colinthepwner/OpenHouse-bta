package openhouse.vt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.zip.Inflater;

public final class Parts implements Live.Src {
    static final class Ent { String name; int method; long csize, size, off; }

    final String url;
    final Map<String, Ent> ents = new LinkedHashMap<>();
    final Map<String, String> pins = new HashMap<>(), pkg_pins = new HashMap<>();
    final Consumer<String> status;

    private Parts(String url, Consumer<String> status) { this.url = url; this.status = status; }

    public static Parts open(String url, String jar, Consumer<String> status) throws IOException {
        byte[] t;
        try (InputStream in = Parts.class.getResourceAsStream("/openhouse/v7/g8parts.txt")) {
            if (in == null) return null;
            t = in.readAllBytes();
        }
        Parts p = new Parts(url, status);
        String[] h = null;
        for (String l : new String(t, StandardCharsets.UTF_8).split("\n")) {
            String[] w = l.trim().split(" ", 4);
            if (w.length < 4 || !w[1].equals(jar)) continue;
            if (w[0].equals("J")) h = l.trim().split(" ");
            else if (w[0].equals("F")) p.pins.put(w[3], w[2]);
            else if (w[0].equals("P")) p.pkg_pins.put(w[3], w[2]);
        }
        if (h == null) return null;
        long cd_off = Long.parseLong(h[2]), len = Long.parseLong(h[5]);
        int cd_len = Integer.parseInt(h[3]);
        if (status != null) status.accept("BTA 8.0.1's files (contents)");
        byte[] cd = p.range(cd_off, cd_off + cd_len - 1, len);
        if (!hex(sha256(cd)).equals(h[4])) throw new IOException("BTA 8.0.1's jar isn't the one we know (directory)");
        p.read_cd(cd);
        return p;
    }

    static String pkg(String n) { return n.indexOf('/') > 0 ? n.substring(0, n.lastIndexOf('/')) : ""; }

    public static String pkg_hash(java.util.SortedMap<String, byte[]> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> e : files.entrySet()) { md.update(e.getKey().getBytes(StandardCharsets.UTF_8)); md.update((byte) 0); md.update(e.getValue()); }
            return hex(md.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) { throw new RuntimeException(e); }
    }

    void read_cd(byte[] b) throws IOException {
        int o = 0;
        while (o + 46 <= b.length && i32(b, o) == 0x02014b50) {
            Ent e = new Ent();
            e.method = u16(b, o + 10);
            e.csize = i32(b, o + 20) & 0xFFFFFFFFL;
            e.size = i32(b, o + 24) & 0xFFFFFFFFL;
            int n = u16(b, o + 28), x = u16(b, o + 30), c = u16(b, o + 32);
            e.off = i32(b, o + 42) & 0xFFFFFFFFL;
            e.name = new String(b, o + 46, n, StandardCharsets.UTF_8);
            ents.put(e.name, e);
            o += 46 + n + x + c;
        }
        if (ents.isEmpty()) throw new IOException("no files in BTA 8.0.1's client jar directory");
    }

    @Override public Collection<String> names() { return ents.keySet(); }

    @Override public Map<String, byte[]> read(Collection<String> want) throws IOException {
        List<Ent> l = new ArrayList<>();
        for (String n : want) {
            Ent e = ents.get(n);
            if (e == null || !pins.containsKey(n) && !pkg_pins.containsKey(pkg(n))) throw new IOException(n + ": not one we can check");
            l.add(e);
        }
        l.sort((a, b) -> Long.compare(a.off, b.off));

        List<List<Ent>> runs = new ArrayList<>();
        List<Ent> cur = null;
        long end = -1, start = 0;
        for (Ent e : l) {
            long e_end = e.off + 30 + e.name.length() * 3L + 1024 + e.csize;
            if (cur == null || e.off - end > 256 * 1024 || e_end - start > 8L << 20) { cur = new ArrayList<>(); runs.add(cur); start = e.off; }
            cur.add(e);
            end = Math.max(end, e_end);
        }
        Map<String, byte[]> out = new java.util.concurrent.ConcurrentHashMap<>();
        ExecutorService ex = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> fs = new ArrayList<>();
            int[] done = { 0 };
            for (List<Ent> r : runs) fs.add(ex.submit(() -> {
                Ent last = r.get(r.size() - 1);
                long a = r.get(0).off, b = last.off + 30 + last.name.length() * 3L + 1024 + last.csize;
                byte[] d = range(a, b, -1);
                for (Ent e : r) out.put(e.name, entry(d, (int) (e.off - a), e));
                synchronized (done) { done[0] += r.size(); if (status != null) status.accept("BTA 8.0.1's files " + (100 * done[0] / l.size()) + "%"); }
                return null;
            }));
            for (Future<?> f : fs) {
                try { f.get(); } catch (java.util.concurrent.ExecutionException e) { throw e.getCause() instanceof IOException ? (IOException) e.getCause() : new IOException(e.getCause()); }
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        } finally {
            ex.shutdownNow();
        }

        Map<String, java.util.TreeMap<String, byte[]>> pk = new HashMap<>();
        for (Ent e : l) if (!pins.containsKey(e.name)) pk.computeIfAbsent(pkg(e.name), k -> new java.util.TreeMap<>()).put(e.name, out.get(e.name));
        for (Map.Entry<String, java.util.TreeMap<String, byte[]>> e : pk.entrySet()) {
            for (Ent x : ents.values()) if (!x.name.endsWith("/") && pkg(x.name).equals(e.getKey()) && !e.getValue().containsKey(x.name)) throw new IOException(e.getKey() + " asked for in part");
            if (!pkg_hash(e.getValue()).equals(pkg_pins.get(e.getKey()))) throw new IOException(e.getKey() + " isn't the one we know");
        }
        return out;
    }

    byte[] entry(byte[] d, int o, Ent e) throws IOException {
        if (o + 30 > d.length || i32(d, o) != 0x04034b50) throw new IOException(e.name + ": no local header");
        int st = o + 30 + u16(d, o + 26) + u16(d, o + 28);
        if (st + e.csize > d.length) throw new IOException(e.name + ": cut short");
        byte[] r;
        if (e.method == 0) { r = new byte[(int) e.size]; System.arraycopy(d, st, r, 0, r.length); }
        else if (e.method == 8) {
            Inflater inf = new Inflater(true);
            try {
                inf.setInput(d, st, (int) e.csize);
                r = new byte[(int) e.size];
                int n = 0;
                while (n < r.length && !inf.finished()) {
                    int k = inf.inflate(r, n, r.length - n);
                    if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                    n += k;
                }
                if (n != r.length) throw new IOException(e.name + ": short");
            } catch (java.util.zip.DataFormatException x) {
                throw new IOException(e.name + ": " + x.getMessage());
            } finally {
                inf.end();
            }
        } else throw new IOException(e.name + ": packed oddly (" + e.method + ")");
        String pin = pins.get(e.name);
        if (pin != null && !hex(sha256(r)).startsWith(pin)) throw new IOException(e.name + " isn't the one we know");
        return r;
    }

    byte[] range(long a, long b, long len) throws IOException {
        IOException last = null;
        for (int tries = 0; tries < 3; tries++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Range", "bytes=" + a + "-" + b);
                c.setRequestProperty("User-Agent", "OpenHouse");
                if (c.getResponseCode() != 206) throw new IOException("no ranged reads here (" + c.getResponseCode() + ")");
                String cr = c.getHeaderField("Content-Range");
                if (len >= 0 && (cr == null || !cr.endsWith("/" + len))) throw new IOException("BTA 8.0.1's client jar changed size (" + cr + ")");
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream((int) Math.min(b - a + 1, 16 << 20));
                    in.transferTo(o);
                    return o.toByteArray();
                }
            } catch (IOException e) {
                last = e;
                if (e.getMessage() != null && (e.getMessage().startsWith("no ranged") || e.getMessage().contains("changed size"))) throw e;
            } finally {
                c.disconnect();
            }
        }
        throw last;
    }

    static int u16(byte[] b, int o) { return b[o] & 0xFF | (b[o + 1] & 0xFF) << 8; }
    static int i32(byte[] b, int o) { return b[o] & 0xFF | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | (b[o + 3] & 0xFF) << 24; }

    static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
