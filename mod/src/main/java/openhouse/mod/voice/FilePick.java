package openhouse.mod.voice;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class FilePick {
    private FilePick() { }

    static final String TITLE = "Pick up to 3 hurt sounds";

    static List<File> audio() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] ex = HurtClip.EXTS;
        try {
            if (os.contains("mac")) {
                StringBuilder types = new StringBuilder();
                for (String e : ex) types.append(types.length() == 0 ? "" : ", ").append('"').append(e).append('"');

                return run("osascript", "-e", "set fs to (choose file with prompt \"" + TITLE + "\" of type {" + types + "} with multiple selections allowed)",
                        "-e", "set out to \"\"", "-e", "repeat with f in fs", "-e", "set out to out & POSIX path of f & linefeed", "-e", "end repeat", "-e", "return out");
            }
            if (!os.contains("win")) {
                StringBuilder glob = new StringBuilder();
                for (String e : ex) glob.append(glob.length() == 0 ? "" : " ").append("*.").append(e);
                List<File> f = run("zenity", "--file-selection", "--multiple", "--separator=\n", "--title=" + TITLE, "--file-filter=Sounds | " + glob);
                if (f != null || tried) return f;
                f = run("kdialog", "--getopenfilename", "--multiple", "--separate-output", System.getProperty("user.home"), glob.toString());
                if (f != null || tried) return f;
            }
        } catch (Throwable ignored) { }

        if (os.contains("win") && java.awt.GraphicsEnvironment.isHeadless()) return windows();
        List<File> f = awt();
        if (f == null && os.contains("win") && !tried_awt) return windows();
        return f;
    }

    private static boolean tried_awt;

    private static List<File> windows() {
        StringBuilder flt = new StringBuilder();
        for (String e : HurtClip.EXTS) flt.append(flt.length() == 0 ? "" : ";").append("*.").append(e);
        String ico = icon_path();

        String ps = "Add-Type -AssemblyName System.Windows.Forms;Add-Type -AssemblyName System.Drawing;"
                + "$o=New-Object System.Windows.Forms.Form -Property @{TopMost=$true;ShowInTaskbar=$false;Text='OpenHouse'};"
                + (ico == null ? "" : "try{$o.Icon=[System.Drawing.Icon]::FromHandle(([System.Drawing.Bitmap]::FromFile('" + ico.replace("'", "''") + "')).GetHicon());"
                        + "$o.ShowInTaskbar=$true;$o.FormBorderStyle='None';$o.Opacity=0;$o.StartPosition='CenterScreen';$o.Size=New-Object System.Drawing.Size(1,1);$o.Show()}catch{};")
                + "$d=New-Object System.Windows.Forms.OpenFileDialog;"
                + "$d.Title='" + TITLE + "';$d.Multiselect=$true;$d.Filter='Sounds|" + flt + "';"
                + "$r=$d.ShowDialog($o);$o.Close();"
                + "if($r -eq 'OK'){[Console]::OutputEncoding=[Text.Encoding]::UTF8;$d.FileNames}";
        return run("powershell", "-NoProfile", "-NonInteractive", "-STA", "-Command", ps);
    }

    private static String icon_path() {
        try {
            File f = new File(openhouse.mod.OpenHouseMod.dataDir(), "picker-icon.png");
            if (!f.isFile()) {
                java.nio.file.Path p = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("openhouse")
                        .flatMap(m -> m.findPath("icon.png")).orElse(null);
                if (p == null) return null;
                f.getParentFile().mkdirs();
                java.nio.file.Files.copy(p, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return f.getAbsolutePath();
        } catch (Throwable e) { return null; }
    }

    private static boolean tried;

    private static List<File> run(String... cmd) {
        tried = false;
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            tried = true;
            List<File> out = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    File f = new File(line.trim());
                    if (!line.trim().isEmpty() && f.isFile()) out.add(f);
                }
            }
            p.waitFor();
            return out.isEmpty() ? null : out;
        } catch (Throwable e) { return null; }
    }

    private static boolean ok(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        for (String e : HurtClip.EXTS) if (l.endsWith("." + e)) return true;
        return false;
    }

    private static List<File> awt() {
        tried_awt = false;
        if (java.awt.GraphicsEnvironment.isHeadless()) return null;
        java.awt.Frame top = null;
        try {
            top = new java.awt.Frame();
            top.setUndecorated(true);
            top.setAlwaysOnTop(true);
            top.setLocationRelativeTo(null);
            top.setVisible(true);
            java.awt.FileDialog d = new java.awt.FileDialog(top, TITLE, java.awt.FileDialog.LOAD);
            d.setMultipleMode(true);
            d.setFilenameFilter((dir, n) -> ok(n));
            StringBuilder w = new StringBuilder();
            for (String e : HurtClip.EXTS) w.append(w.length() == 0 ? "" : ";").append("*.").append(e);
            d.setFile(w.toString());
            tried_awt = true;
            d.setVisible(true);
            List<File> out = new ArrayList<>();
            for (File f : d.getFiles()) if (f.isFile()) out.add(f);
            return out.isEmpty() ? null : out;
        } catch (Throwable e) {
            return null;
        } finally {
            if (top != null) top.dispose();
        }
    }
}
