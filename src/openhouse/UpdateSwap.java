package openhouse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class UpdateSwap {
    private UpdateSwap() { }

    static PrintStream log;

    static void log(String s) {
        log.printf("%tT %s%n", System.currentTimeMillis(), s);
        log.flush();
    }

    public static void main(String[] args) throws Exception {
        int sep = Arrays.asList(args).indexOf("--");
        if (args.length < 5 || sep < 5) { System.err.println("usage: UpdateSwap <new jar> <log> <command log> <dir> <target>... -- [command...]"); System.exit(2); }
        File newJar = new File(args[0]);
        log = new PrintStream(new FileOutputStream(args[1], true), true, "UTF-8");
        File gameLog = new File(args[2]);
        File dir = new File(args[3]);
        List<File> folders = new ArrayList<>();
        for (int i = 4; i < sep; i++) folders.add(new File(args[i]));
        List<String> cmd = new ArrayList<>(Arrays.asList(args).subList(sep + 1, args.length));

        log("waiting for OpenHouse's process to quit, then putting " + newJar.getName() + " in place");
        InputStream in = System.in;
        try { while (in.read() >= 0) { } } catch (Exception ignored) { }
        Thread.sleep(500);

        for (File target : folders) {
            if (target.getName().toLowerCase().endsWith(".jar") && !target.isDirectory()) { replaceFile(target, newJar); continue; }
            File[] jars = target.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
            if (jars == null) continue;
            List<File> old = new ArrayList<>();
            for (File j : jars) if (isOpenHouseMod(j)) old.add(j);
            if (old.isEmpty()) continue;
            File dest = new File(target, newJar.getName()), part = new File(target, newJar.getName() + ".part");
            try {
                Files.copy(newJar.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                log("couldn't copy the new jar into " + target + ": " + e + " - leaving this folder as it is");
                continue;
            }
            boolean cleared = true;
            for (File j : old) {
                if (!deleteSoon(j)) { cleared = false; log("couldn't remove " + j + " - leaving this folder as it is"); }
            }
            if (!cleared || (dest.exists() && !deleteSoon(dest)) || !part.renameTo(dest)) {
                part.delete();
                if (cleared) log("couldn't put the new jar in place in " + target);
                continue;
            }
            log("updated " + target);
        }

        if (!cmd.isEmpty()) {
            gameLog.getParentFile().mkdirs();
            new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).redirectOutput(gameLog).start();
            log("started the game again");
        }
    }

    static void replaceFile(File target, File newJar) throws InterruptedException {
        File part = new File(target.getPath() + ".part");
        try {
            Files.copy(newJar.toPath(), part.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log("couldn't copy the new jar next to " + target + ": " + e);
            return;
        }
        if (!deleteSoon(target) || !part.renameTo(target)) {
            part.delete();
            log("couldn't replace " + target);
            return;
        }
        log("updated " + target);
    }

    static boolean deleteSoon(File f) throws InterruptedException {
        long until = System.currentTimeMillis() + 60000;
        while (true) {
            if (!f.exists() || f.delete()) { log("removed " + f.getName()); return true; }
            if (System.currentTimeMillis() > until) return false;
            Thread.sleep(500);
        }
    }

    static boolean isOpenHouseMod(File jar) {
        try (ZipFile z = new ZipFile(jar)) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e == null) return false;
            byte[] b;
            try (InputStream s = z.getInputStream(e)) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = s.read(buf)) > 0) o.write(buf, 0, n);
                b = o.toByteArray();
            }
            Map<String, Object> root = Json.obj(Json.parse(new String(b, StandardCharsets.UTF_8)));
            return root != null && "openhouse".equals(Json.str(root.get("id")));
        } catch (Exception ex) {
            return false;
        }
    }
}
