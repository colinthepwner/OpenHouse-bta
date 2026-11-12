package openhouse.mod;

import com.mojang.nbt.NbtIo;
import com.mojang.nbt.tags.CompoundTag;
import com.mojang.nbt.tags.ListTag;
import com.mojang.nbt.tags.Tag;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.world.save.mcregion.RegionFile;

public final class WorldRepair {

    static double BAD = 0.30;

    static double GOOD = 0.20;

    static double MARGIN = 0.10;

    static double TIE = 0.04;

    static boolean DEBUG = Boolean.getBoolean("openhouse.repairDebug");

    public interface Progress { void at(String stage, int percent); }

    public static final class Report {
        public int chunks, damaged, repaired, restored, unfixable;
        public final List<String> lines = new ArrayList<>();
        public boolean changed() { return repaired + restored > 0; }
    }

    static final class Cand {
        final File file; final int sector; final int x, z; final long ticks; final String from;
        long[] masks;
        short[] tops;
        Cand(File file, int sector, int x, int z, long ticks, String from) {
            this.file = file; this.sector = sector; this.x = x; this.z = z; this.ticks = ticks; this.from = from;
        }
    }

    static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    static Cand at(Cand c, long k) {
        int x = (int) (k >> 32), z = (int) k;
        Cand m = new Cand(c.file, c.sector, x, z, c.ticks, x == c.x && z == c.z ? c.from : "moved from " + c.x + "," + c.z);
        m.masks = c.masks;
        m.tops = c.tops;
        return m;
    }

    static byte[] readAll(File f) throws IOException { return Files.readAllBytes(f.toPath()); }

