package openhouse.mod;

public final class NameColor {
    private NameColor() { }

    static final byte[] CODES = { 3, 14, 5, 2, 1, 9, 6, 4 };

    public static byte code_of(String name) {
        if (name == null) return 0;
        int h = name.toLowerCase(java.util.Locale.ROOT).hashCode();
        h ^= h >>> 16;
        return CODES[(h & 0x7FFFFFFF) % CODES.length];
    }
}
