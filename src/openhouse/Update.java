package openhouse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Update {
    private Update() { }

    static final String API = "https://api.github.com/repos/colinthepwner/OpenHouse-bta/releases/latest";
    static final String DOWNLOADS = "https://github.com/colinthepwner/OpenHouse-bta/releases/download/";
    public static final String HELPER_ASSET = "openhouse-helper.jar";

    public static final class Release {
        public final String version, file, url, sha256;
        public final long size;
        public Release(String version, String file, String url, String sha256, long size) {
            this.version = version; this.file = file; this.url = url; this.sha256 = sha256; this.size = size;
        }
        public boolean newer() { return version != null && Version.compare(version, Version.VERSION) > 0; }
    }

    public static String gameVersion(String modVersion) {
        int plus = modVersion == null ? -1 : modVersion.indexOf('+');
        return plus < 0 ? "" : modVersion.substring(plus + 1);
    }

    public static Release latestMod(String gameVersion) throws IOException {
        Map<String, Object> root = latest();
        String version = Version.base(Json.str(root.get("tag_name")));
        String exact = "openhouse-" + version + "+" + gameVersion + ".jar";
        Map<String, Object> pick = null;
        for (Map<String, Object> a : assets(root)) {
            String name = Json.str(a.get("name"));
            if (name == null || !name.startsWith("openhouse-") || !name.endsWith(".jar") || name.contains("helper") || name.contains("sources")) continue;
            if (name.equals(exact)) { pick = a; break; }
            if (pick == null) pick = a;
        }
        if (pick == null) throw new IOException("the newest release has no mod jar");
        return release(version, pick);
    }

    public static Release latestHelper() throws IOException {
        Map<String, Object> root = latest();
        String version = Version.base(Json.str(root.get("tag_name")));
        for (Map<String, Object> a : assets(root))
            if (HELPER_ASSET.equals(Json.str(a.get("name")))) return release(version, a);
        throw new IOException("the newest release has no helper");
    }

    static Map<String, Object> latest() throws IOException {
        HttpURLConnection c = open(API, "application/vnd.github+json");
        String body;
        try (InputStream in = c.getInputStream()) { body = new String(readAll(in, 1 << 20), StandardCharsets.UTF_8); }
        Map<String, Object> root = Json.obj(Json.parse(body));
        if (root == null || Version.base(Json.str(root.get("tag_name"))) == null) throw new IOException("GitHub's answer didn't make sense");
        return root;
    }

    static List<Map<String, Object>> assets(Map<String, Object> root) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object list = root.get("assets");
        if (list instanceof List) for (Object o : (List<?>) list) { Map<String, Object> a = Json.obj(o); if (a != null) out.add(a); }
        return out;
    }

    static Release release(String version, Map<String, Object> a) {
        String digest = Json.str(a.get("digest"));
        String sha = digest != null && digest.startsWith("sha256:") ? digest.substring(7).toLowerCase() : null;
        Object size = a.get("size");
        return new Release(version, Json.str(a.get("name")), Json.str(a.get("browser_download_url")), sha,
                size instanceof Number ? ((Number) size).longValue() : -1);
    }

    public interface Progress { void at(long done, long total); }

    public static File download(Release r, File dir, Progress p) throws IOException {
        if (r.url == null || !r.url.startsWith(DOWNLOADS)) throw new IOException("unexpected download address");
        if (r.file == null || !r.file.matches("[A-Za-z0-9._+\\-]{1,120}")) throw new IOException("unexpected file name");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        File part = new File(dir, r.file + ".part"), done = new File(dir, r.file);
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IOException(e.toString()); }
        HttpURLConnection c = open(r.url, null);
        long total = r.size > 0 ? r.size : c.getContentLengthLong(), got = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                got += n;
                if (p != null) p.at(got, total);
                if (got > (64L << 20)) throw new IOException("the download is far too big");
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b & 0xff));
        if ((r.size > 0 && got != r.size) || (r.sha256 != null && !hex.toString().equals(r.sha256))) {
            part.delete();
            throw new IOException("the download arrived damaged - try again");
        }
        if (done.exists() && !done.delete()) throw new IOException("can't replace " + done);
        if (!part.renameTo(done)) throw new IOException("can't move the download into place");
        return done;
    }

    public static Process startSwap(File self, File newJar, File dataDir, List<String> targets, List<String> relaunch, File workDir) throws IOException {
        File work = new File(dataDir, "update");
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("can't create " + work);
        File swapJar = new File(work, "openhouse-swap.jar");
        java.nio.file.Files.copy(self.toPath(), swapJar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath());
        cmd.add("-Xmx64m");
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-cp");
        cmd.add(swapJar.getAbsolutePath());
        cmd.add("openhouse.UpdateSwap");
        cmd.add(newJar.getAbsolutePath());
        cmd.add(new File(dataDir, "update.log").getAbsolutePath());
        cmd.add(new File(dataDir, "restart.log").getAbsolutePath());
        cmd.add(workDir.getAbsolutePath());
        cmd.addAll(targets);
        cmd.add("--");
        cmd.addAll(relaunch);
        return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(new File(work, "swap.out")).start();
    }

    static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "OpenHouse/" + Version.VERSION);
        if (accept != null) c.setRequestProperty("Accept", accept);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
        return c;
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            o.write(buf, 0, n);
            if (o.size() > max) throw new IOException("answer too big");
        }
        return o.toByteArray();
    }
}