    static int intAt(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    static CompoundTag blob(byte[] f, int sector, boolean quick) {
        long off = (long) sector * 4096L;
        if (off + 6 > f.length) return null;
        int o = (int) off;
        int len = intAt(f, o);
        if (len < 2 || o + 4L + len > f.length) return null;
        byte type = f[o + 4];
        try {
            java.io.InputStream in = new ByteArrayInputStream(f, o + 5, len - 1);
            if (type == 2) {
                if (quick && (f[o + 5] & 0xff) != 0x78) return null;
                in = new InflaterInputStream(in);
            } else if (type == 1) {
                if (quick && ((f[o + 5] & 0xff) != 0x1f || (f[o + 6] & 0xff) != 0x8b)) return null;
                in = new GZIPInputStream(in);
            } else if (type != 3 || quick) {
                return null;
            }
            CompoundTag root = NbtIo.read(new DataInputStream(in));
            return root != null && root.containsKey("Level") ? root : null;
        } catch (Throwable t) {

            return null;
        }
    }

    static Cand cand(CompoundTag root, File file, int sector, String from) {
        CompoundTag lv = root.getCompound("Level");
        Cand c = new Cand(file, sector, lv.getInteger("xPos"), lv.getInteger("zPos"),
                lv.containsKey("TicksOnUnload") ? lv.getLong("TicksOnUnload") : lv.getLong("LastUpdate"), from);
        seams(c, lv);
        return c;
    }

    static void seams(Cand c, CompoundTag lv) {
        ListTag secs = lv.getListOrDefault("Sections", null);
        if (secs == null) return;
        long[] m = new long[4 * 16 * 4];
        boolean any = false;
        for (Tag<?> t : secs) {
            if (!(t instanceof CompoundTag)) continue;
            CompoundTag s = (CompoundTag) t;
            short[] blocks = s.getShortArrayOrDefault("Blocks", null);
            int sy = s.getInteger("yPos");
            if (blocks == null || blocks.length < 4096 || sy < 0 || sy > 15) continue;
            any = true;
            for (int e = 0; e < 4; e++) for (int i = 0; i < 16; i++) {
                int x = e == 0 || e == 1 ? i : (e == 2 ? 0 : 15);
                int z = e == 2 || e == 3 ? i : (e == 0 ? 0 : 15);
                int col = (e * 16 + i) * 4;
                for (int ly = 0; ly < 16; ly++) {
                    if ((blocks[ly * 256 + z * 16 + x] & 16383) == 0) continue;
                    int y = sy * 16 + ly;
                    m[col + (y >> 6)] |= 1L << (y & 63);
                }
            }
        }
        if (!any) return;
        short[] tops = new short[64];
        for (int k = 0; k < 64; k++) {
            int top = -1;
            for (int w = 3; w >= 0 && top < 0; w--) if (m[k * 4 + w] != 0) top = w * 64 + 63 - Long.numberOfLeadingZeros(m[k * 4 + w]);
            tops[k] = (short) top;
        }
        c.masks = m;
        c.tops = tops;
    }

    static final int[] DX = { 0, 0, -1, 1 }, DZ = { -1, 1, 0, 0 }, OPP = { 1, 0, 3, 2 };

    static double edge(Cand a, int e, Cand b) {
        double[] m = new double[16];
        int eb = OPP[e];
        for (int i = 0; i < 16; i++) {
            int ka = e * 16 + i, kb = eb * 16 + i;
            int ta = a.tops[ka], tb = b.tops[kb];
            int lo = Math.max(0, Math.min(ta, tb) - 8), hi = Math.min(255, Math.max(ta, tb) + 8);
            if (hi < lo) { m[i] = 0; continue; }
            int bad = 0;
            for (int y = lo; y <= hi; y++) {
                boolean sa = (a.masks[ka * 4 + (y >> 6)] >>> (y & 63) & 1) != 0;
                boolean sb = (b.masks[kb * 4 + (y >> 6)] >>> (y & 63) & 1) != 0;
                if (sa != sb) bad++;
            }
            m[i] = bad / (double) (hi - lo + 1);
        }
        Arrays.sort(m);
        return (m[7] + m[8]) / 2;
    }

    static double score(Cand c, Map<Long, Cand> world, Set<Long> skip, int[] n) {
        n[0] = 0;
        if (c == null || c.masks == null) return Double.NaN;
        for (int pass = 0; pass < 2; pass++) {
            double sum = 0;
            int cnt = 0;
            for (int e = 0; e < 4; e++) {
                long k = key(c.x + DX[e], c.z + DZ[e]);
                Cand nb = world.get(k);
                if (nb == null || nb.masks == null) continue;
                if (pass == 0 && skip != null && skip.contains(k)) continue;
                sum += edge(c, e, nb);
                cnt++;
            }
            if (cnt > 0) { n[0] = cnt; return sum / cnt; }
            if (skip == null || skip.isEmpty()) break;
        }
        return Double.NaN;
    }

    public static void repair(File regionDir, List<File> backups, Progress progress, Report report) throws IOException {
        repair(regionDir, backups, progress, report, null);
    }

    public static void repair(File regionDir, List<File> backups, Progress progress, Report report, Runnable beforeWrite) throws IOException {
        File[] files = regionDir.listFiles((d, n) -> n.matches("r\\.-?\\d+\\.-?\\d+\\.mcr"));
        if (files == null) files = new File[0];
        Arrays.sort(files);
        Map<Long, Cand> world = new HashMap<>();
        Map<Long, String> problem = new HashMap<>();
        Map<Long, List<Cand>> extra = new HashMap<>();
        Map<File, int[]> headers = new HashMap<>();
        Set<File> overlapping = new HashSet<>();

        int fi = 0;
        for (File f : files) {
            if (progress != null) progress.at("Checking " + regionDir.getParentFile().getName() + "/" + regionDir.getName(), fi++ * 60 / Math.max(1, files.length));
            int[] rc = regionCoords(f);
            byte[] b = readAll(f);
            int[] head = new int[1024];
            if (b.length >= 4096) for (int i = 0; i < 1024; i++) head[i] = intAt(b, i * 4);
            headers.put(f, head);
            int sectors = b.length / 4096;
            int[] owner = new int[Math.max(sectors, 1)];
            Arrays.fill(owner, -1);
            for (int i = 0; i < 1024; i++) {
                int loc = head[i];
                if (loc == 0) continue;
                int x = rc[0] * 32 + (i & 31), z = rc[1] * 32 + (i >> 5);
                long k = key(x, z);
                int off = loc >>> 8, cnt = loc & 0xff;
                report.chunks++;
                if (off < 2 || off + cnt > sectors) { problem.put(k, "points past the end of its file"); continue; }
                for (int s = off; s < off + cnt; s++) {
                    if (owner[s] >= 0 && owner[s] != i) overlapping.add(f);
                    owner[s] = i;
                }
                CompoundTag root = blob(b, off, false);
                if (root == null) { problem.put(k, "can't be read"); continue; }
                Cand c = cand(root, f, off, "world");
                if (c.x != x || c.z != z) {

                    problem.put(k, "holds chunk " + c.x + "," + c.z);
                    Cand mis = new Cand(f, off, c.x, c.z, c.ticks, "misplaced");
                    mis.masks = c.masks;
                    mis.tops = c.tops;
                    extra.computeIfAbsent(key(c.x, c.z), q -> new ArrayList<>()).add(mis);
                    continue;
                }
                world.put(k, c);
            }
        }

        File newest = backups.isEmpty() ? null : backups.get(0);
        if (newest != null && newest.isDirectory()) {
            File[] bf = newest.listFiles((d, n) -> n.matches("r\\.-?\\d+\\.-?\\d+\\.mcr"));
            if (bf != null) for (File f : bf) {
                int[] rc = regionCoords(f);
                byte[] hb = headerOf(f);
                if (hb == null) continue;
                for (int i = 0; i < 1024; i++) {
                    if (intAt(hb, i * 4) == 0) continue;
                    long k = key(rc[0] * 32 + (i & 31), rc[1] * 32 + (i >> 5));
                    if (!world.containsKey(k) && !problem.containsKey(k)) problem.put(k, "is missing (the last backup has it)");
                }
            }
        }

        int[] n = new int[1];
        for (Map.Entry<Long, Cand> e : world.entrySet()) {
            double s = score(e.getValue(), world, null, n);
            if (n[0] >= 2 && s > BAD) problem.put(e.getKey(), String.format("doesn't fit its neighbours (seams %.2f)", s));
        }
        if (problem.isEmpty()) return;
        report.damaged += problem.size();

        if (progress != null) progress.at("Looking for good copies of " + problem.size() + " damaged chunk" + (problem.size() == 1 ? "" : "s"), 65);
        Set<File> scan = new HashSet<>();
        for (long k : problem.keySet()) scan.add(new File(regionDir, regionName(k)));
        for (File f : scan) {
            if (!f.isFile()) continue;
            byte[] b = readAll(f);
            int[] head = headers.get(f);
            Set<Integer> starts = new HashSet<>();
            if (head != null) for (int loc : head) if (loc != 0) starts.add(loc >>> 8);
            for (int s = 2; s < b.length / 4096; s++) {
                if (starts.contains(s)) continue;
                CompoundTag root = blob(b, s, true);
                if (root == null) continue;
                Cand c = cand(root, f, s, "orphan");
                long k = key(c.x, c.z);
                if (problem.containsKey(k)) extra.computeIfAbsent(k, q -> new ArrayList<>()).add(c);
            }
        }
        for (File bdir : backups) {
            Map<String, byte[]> cache = new HashMap<>();
            for (long k : problem.keySet()) {
                File f = new File(bdir, regionName(k));
                if (!f.isFile()) continue;
                byte[] b = cache.computeIfAbsent(f.getName(), q -> { try { return readAll(f); } catch (IOException ex) { return new byte[0]; } });
                if (b.length < 4096) continue;
                int x = (int) (k >> 32), z = (int) k;
                int loc = intAt(b, ((x & 31) + (z & 31) * 32) * 4);
                if (loc == 0) continue;
                CompoundTag root = blob(b, loc >>> 8, false);
                if (root == null) continue;
                Cand c = cand(root, f, loc >>> 8, "backup " + bdir.getParentFile().getName());
                if (c.x == x && c.z == z) extra.computeIfAbsent(k, q -> new ArrayList<>()).add(c);
            }
        }

        Map<String, Cand> reloc = new HashMap<>();
        for (Map.Entry<Long, String> e : problem.entrySet()) {
            Cand cur = world.get(e.getKey());
            if (cur != null) reloc.put(cur.file.getName() + "@" + cur.sector, cur);
        }
        for (List<Cand> l : extra.values()) for (Cand c : l) {
            if (c.from.startsWith("backup")) continue;
            if (c.from.equals("orphan")) {
                Cand own = world.get(key(c.x, c.z));
                double s = score(c, world, null, n);
                if (own != null && !(n[0] >= 2 && s > BAD)) continue;
            }
            reloc.putIfAbsent(c.file.getName() + "@" + c.sector, c);
        }
        for (Cand r : reloc.values()) {
            if (r.masks == null) continue;
            long bestK = 0;
            double best = Double.MAX_VALUE, second = Double.MAX_VALUE;
            for (long k : problem.keySet()) {
                if (!regionName(k).equals(r.file.getName())) continue;
                Map<Long, Cand> others = new HashMap<>(world);
                others.remove(k);
                double s = score(at(r, k), others, problem.keySet(), n);
                if (Double.isNaN(s) || n[0] < 2) continue;
                if (s < best) { second = best; best = s; bestK = k; } else if (s < second) second = s;
            }
            if (best > GOOD || second < best + MARGIN) continue;
            if (key(r.x, r.z) == bestK && world.get(bestK) == r) continue;
            Cand moved = at(r, bestK);
            extra.computeIfAbsent(bestK, q -> new ArrayList<>()).add(moved);
        }

        Map<Long, Cand> chosen = new HashMap<>();
        Set<Long> unresolved = new HashSet<>(problem.keySet());
        for (int pass = 0; pass < 2; pass++) {
            for (long k : new ArrayList<>(problem.keySet())) {
                Cand cur = world.get(k);
                boolean broken = cur == null;
                Set<Long> skip = new HashSet<>(unresolved);
                skip.remove(k);
                double curScore = cur == null ? Double.NaN : score(cur, world, skip, n);
                Cand best = null;
                double bestScore = Double.NaN;
                List<Cand> cs = extra.getOrDefault(k, new ArrayList<>());

                double top = Double.MAX_VALUE;
                double[] sc = new double[cs.size()];
                for (int i = 0; i < cs.size(); i++) {
                    sc[i] = score(cs.get(i), world, skip, n);
                    if (!Double.isNaN(sc[i]) && sc[i] < top) top = sc[i];
                }
                for (int i = 0; i < cs.size(); i++) {
                    Cand c = cs.get(i);
                    boolean ok = Double.isNaN(sc[i]) ? top == Double.MAX_VALUE : sc[i] <= top + TIE;
                    if (!ok) continue;
                    if (best == null || c.ticks > best.ticks) { best = c; bestScore = sc[i]; }
                }
                if (DEBUG && pass == 1) {
                    StringBuilder sb = new StringBuilder("  " + (int) (k >> 32) + "," + (int) k + " current " + (cur == null ? "none" : String.format("%.3f t%d", curScore, cur.ticks)));
                    for (int i = 0; i < cs.size(); i++) sb.append(String.format(" | %s s%d %.3f t%d", cs.get(i).from, cs.get(i).sector, sc[i], cs.get(i).ticks));
                    System.out.println(sb);
                }
                boolean take;
                if (best == null) take = false;
                else if (broken) {

                    score(best, world, skip, n);
                    take = Double.isNaN(bestScore) || n[0] < 2 || bestScore <= BAD;
                }
                else take = !Double.isNaN(bestScore) && bestScore <= GOOD && (Double.isNaN(curScore) || bestScore < curScore - MARGIN);
                if (take) {
                    chosen.put(k, best);
                    world.put(k, best);
                    unresolved.remove(k);
                } else if (pass == 1 && !chosen.containsKey(k)) {

                }
            }
        }

        Set<String> used = new HashSet<>();
        for (Cand c : chosen.values()) used.add(c.file.getName() + "@" + c.sector);
        for (long k : problem.keySet()) {
            Cand cur = world.get(k);
            if (chosen.containsKey(k) || cur == null) continue;
            if (used.contains(cur.file.getName() + "@" + cur.sector)) chosen.put(k, new Cand(cur.file, -1, cur.x, cur.z, 0, "nothing (generated fresh)"));
        }

        Map<String, List<Long>> byFile = new HashMap<>();
        for (long k : chosen.keySet()) byFile.computeIfAbsent(regionName(k), q -> new ArrayList<>()).add(k);
        int done = 0;
        if (!byFile.isEmpty() && beforeWrite != null) beforeWrite.run();
        for (Map.Entry<String, List<Long>> e : byFile.entrySet()) {
            if (progress != null) progress.at("Repairing " + e.getKey(), 70 + 30 * done++ / Math.max(1, byFile.size()));
            File target = new File(regionDir, e.getKey());
            rebuild(target, headers.get(target), chosen);
        }
        for (Map.Entry<Long, String> e : problem.entrySet()) {
            long k = e.getKey();
            int x = (int) (k >> 32), z = (int) k;
            Cand c = chosen.get(k);
            if (c != null) {
                if (e.getValue().startsWith("is missing")) report.restored++; else report.repaired++;
                report.lines.add("chunk " + x + "," + z + " " + e.getValue() + " -> " + (c.sector < 0 ? c.from : c.from + " copy (" + c.file.getName() + " sector " + c.sector + ")"));
            } else {
                report.unfixable++;
                report.lines.add("chunk " + x + "," + z + " " + e.getValue() + " -> left as it is (no better copy)");
            }
        }
    }

    static void rebuild(File target, int[] head, Map<Long, Cand> chosen) throws IOException {
        int[] rc = regionCoords(target);
        byte[] old = target.isFile() ? readAll(target) : new byte[0];
        Map<File, byte[]> sources = new HashMap<>();
        File tmp = new File(target.getPath() + ".repair");
        Files.deleteIfExists(tmp.toPath());
        RegionFile out = new RegionFile(tmp);
        try {
            for (int i = 0; i < 1024; i++) {
                int x = rc[0] * 32 + (i & 31), z = rc[1] * 32 + (i >> 5);
                Cand c = chosen.get(key(x, z));
                CompoundTag root;
                if (c != null && c.sector < 0) continue;
                if (c != null) {
                    byte[] src = c.file.equals(target) ? old : sources.computeIfAbsent(c.file, q -> { try { return readAll(q); } catch (IOException ex) { return new byte[0]; } });
                    root = blob(src, c.sector, false);
                    if (root != null) {

                        root.getCompound("Level").putInt("xPos", x);
                        root.getCompound("Level").putInt("zPos", z);
                    }
                } else {
                    int loc = head == null ? 0 : head[i];
                    if (loc == 0) continue;

                    root = blob(old, loc >>> 8, false);
                    if (root == null) { copyRaw(old, loc, out, i); continue; }
                }
                if (root == null) continue;
                try (DataOutputStream o = out.getChunkDataOutputStream(i & 31, i >> 5)) {
                    NbtIo.write(root, o);
                }
            }
        } finally {
            out.close();
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    static void copyRaw(byte[] old, int loc, RegionFile out, int i) {
        try {
            int off = (loc >>> 8) * 4096, cnt = loc & 0xff;
            if (off + 5 > old.length) return;
            int len = Math.min(intAt(old, off), Math.min(cnt * 4096, old.length - off) - 4);
            if (len < 1) return;
            java.lang.reflect.Method w = RegionFile.class.getDeclaredMethod("write", int.class, int.class, byte[].class, int.class);
            w.setAccessible(true);

            byte[] payload = Arrays.copyOfRange(old, off + 5, off + 4 + len);
            w.invoke(out, i & 31, i >> 5, payload, payload.length);
        } catch (Throwable ignored) { }
    }

    static byte[] headerOf(File f) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            if (r.length() < 4096) return null;
            byte[] b = new byte[4096];
            r.readFully(b);
            return b;
        } catch (IOException e) {
            return null;
        }
    }

    static int[] regionCoords(File f) {
        String[] p = f.getName().split("\\.");
        return new int[] { Integer.parseInt(p[1]), Integer.parseInt(p[2]) };
    }

    static String regionName(long k) {
        int x = (int) (k >> 32), z = (int) k;
        return "r." + (x >> 5) + "." + (z >> 5) + ".mcr";
    }
}
