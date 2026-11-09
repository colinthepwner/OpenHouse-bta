package openhouse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ShareFolders {
    private ShareFolders() { }

    public static final String ID = "openhouse:folder";

    public static final String SETTING = "share-folders";
    public static final String DEFAULT = "roms";

    public static final long MAX_FILE = 80L * 1024 * 1024;
    public static final long MAX_TOTAL = 512L * 1024 * 1024;
    public static final int MAX_FILES = 500;

    static final Set<String> GAMES_OWN = new HashSet<>(Arrays.asList(
            "mods", "config", "saves", "logs", "bin", "libraries", "versions", "assets",
            "resourcepacks", "texturepacks", "server-resource-packs", "shaderpacks", "resources",
            "screenshots", "stats", "datapacks", "coremods", "packresources", "languages",
            "crash-reports", "world", "world_nether", "world_the_end", "players", "openhouse",
            "patches", "jarmods", "natives", "meta", "backups", "journeymap"));

    static final Set<String> NEVER = new HashSet<>(Arrays.asList("btemu-cef", "btemu-cores"));

    public static List<String> folders(String setting) {
        List<String> out = new ArrayList<>();
        String s = setting == null || setting.trim().isEmpty() ? DEFAULT : setting.trim();
        if (s.equalsIgnoreCase("off") || s.equalsIgnoreCase("none") || s.equalsIgnoreCase("false")) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String part : s.split("[,;]")) {
            String n = part.trim();
            if (n.isEmpty() || !n.matches("[A-Za-z0-9._+\\- ]{1,64}") || n.startsWith(".")
                    || n.contains("..") || GAMES_OWN.contains(n.toLowerCase(Locale.ROOT)) || NEVER.contains(n.toLowerCase(Locale.ROOT)))
                continue;
            seen.add(n);
        }
        out.addAll(seen);
        return out;
    }

    public static final class Item {
        public final String path;
        public final File file;
        public final long size;
        public final String sha256;
        Item(String path, File file, long size, String sha256) {
            this.path = path; this.file = file; this.size = size; this.sha256 = sha256;
        }
    }

    public static List<Item> scan(File root, List<String> folders) {
        List<Item> out = new ArrayList<>();
        long total = 0;
        for (String name : folders) {
            File dir = new File(root, name);
            if (!dir.isDirectory()) continue;
            List<File> files = new ArrayList<>();
            walk(dir, files, 0);
            files.sort(Comparator.comparing(f -> f.getAbsolutePath().toLowerCase(Locale.ROOT)));
            for (File f : files) {
                if (out.size() >= MAX_FILES || total >= MAX_TOTAL) break;
                long len = f.length();
                if (len <= 0 || len > MAX_FILE || total + len > MAX_TOTAL) continue;
                String rel = name + "/" + relative(dir, f);
                if (safePath(rel, folders) == null) continue;
                try {
                    out.add(new Item(rel, f, len, Rtc.sha256(f)));
                    total += len;
                } catch (IOException ignored) {

                }
            }
        }
        return out;
    }

    private static void walk(File dir, List<File> into, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().startsWith(".")) continue;
            if (k.isDirectory()) {
                if (depth < 3) walk(k, into, depth + 1);
            } else if (k.isFile()) into.add(k);
        }
    }

    private static String relative(File dir, File f) {
        return dir.toPath().toAbsolutePath().relativize(f.toPath().toAbsolutePath()).toString().replace('\\', '/');
    }

    public static String safePath(String rel, List<String> folders) {
        if (rel == null || rel.length() > 200 || rel.indexOf('\\') >= 0 || rel.startsWith("/")) return null;
        String[] parts = rel.split("/");
        if (parts.length < 2 || parts.length > 5) return null;
        boolean allowed = false;
        for (String f : folders) if (f.equals(parts[0])) allowed = true;
        if (!allowed) return null;
        if (GAMES_OWN.contains(parts[0].toLowerCase(Locale.ROOT)) || NEVER.contains(parts[0].toLowerCase(Locale.ROOT))) return null;
        for (String p : parts) {

            if (p.isEmpty() || p.equals(".") || p.equals("..") || p.startsWith(".") || !p.matches("[A-Za-z0-9._+\\-() ,'!&\\[\\]]{1,80}")) return null;
            if (p.endsWith(" ") || p.endsWith(".")) return null;

            String bare = p.replaceFirst("\\..*$", "").toUpperCase(Locale.ROOT);
            if (bare.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]")) return null;
        }
        return String.join("/", parts);
    }

    public static int fill(File from, File to, List<String> folders, java.util.function.Consumer<String> log) {
        int added = 0;
        for (Item it : scan(from, folders)) {
            File dest = new File(to, it.path);
            if (dest.isFile() && dest.length() == it.size) continue;
            try {
                if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
                if (dest.exists()) continue;
                try {
                    Files.createLink(dest.toPath(), it.file.toPath());
                } catch (IOException | UnsupportedOperationException noLink) {
                    Files.copy(it.file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                added++;
            } catch (IOException e) {
                if (log != null) log.accept("couldn't give the server " + it.path + " (" + e + ")");
            }
        }
        return added;
    }

    public static File receive(File root, String path, byte[] bytes, String sha256) throws IOException {
        File dest = new File(root, path);
        if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
        if (dest.isFile()) {
            if (dest.length() == bytes.length && sha256 != null && sha256.equals(Rtc.sha256(dest))) return null;
            String name = dest.getName();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
            File beside = null;
            for (int n = 2; n < 50; n++) {
                File f = new File(dest.getParentFile(), base + " (" + n + ")" + ext);
                if (!f.exists()) { beside = f; break; }
                if (f.length() == bytes.length && sha256 != null && sha256.equals(Rtc.sha256(f))) return null;
            }
            if (beside == null) throw new IOException("too many files called " + name);
            dest = beside;
        }
        File part = new File(dest.getParentFile(), dest.getName() + ".part");
        Files.write(part.toPath(), bytes);
        Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return dest;
    }

    public static String offer(List<Item> items) {
        StringBuilder b = new StringBuilder();
        for (Item it : items) b.append(it.sha256).append(' ').append(it.size).append(' ').append(it.path).append('\n');
        return b.toString();
    }
}
