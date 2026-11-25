package openhouse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class RoomPassword {
    public static final String MARK = "#Q", OLD_MARK = "#P";
    private static final int PROOF_BYTES = 8, ITER = 60000, SKEW = 10;

    public static final String NEEDED = "This server needs a password. Join with the OpenHouse mod to type it.";
    public static final String WRONG = "Wrong password.";
    public static final String OLD = "This server's password needs a newer OpenHouse. Update OpenHouse and join again.";
    public static final String LOCKED = "Too many wrong passwords. Wait a minute and try again.";

    private RoomPassword() { }

    public static byte[] key(String password) {
        try {
            javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(new javax.crypto.spec.PBEKeySpec(password.toCharArray(),
                    "OpenHouse password".getBytes(StandardCharsets.UTF_8), ITER, 256)).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static long minute() { return System.currentTimeMillis() / 60000L; }

    static final java.security.SecureRandom RND = new java.security.SecureRandom();

    public static String proof(byte[] key, String name) {
        byte[] r = new byte[2];
        RND.nextBytes(r);
        return proof(key, name, minute(), Base64.getUrlEncoder().withoutPadding().encodeToString(r));
    }

    static String proof(byte[] key, String name, long min, String salt) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = m.doFinal((salt + "|" + name.toLowerCase(Locale.ROOT) + "|" + min).getBytes(StandardCharsets.UTF_8));
            byte[] cut = new byte[PROOF_BYTES];
            System.arraycopy(h, 0, cut, 0, PROOF_BYTES);
            return MARK + salt + Base64.getUrlEncoder().withoutPadding().encodeToString(cut);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isProof(String handshakeName) {
        return handshakeName != null && (handshakeName.startsWith(MARK) || handshakeName.startsWith(OLD_MARK));
    }
    public static boolean isOld(String proof) { return proof != null && proof.startsWith(OLD_MARK); }

    private static String cached_pw;
    private static byte[] cached_key;
    private static synchronized byte[] serverKey(String pw) {
        if (!pw.equals(cached_pw)) { cached_key = key(pw); cached_pw = pw; }
        return cached_key;
    }

    private static final java.util.Map<String, Long> used = new java.util.HashMap<>();

    public static boolean matches(String password, String sentProof, String name) {
        if (password == null || password.isEmpty() || sentProof == null || name == null || !sentProof.startsWith(MARK) || sentProof.length() != 16) return false;
        byte[] key = serverKey(password), got = sentProof.getBytes(StandardCharsets.UTF_8);
        String salt = sentProof.substring(2, 5);
        long now = minute();
        boolean ok = false;
        for (long m = now - SKEW; m <= now + SKEW; m++) ok |= MessageDigest.isEqual(proof(key, name, m, salt).getBytes(StandardCharsets.UTF_8), got);
        if (!ok) return false;
        synchronized (used) {
            used.values().removeIf(t -> now - t > 2 * SKEW + 5);
            if (used.containsKey(sentProof)) return false;
            used.put(sentProof, now);
        }
        return true;
    }

    private static final java.util.Map<String, long[]> wrong = new java.util.HashMap<>();
    private static long all_from, all_until;
    private static int all_cnt;

    public static synchronized boolean locked(String name) {
        long now = System.currentTimeMillis();
        if (now < all_until) return true;
        long[] w = wrong.get(name.toLowerCase(Locale.ROOT));
        return w != null && now < w[2];
    }

    public static synchronized void wrongGuess(String name) {
        long now = System.currentTimeMillis();
        if (now - all_from > 60000) { all_from = now; all_cnt = 0; }
        if (++all_cnt >= 30) all_until = now + 60000;
        String k = name.toLowerCase(Locale.ROOT);
        long[] w = wrong.get(k);
        if (w == null || (now - w[1] > 600000 && now >= w[2])) { w = new long[] { 0, now, 0, w == null ? 0 : w[3] }; wrong.put(k, w); }
        if (++w[0] >= 5) { w[3]++; w[2] = now + Math.min(30, 1L << Math.min(5, w[3] - 1)) * 60000; w[0] = 0; w[1] = now; }
        if (wrong.size() > 2000) wrong.clear();
    }

    public static synchronized void rightGuess(String name) { wrong.remove(name.toLowerCase(Locale.ROOT)); }
}
