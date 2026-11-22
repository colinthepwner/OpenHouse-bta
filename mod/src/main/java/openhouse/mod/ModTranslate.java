package openhouse.mod;

import openhouse.ModShare;
import openhouse.Rtc;
import openhouse.Version;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class ModTranslate {
    private ModTranslate() { }

    static volatile String now = "";

    static boolean wanted(int host_proto) {
        return OpenHouseMod.protocol() == openhouse.bridge.Bridge.P7 && host_proto == openhouse.bridge.Bridge.P8;
    }

    static File mark(File pack, String file) { return new File(pack, file + ".oh7"); }
    static String stamp(String sha) { return sha + " " + Version.VERSION + " " + vt_print(); }

    private static String vt_print;
    static String vt_print() {
        String v = vt_print;
        if (v == null) {
            try (java.io.InputStream in = openhouse.vt.Vt.class.getResourceAsStream("Vt.class")) {
                v = Integer.toHexString(java.util.Arrays.hashCode(in.readAllBytes()));
            } catch (Exception e) { v = "?"; }
            vt_print = v;
        }
        return v;
    }

    static boolean done(File pack, ModShare.Mod m) {
        try {
            File k = mark(pack, m.file);
            return k.isFile() && new File(pack, m.file).isFile() && new String(Files.readAllBytes(k.toPath()), StandardCharsets.UTF_8).trim().equals(stamp(m.sha256));
        } catch (Exception e) {
            return false;
        }
    }

    static File orig(File pack, String file) { return new File(pack, file + ".oh8"); }
    static File stubs(File pack) { return new File(pack, openhouse.vt.Live.PACK_STUBS); }

    static void pack(File pack, List<ModShare.Mod> host) throws Exception {
        List<ModShare.Mod> mods = new ArrayList<>();
        boolean any = false;
        for (ModShare.Mod m : host) {
            if (m.file == null || OpenHouseMod.MOD_ID.equals(m.id) || ModShare.TEXTURE_PACK.equals(m.id) || ModShare.ORIGINAL.equals(m.id)) continue;
            File f = new File(pack, m.file);
            if (!f.isFile() || !f.getName().endsWith(".jar")) continue;
            mods.add(m);
            if (!done(pack, m)) {

                Files.copy(f.toPath(), orig(pack, m.file).toPath(), StandardCopyOption.REPLACE_EXISTING);
                any = true;
            } else if (!orig(pack, m.file).isFile()) throw new java.io.IOException(m.file + " was translated without its original kept");
        }
        if (!any && stubs(pack).isFile()) return;
        List<File> game = game_jars();
        List<File> origs = new ArrayList<>();
        for (ModShare.Mod m : mods) origs.add(orig(pack, m.file));
        openhouse.vt.Live.begin(origs);
        try {
            for (ModShare.Mod m : mods) {
                now = m.file;
                File f = new File(pack, m.file), tmp = new File(pack, m.file + ".part");
                openhouse.vt.Live.mod(orig(pack, m.file), tmp, game, s -> OpenHouseMod.LOG.info(s));
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.write(mark(pack, m.file).toPath(), stamp(m.sha256).getBytes(StandardCharsets.UTF_8));
            }
            openhouse.vt.Live.LibPlan lp = openhouse.vt.Live.lib_plan();
            File lib = new File(OpenHouseMod.dataDir(), "lib");

            openhouse.vt.Live.Src client8 = null;
            try {
                client8 = client_src(lib);
                now = openhouse.vt.Live.PACK_ART;
                openhouse.vt.Live.art(client8, new File(pack, openhouse.vt.Live.PACK_ART), game);
            } catch (Exception e) {
                OpenHouseMod.LOG.info("BTA 8.0.1's pictures in parts failed ({}), getting the whole jar", e.toString());
                try {
                    client8 = openhouse.vt.Live.zip_src(Rtc.btaClient(lib, s -> now = s));
                    openhouse.vt.Live.art(client8, new File(pack, openhouse.vt.Live.PACK_ART), game);
                } catch (Exception e2) {
                    client8 = null;
                    OpenHouseMod.LOG.info("no BTA 8.0.1 block pictures ({}): its blocks get look-alikes", e2.toString());
                }
            }
            if (lp.pkgs.isEmpty()) { libs(pack).delete(); libs_bta(pack).delete(); }
            else {

                now = openhouse.vt.Live.PACK_LIBS;
                try {
                    openhouse.vt.Live.libs(lp, server_src(lib), lp.client ? (client8 != null ? client8 : client_src(lib)) : null, libs(pack), libs_bta(pack), game, s -> OpenHouseMod.LOG.info(s));
                } catch (Exception e) {
                    OpenHouseMod.LOG.info("BTA 8.0.1's libraries in parts failed ({}), getting the whole jars", e.toString());
                    openhouse.vt.Live.libs(lp, openhouse.vt.Live.zip_src(Rtc.btaServer(lib, s -> now = s)), lp.client ? openhouse.vt.Live.zip_src(Rtc.btaClient(lib, s -> now = s)) : null,
                            libs(pack), libs_bta(pack), game, s -> OpenHouseMod.LOG.info(s));
                }
                OpenHouseMod.LOG.info("room {}'s mods use {} packages of BTA 8.0.1's libraries this game hasn't got", pack.getName(), lp.pkgs.size());
            }
            now = openhouse.vt.Live.PACK_STUBS;
        } finally {
            openhouse.vt.Live.end(stubs(pack));
        }
        now = "";
    }

    static openhouse.vt.Live.Src client_src(File lib) throws java.io.IOException {
        File f = Rtc.btaClientIfThere(lib);
        if (f != null) return openhouse.vt.Live.zip_src(f);
        openhouse.vt.Parts p = openhouse.vt.Parts.open(Rtc.btaClientUrl(), "C", s -> now = s);
        return p != null ? p : openhouse.vt.Live.zip_src(Rtc.btaClient(lib, s -> now = s));
    }

    static openhouse.vt.Live.Src server_src(File lib) throws java.io.IOException {
        File f = Rtc.btaServerIfThere(lib);
        if (f != null) return openhouse.vt.Live.zip_src(f);
        openhouse.vt.Parts p = openhouse.vt.Parts.open(Rtc.btaServerUrl(), "S", s -> now = s);
        return p != null ? p : openhouse.vt.Live.zip_src(Rtc.btaServer(lib, s -> now = s));
    }

    static File libs(File pack) { return new File(pack, openhouse.vt.Live.PACK_LIBS); }
    static File libs_bta(File pack) { return new File(pack, openhouse.vt.Live.PACK_LIBS_BTA); }

    static List<File> game_jars() throws Exception {
        List<File> l = new ArrayList<>();
        l.add(new File(Class.forName("net.minecraft.client.Minecraft").getProtectionDomain().getCodeSource().getLocation().toURI()));
        try {
            l.add(Rtc.btaServer(new File(OpenHouseMod.dataDir(), "lib"), LocalServer.gameVersion(), s -> now = s));
        } catch (Exception e) {
            OpenHouseMod.LOG.info("translating without the BTA server's classes ({}): mods' server code may not work if you host", e.toString());
        }
        return l;
    }
}
