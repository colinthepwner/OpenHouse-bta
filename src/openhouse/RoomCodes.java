package openhouse;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RoomCodes {
    private RoomCodes() { }

    private static final SecureRandom RND = new SecureRandom();
    public static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVXYZ";
    static final String DIGITS = "123456789";

    static final String[] RHYMES = { "BCDEGPTVZ", "AHJK", "FLMNSX", "QU" };
    static final int RHYME_PERCENT = 15;
    private static final List<String> TRIPLES = new ArrayList<>();
    private static final List<String[]> PAIRS = new ArrayList<>();
    private static final Set<String> PAIR_SET = new HashSet<>();

    static {
        for (String g : RHYMES)
            for (char a : g.toCharArray()) for (char b : g.toCharArray()) for (char c : g.toCharArray())
                TRIPLES.add("" + a + b + c);
        for (char d : DIGITS.toCharArray()) {
            for (char t = '2'; t <= '9'; t++) PAIRS.add(new String[] { "" + d, "" + t + d });
            if (d == '7') PAIRS.add(new String[] { "7", "11" });
        }
        for (String[] p : PAIRS) PAIR_SET.add(p[0] + "/" + p[1]);
    }

    public static String next() {
        StringBuilder b = new StringBuilder(6);
        if (RND.nextInt(100) >= RHYME_PERCENT) {
            for (int k = 0; k < 3; k++) b.append(LETTERS.charAt(RND.nextInt(LETTERS.length())));
            for (int k = 0; k < 3; k++) b.append(DIGITS.charAt(RND.nextInt(DIGITS.length())));
            return b.toString();
        }
        String l = TRIPLES.get(RND.nextInt(TRIPLES.size()));
        String[] p = PAIRS.get(RND.nextInt(PAIRS.size()));
        boolean loneFirst = RND.nextBoolean();
        return b.append(l.charAt(0)).append(loneFirst ? p[0] : p[1]).append(l.charAt(1))
                .append(loneFirst ? p[1] : p[0]).append(l.charAt(2)).toString();
    }

    public static boolean isRandom(String c) {
        return isPlate(c) || isRhyme(c);
    }

    static boolean isPlate(String c) {
        if (c == null || c.length() != 6) return false;
        for (int k = 0; k < 3; k++) if (LETTERS.indexOf(c.charAt(k)) < 0) return false;
        for (int k = 3; k < 6; k++) if (DIGITS.indexOf(c.charAt(k)) < 0) return false;
        return true;
    }

    static boolean isRhyme(String c) {
        if (c == null || c.length() != 6) return false;
        String lone, num, l;
        if (Character.isDigit(c.charAt(1)) && !Character.isDigit(c.charAt(2))) {
            lone = c.substring(1, 2); num = c.substring(3, 5);
            l = "" + c.charAt(0) + c.charAt(2) + c.charAt(5);
        } else {
            lone = c.substring(4, 5); num = c.substring(1, 3);
            l = "" + c.charAt(0) + c.charAt(3) + c.charAt(5);
        }
        return PAIR_SET.contains(lone + "/" + num) && TRIPLES.contains(l);
    }

    static long plateCount() { return (long) LETTERS.length() * LETTERS.length() * LETTERS.length() * 729; }
    static long rhymeCount() { return 2L * TRIPLES.size() * PAIRS.size(); }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(i == 0 ? "" : " ").append(next());
        System.out.println(b);
    }
}
