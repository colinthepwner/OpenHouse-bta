package openhouse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class RoomPassword {
    public static final String MARK = "#P";
    private static final int PROOF_BYTES = 10;

    public static final String NEEDED = "This server needs a password. Join with the OpenHouse mod to type it.";
    public static final String WRONG = "Wrong password.";

    private RoomPassword() { }

    public static byte[] key(String password) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(("OpenHouse password\0" + password).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String proof(byte[] key, String name) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = m.doFinal(name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            byte[] cut = new byte[PROOF_BYTES];
            System.arraycopy(h, 0, cut, 0, PROOF_BYTES);
            return MARK + Base64.getUrlEncoder().withoutPadding().encodeToString(cut);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isProof(String handshakeName) {
        return handshakeName != null && handshakeName.startsWith(MARK);
    }

    public static boolean matches(String password, String sentProof, String name) {
        if (password == null || password.isEmpty() || sentProof == null || name == null) return false;
        byte[] want = proof(key(password), name).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(want, sentProof.getBytes(StandardCharsets.UTF_8));
    }
}
