package openhouse;

public final class Version {
    private Version() { }

    public static final String VERSION = "1.1.27";

    public static String base(String v) {
        if (v == null) return null;
        v = v.trim();
        if (v.startsWith("v")) v = v.substring(1);
        int plus = v.indexOf('+');
        return plus < 0 ? v : v.substring(0, plus);
    }

    public static boolean same(String a, String b) {
        String x = base(a), y = base(b);
        return x != null && x.equals(y);
    }

    public static final int PROTOCOL = 1;

    static int proto_of(String v) {
        if (base(v) == null) return 0;
        return compare(v, "1.1.8") >= 0 && compare(v, "1.1.15") <= 0 ? 1 : 0;
    }

    public static int proto(String v, Object p) {
        if (p instanceof Number) return ((Number) p).intValue();
        if (p instanceof String) try { return Integer.parseInt(((String) p).trim()); } catch (NumberFormatException ignored) { }
        return proto_of(v);
    }

    public static boolean compatible(String v, Object p) {
        if (same(v, VERSION)) return true;
        int q = proto(v, p);
        return q > 0 && q == PROTOCOL;
    }

    public static String speak_as(String v, Object p) {
        return p == null && v != null && compatible(v, null) ? base(v) : VERSION;
    }

    public static int compare(String a, String b) {
        String x = base(a), y = base(b);
        if (x == null || y == null) return x == null ? (y == null ? 0 : -1) : 1;
        String[] p = x.split("\\."), q = y.split("\\.");
        for (int i = 0; i < Math.max(p.length, q.length); i++) {
            int m = i < p.length ? num(p[i]) : 0, n = i < q.length ? num(q[i]) : 0;
            if (m != n) return m < n ? -1 : 1;
        }
        return 0;
    }

    private static int num(String s) {
        int n = 0;
        for (int i = 0; i < s.length() && Character.isDigit(s.charAt(i)); i++) n = n * 10 + (s.charAt(i) - '0');
        return n;
    }
}
