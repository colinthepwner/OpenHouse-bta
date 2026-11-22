package openhouse.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Screen;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

public final class Firewall {
    private Firewall() { }

    static final boolean WIN = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    static final String RULE = "OpenHouse (Java)";

    static volatile List<String> blocked;
    static volatile boolean fixing, said_later;
    private static boolean told;

    static List<String> progs() {
        File bin = new File(System.getProperty("java.home"), "bin");
        List<String> l = new ArrayList<>();
        for (String n : new String[] { "java.exe", "javaw.exe" }) l.add(new File(bin, n).getAbsolutePath());
        return l;
    }

    public static void check() {
        if (!WIN) return;
        told = "true".equals(OpenHouseMod.setting("firewall-told", "false"));
        Thread t = new Thread(Firewall::scan, "OpenHouse firewall check");
        t.setDaemon(true);
        t.start();
    }

    static void scan() {
        StringBuilder ps = new StringBuilder("$p = @(");
        List<String> pr = progs();
        for (int i = 0; i < pr.size(); i++) ps.append(i > 0 ? "," : "").append(q(pr.get(i)));
        ps.append(")\n");

        ps.append("Get-NetFirewallApplicationFilter | ? { $_.Program } | % { $x = [Environment]::ExpandEnvironmentVariables($_.Program); "
                + "if ($p -contains $x) { $_ | Get-NetFirewallRule | ? { $_.Action -eq 'Block' -and $_.Direction -eq 'Inbound' -and $_.Enabled -eq 'True' } | % { 'BLOCK ' + $x } } }\n");
        List<String> got = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", enc(ps.toString())).redirectErrorStream(true).start();
            p.getOutputStream().close();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (!line.startsWith("BLOCK ")) continue;
                    String prog = line.substring(6).trim();
                    boolean have = false;
                    for (String g : got) have |= g.equalsIgnoreCase(prog);
                    if (!have) got.add(prog);
                }
            }
            p.waitFor();
        } catch (Exception e) {
            OpenHouseMod.LOG.info("couldn't check Windows Firewall: {}", e.toString());
            return;
        }
        blocked = got;
        if (!got.isEmpty()) OpenHouseMod.LOG.warn("Windows Firewall blocks {} (someone clicked Cancel when Windows asked)", got);
    }

    public static boolean cut_in(Minecraft mc, Screen back) {
        if (!WIN || fixing) return false;
        List<String> b = blocked;
        boolean block = b != null && !b.isEmpty() && !said_later;
        if (!block && told) return false;
        if (!told) { told = true; OpenHouseMod.put_setting("firewall-told", "true"); }
        mc.displayScreen(new ScreenFirewall(back, block));
        return true;
    }

    static void fix(java.util.function.Consumer<Boolean> done) {
        fixing = true;
        new Thread(() -> {
            try {
                StringBuilder in = new StringBuilder();
                for (String p : progs()) {
                    in.append("$p = ").append(q(p)).append("\n");
                    in.append("Get-NetFirewallApplicationFilter | ? { $_.Program -and [Environment]::ExpandEnvironmentVariables($_.Program) -ieq $p } | Get-NetFirewallRule"
                            + " | ? { $_.Action -eq 'Block' -or $_.DisplayName -eq " + q(RULE) + " } | Remove-NetFirewallRule\n");
                    in.append("if (Test-Path $p) { New-NetFirewallRule -DisplayName ").append(q(RULE)).append(" -Direction Inbound -Program $p -Action Allow -Profile Any | Out-Null }\n");
                }
                String outer = "Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile','-NonInteractive','-EncodedCommand','" + enc(in.toString()) + "'";
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", enc(outer)).redirectErrorStream(true).start();
                p.getOutputStream().close();
                p.getInputStream().readAllBytes();
                int rc = p.waitFor();
                OpenHouseMod.LOG.info("firewall fix ended {}", rc);
                scan();
            } catch (Exception e) {
                OpenHouseMod.LOG.warn("firewall fix failed: {}", e.toString());
            } finally {
                fixing = false;
            }
            List<String> b = blocked;
            done.accept(b != null && b.isEmpty());
        }, "OpenHouse firewall fix").start();
    }

    static String q(String s) { return "'" + s.replace("'", "''") + "'"; }
    static String enc(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_16LE)); }
}
