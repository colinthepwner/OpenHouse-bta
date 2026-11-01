package openhouse.mod;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Base64;
import java.util.Properties;

final class PasswordStore {
    private static final File FILE = new File(OpenHouseMod.dataDir(), "passwords.properties");

    private PasswordStore() { }

    static synchronized byte[] get(String target) {
        if (target == null) return null;
        String v = load().getProperty(target);
        try {
            return v == null ? null : Base64.getDecoder().decode(v.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static synchronized void put(String target, byte[] key) {
        Properties p = load();
        p.setProperty(target, Base64.getEncoder().encodeToString(key));
        store(p);
    }

    static synchronized void forget(String target) {
        Properties p = load();
        if (p.remove(target) != null) store(p);
    }

    private static Properties load() {
        Properties p = new Properties();
        if (FILE.isFile()) {
            try (FileInputStream in = new FileInputStream(FILE)) { p.load(in); } catch (Exception e) {
                OpenHouseMod.LOG.warn("can't read {}: {}", FILE, e.toString());
            }
        }
        return p;
    }

    private static void store(Properties p) {
        FILE.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(FILE)) {
            p.store(out, "OpenHouse: servers this game has the password for (not the passwords themselves)");
        } catch (Exception e) {
            OpenHouseMod.LOG.warn("can't save {}: {}", FILE, e.toString());
        }
    }
}
