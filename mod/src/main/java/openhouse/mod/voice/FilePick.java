package openhouse.mod.voice;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class FilePick {
    private FilePick() { }

    static File audio() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) return run("osascript", "-e", "POSIX path of (choose file with prompt \"Pick a hurt sound (.wav or .ogg)\" of type {\"wav\", \"ogg\", \"aiff\", \"aif\", \"au\"})");
            if (!os.contains("win")) {
                File f = run("zenity", "--file-selection", "--title=Pick a hurt sound", "--file-filter=Sounds | *.wav *.ogg *.aiff *.aif *.au");
                if (f != null || tried) return f;
                f = run("kdialog", "--getopenfilename", System.getProperty("user.home"), "*.wav *.ogg *.aiff *.aif *.au");
                if (f != null || tried) return f;
            }
        } catch (Throwable ignored) { }
        return awt();
    }

    private static boolean tried;

    private static File run(String... cmd) {
        tried = false;
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            tried = true;
            String line;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) { line = r.readLine(); }
            p.waitFor();
            if (line == null || line.trim().isEmpty()) return null;
            File f = new File(line.trim());
            return f.isFile() ? f : null;
        } catch (Throwable e) { return null; }
    }

    private static File awt() {
        if (java.awt.GraphicsEnvironment.isHeadless()) return null;
        java.awt.Frame top = null;
        try {
            top = new java.awt.Frame();
            top.setUndecorated(true);
            top.setAlwaysOnTop(true);
            top.setLocationRelativeTo(null);
            top.setVisible(true);
            java.awt.FileDialog d = new java.awt.FileDialog(top, "Pick a hurt sound (.wav or .ogg)", java.awt.FileDialog.LOAD);
            d.setFilenameFilter((dir, n) -> { String l = n.toLowerCase(Locale.ROOT); return l.endsWith(".wav") || l.endsWith(".ogg") || l.endsWith(".aiff") || l.endsWith(".aif") || l.endsWith(".au"); });
            d.setFile("*.wav;*.ogg;*.aiff;*.aif;*.au");
            d.setVisible(true);
            String f = d.getFile(), dir = d.getDirectory();
            if (f == null || dir == null) return null;
            File r = new File(dir, f);
            return r.isFile() ? r : null;
        } catch (Throwable e) {
            return null;
        } finally {
            if (top != null) top.dispose();
        }
    }
}
