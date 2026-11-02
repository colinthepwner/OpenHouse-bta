package openhouse.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.item.Item;
import net.minecraft.core.net.command.CommandSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.entity.player.PlayerServer;
import net.minecraft.server.net.command.ConsoleCommandSource;

public final class LazyCommands {
    private LazyCommands() { }

    public static final class Result {
        public final String command;
        public final boolean changed;
        Result(String command, boolean changed) { this.command = command; this.changed = changed; }
    }

    static CommandDispatcher<CommandSource> dispatcher(MinecraftServer s) {
        return s.getDimensionWorld(0).getCommandManager().getDispatcher();
    }

    static boolean exact(MinecraftServer s, String cmd) {
        try {
            ParseResults<CommandSource> p = dispatcher(s).parse(cmd, new ConsoleCommandSource(s));
            return !p.getReader().canRead() && p.getExceptions().isEmpty() && p.getContext().getCommand() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static Result resolve(MinecraftServer s, String typed) {
        if (s == null || typed == null) return null;
        String in = typed.trim();
        if (in.startsWith("/")) in = in.substring(1).trim();
        if (in.isEmpty()) return null;
        try { in = players(s, in); } catch (Throwable ignored) { }
        if (exact(s, in)) return new Result(in, !in.equals(typed.trim()));
        try {
            String r = lazy(s, in);
            if (r != null && exact(s, r)) return new Result(r, true);
        } catch (Throwable ignored) { }
        return null;
    }

    static final Set<String> FILLER = new HashSet<>(Arrays.asList(
            "to", "a", "an", "some", "of", "the", "please", "pls", "x", "for", "me", "him", "her", "them", "into", "at"));

    static String lazy(MinecraftServer s, String in) {
        List<String> w = new ArrayList<>(Arrays.asList(in.split("\\s+")));
        String verb = w.get(0).toLowerCase(Locale.ROOT);
        List<String> rest = w.subList(1, w.size());

        switch (verb) {
            case "day": case "night": case "noon": case "midnight":
                if (rest.isEmpty()) return "time set " + verb;
                break;

            case "sun": case "sunny":
                if (rest.isEmpty()) return "weather clear";
                break;
            case "rain": case "storm": case "snow": case "thunder":
                if (rest.isEmpty()) return generic(s, Arrays.asList("weather", verb.equals("thunder") ? "storm" : verb));
                break;
            case "creative": case "survival": case "adventure": case "spectator":
                return gamemode(s, w);

            case "save-all": case "saveall": case "save":
                if (rest.isEmpty()) return "save all";
                break;
            case "save-off": return rest.isEmpty() ? "save off" : null;
            case "save-on": return rest.isEmpty() ? "save on" : null;
        }
        String root = rootCommand(s, verb);
        if (root == null) return null;
        switch (root) {
            case "give": return give(s, rest);
            case "teleport": case "tp": return teleport(s, rest);
            case "gamemode": return gamemode(s, rest);
            case "say": case "me": case "message": case "msg": case "tell": case "w":

                return generic(s, joinFrom(root, rest, root.equals("say") || root.equals("me") ? 0 : 1));

            case "kick": {
                String who = rest.isEmpty() ? null : player(s, rest.get(0));
                if (who == null) return null;
                if (rest.size() == 1) return "kick " + who;
                return "oh kick " + who + " " + String.join(" ", rest.subList(1, rest.size()));
            }
            case "ban":
                if (rest.size() == 1 && !rest.get(0).equalsIgnoreCase("username") && !rest.get(0).equalsIgnoreCase("ip")) {
                    String who = player(s, rest.get(0));
                    return "ban username " + (who != null ? who : rest.get(0));
                }
                return generic(s, joinFrom(root, rest, 1));
            case "heal":

                if (rest.size() == 1) {
                    String who = player(s, rest.get(0));
                    return who == null ? null : "heal " + who + " 32768";
                }
                break;
        }
        List<String> all = new ArrayList<>();
        all.add(root);
        all.addAll(rest);
        return generic(s, all);
    }

    static List<String> joinFrom(String root, List<String> rest, int keep) {
        List<String> out = new ArrayList<>();
        out.add(root);
        for (int i = 0; i < rest.size() && i < keep; i++) out.add(rest.get(i));
        if (rest.size() > keep) out.add(String.join(" ", rest.subList(keep, rest.size())));
        return out;
    }

    static String rootCommand(MinecraftServer s, String verb) {
        switch (verb) {
            case "gm": return "gamemode";
            case "tele": case "warp": return "teleport";
            case "msg": case "tell": case "whisper": case "dm": verb = "msg"; break;
            case "time": case "weather": break;
        }
        List<String> names = new ArrayList<>();
        for (com.mojang.brigadier.tree.CommandNode<CommandSource> n : dispatcher(s).getRoot().getChildren()) names.add(n.getName());
        for (String n : names) if (n.equalsIgnoreCase(verb)) return n;
        if (verb.equals("msg")) for (String n : names) if (n.equals("message") || n.equals("tell") || n.equals("w")) return n;
        String only = null;
        for (String n : names) {
            if (n.toLowerCase(Locale.ROOT).startsWith(verb)) {
                if (only != null) return null;
                only = n;
            }
        }
        return only;
    }

    static List<String> players(MinecraftServer s) {
        List<String> out = new ArrayList<>();
        try {
            for (PlayerServer p : new ArrayList<>(s.playerList.playerEntities)) out.add(p.username);
        } catch (Throwable ignored) { }
        return out;
    }

    static String player(MinecraftServer s, String typed) {
        if (typed == null) return null;
        String t = typed.toLowerCase(Locale.ROOT);
        List<String> on = players(s);
        for (String n : on) if (n.equalsIgnoreCase(t)) return n;
        String only = null;
        for (String n : on) if (n.toLowerCase(Locale.ROOT).startsWith(t)) { if (only != null) return null; only = n; }
        if (only != null) return only;
        if (t.length() < 3) return null;
        for (String n : on) if (n.toLowerCase(Locale.ROOT).contains(t)) { if (only != null) return null; only = n; }
        return only;
    }

    static String give(MinecraftServer s, List<String> words) {
        int amount = -1;
        List<String> ws = new ArrayList<>();
        for (String raw : words) {
            String w = raw.toLowerCase(Locale.ROOT);
            Integer n = amount(w);
            if (n != null && amount < 0) { amount = n; continue; }
            if (w.equals("stack") || w.equals("stacks")) { amount = Math.max(1, amount) * 64; continue; }
            if (FILLER.contains(w)) continue;
            ws.add(raw);
        }
        String who = null, id = null;
        boolean exactWho = false;
        for (int i = 0; i < ws.size(); i++) {
            String p = player(s, ws.get(i));
            if (p == null) continue;
            List<String> rest = new ArrayList<>();
            for (int k = 0; k < ws.size(); k++) if (k != i) rest.add(ws.get(k).toLowerCase(Locale.ROOT));
            if (rest.isEmpty()) continue;
            String it = itemId(rest);
            if (it == null) continue;
            boolean ex = p.equalsIgnoreCase(ws.get(i));
            if (who == null || (ex && !exactWho)) { who = p; id = it; exactWho = ex; }
        }
        if (who == null) {
            List<String> on = players(s);
            if (on.size() != 1 || ws.isEmpty()) return null;
            who = on.get(0);
            List<String> all = new ArrayList<>();
            for (String w : ws) all.add(w.toLowerCase(Locale.ROOT));
            id = itemId(all);
        }
        if (id == null) return null;
        return "give " + who + " " + id + " " + (amount > 0 ? amount : 1);
    }

    static String players(MinecraftServer s, String in) {
        List<String> on = players(s);
        if (on.isEmpty()) return in;
        String[] w = in.split(" ");
        boolean changed = false;
        StringBuilder prefix = new StringBuilder(w[0]);
        for (int i = 1; i < w.length; i++) {
            String word = w[i];
            if (!word.isEmpty() && !on.contains(word)) {
                List<String> here = suggestions(s, prefix + " ");
                boolean namesHere = false;
                for (String p : on) if (here.contains(p)) namesHere = true;
                String p = namesHere ? player(s, word) : null;
                if (p != null && here.contains(p)) { w[i] = p; changed = true; }
            }
            prefix.append(' ').append(w[i]);
        }
        return changed ? String.join(" ", w) : in;
    }

    static Integer amount(String w) {
        String d = w.replaceAll("^[x*]", "").replaceAll("x$", "");
        if (d.isEmpty() || d.length() > 5) return null;
        for (int i = 0; i < d.length(); i++) if (!Character.isDigit(d.charAt(i))) return null;
        int n = Integer.parseInt(d);
        return n > 0 ? n : null;
    }

    static String teleport(MinecraftServer s, List<String> words) {
        List<String> w = new ArrayList<>();
        for (String x : words) if (!FILLER.contains(x.toLowerCase(Locale.ROOT))) w.add(x);
        if (w.isEmpty()) return null;
        String who = player(s, w.get(0));
        if (who == null) return null;
        if (w.size() == 2) {
            if (w.get(1).equalsIgnoreCase("spawn")) {
                net.minecraft.core.world.pos.TilePos sp = s.getDimensionWorld(0).getSpawnPoint();
                return "teleport " + who + " " + sp.x + " " + (sp.y + 1) + " " + sp.z;
            }
            String to = player(s, w.get(1));
            return to == null ? null : "teleport " + who + " " + to;
        }
        if (w.size() == 4) return "teleport " + who + " " + w.get(1) + " " + w.get(2) + " " + w.get(3);
        return null;
    }

    static String gamemode(MinecraftServer s, List<String> words) {
        String mode = null, who = null;
        for (String raw : words) {
            String w = raw.toLowerCase(Locale.ROOT);
            if (FILLER.contains(w)) continue;
            String m = modeWord(s, w);
            if (mode == null && m != null) { mode = m; continue; }
            String p = player(s, raw);
            if (who == null && p != null) { who = p; continue; }
        }
        if (mode == null) return null;
        if (who == null) {
            List<String> on = players(s);
            if (on.size() != 1) return null;
            who = on.get(0);
        }
        return "gamemode " + mode + " " + who;
    }

    static String modeWord(MinecraftServer s, String w) {
        switch (w) {
            case "0": case "s": case "surv": return "survival";
            case "1": case "c": case "crea": return "creative";
            case "2": case "a": case "adv": return "adventure";
            case "3": case "sp": case "spec": return "spectator";
        }
        for (String m : suggestions(s, "gamemode ")) if (m.startsWith(w) && w.length() >= 2) return m;
        return null;
    }

    static String generic(MinecraftServer s, List<String> words) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            String prefix = out.length() == 0 ? "" : out + " ";
            List<String> opts = i == 0 ? rootNames(s) : suggestions(s, prefix);
            String pick = opts.isEmpty() ? w : bestOf(opts, w);
            if (pick == null) {

                String p = player(s, w);
                pick = p != null ? p : w;
            }
            out.append(i == 0 ? "" : " ").append(pick);
        }
        return out.toString();
    }

    static List<String> rootNames(MinecraftServer s) {
        List<String> names = new ArrayList<>();
        for (com.mojang.brigadier.tree.CommandNode<CommandSource> n : dispatcher(s).getRoot().getChildren()) names.add(n.getName());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static List<String> suggestions(MinecraftServer s, String prefix) {
        List<String> out = new ArrayList<>();
        try {
            CommandDispatcher<CommandSource> d = dispatcher(s);
            ParseResults<CommandSource> p = d.parse(prefix, new ConsoleCommandSource(s));
            Suggestions sg = d.getCompletionSuggestions(p, prefix.length()).get(2, java.util.concurrent.TimeUnit.SECONDS);
            Set<String> seen = new LinkedHashSet<>();
            for (Suggestion x : sg.getList()) seen.add(x.getText());
            out.addAll(seen);
        } catch (Throwable ignored) { }
        return out;
    }

    static String bestOf(Collection<String> opts, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        for (String o : opts) if (o.equalsIgnoreCase(t)) return o;
        String only = null;
        int starts = 0;
        for (String o : opts) if (o.toLowerCase(Locale.ROOT).startsWith(t)) { only = o; starts++; }
        if (starts == 1) return only;
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        List<String> q = Arrays.asList(t.split("[_ ]+"));
        for (String o : opts) {
            int sc = matchScore(shortName(o), q);
            if (sc >= 0 && sc < bestScore) { bestScore = sc; best = o; }
        }
        return best;
    }

    static String itemId(List<String> words) {
        String best = null;
        int bestScore = Integer.MAX_VALUE, bestRank = Integer.MAX_VALUE;
        String joined = String.join("_", words);
        for (Item item : Item.itemsList) {
            if (item == null || item.namespaceID == null) continue;
            String id = item.namespaceID.toString();
            String name = shortName(id);
            if (name.equals(joined)) return id;
            int score = matchScore(name, words);
            if (score < 0) continue;
            int rank = formRank(name);
            if (score < bestScore || (score == bestScore && rank < bestRank)) {
                bestScore = score;
                bestRank = rank;
                best = id;
            }
        }
        return best;
    }

    static String shortName(String id) {
        String v = id;
        int c = v.indexOf(':');
        if (c >= 0) v = v.substring(c + 1);
        int sl = v.lastIndexOf('/');
        return (sl >= 0 ? v.substring(sl + 1) : v).toLowerCase(Locale.ROOT);
    }

    static int formRank(String n) {
        for (String derived : new String[]{"brick", "block", "tile", "carved", "polished", "chiseled",
                "pillar", "slab", "stair", "wall", "pane", "fence", "door", "trapdoor", "button"})
            if (n.contains(derived)) return 2;
        if (n.startsWith("ore") || n.endsWith("ore") || n.contains("_ore")) return 1;
        return 0;
    }

    static int matchScore(String name, List<String> query) {
        String[] parts = name.split("[_.]");
        int score = 0;
        for (String q0 : query) {
            String q = normalizeWord(q0);
            if (q.isEmpty()) continue;
            int best = -1;
            for (String part : parts) {
                int c = matchCost(part, q);
                if (c >= 0 && (best < 0 || c < best)) best = c;
            }
            if (best < 0) return -1;
            score += best;
        }
        return score + Math.max(0, parts.length - query.size());
    }

    static String normalizeWord(String w) {
        switch (w) {
            case "wooden": return "wood";
            case "golden": return "gold";
            case "stone": return "stone";
            case "pick": return "pickaxe";
            default: return w;
        }
    }

    static int matchCost(String part, String q) {
        if (part.equals(q)) return 0;
        if (q.length() >= 3 && part.startsWith(q)) return 1;
        if (part.length() >= 3 && q.startsWith(part)) return 1;
        if (stem(part).equals(stem(q))) return 2;
        if (q.length() >= 4 && part.contains(q)) return 3;
        return -1;
    }

    static String stem(String s) {
        for (String suf : new String[]{"ing", "es", "ed", "en", "s"})
            if (s.endsWith(suf) && s.length() - suf.length() >= 3) return s.substring(0, s.length() - suf.length());
        return s;
    }

    public static String usage(MinecraftServer s, String typed) {
        try {
            String in = typed.trim();
            if (in.startsWith("/")) in = in.substring(1).trim();
            if (in.isEmpty()) return null;
            CommandDispatcher<CommandSource> d = dispatcher(s);
            CommandSource src = new ConsoleCommandSource(s);
            ParseResults<CommandSource> p = d.parse(in, src);
            List<com.mojang.brigadier.context.ParsedCommandNode<CommandSource>> nodes = p.getContext().getNodes();
            if (nodes.isEmpty()) {
                String first = in.split("\\s+")[0].toLowerCase(Locale.ROOT);
                String root = rootCommand(s, first);
                if (root != null && !root.equalsIgnoreCase(first)) return "→ " + root;

                List<String> starts = new ArrayList<>();
                for (String n : rootNames(s)) if (n.toLowerCase(Locale.ROOT).startsWith(first)) starts.add(n);
                if (starts.size() > 1)
                    return String.join("  ", starts.subList(0, Math.min(8, starts.size()))) + (starts.size() > 8 ? "  …" : "");
                return "unknown command \"" + first + "\"";
            }
            com.mojang.brigadier.context.ParsedCommandNode<CommandSource> last = nodes.get(nodes.size() - 1);
            String done = in.substring(0, Math.min(in.length(), last.getRange().getEnd()));
            java.util.Map<com.mojang.brigadier.tree.CommandNode<CommandSource>, String> u = d.getSmartUsage(last.getNode(), src);
            if (u.isEmpty()) return done;
            StringBuilder b = new StringBuilder(done).append(' ');
            int n = 0;
            for (String v : u.values()) {
                if (n++ > 0) b.append("  |  ");
                b.append(v);
                if (n >= 3) break;
            }
            return b.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    public static List<String> complete(MinecraftServer s, String typed) {

        try {
            return completeNow(s, typed);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private static List<String> completeNow(MinecraftServer s, String typed) {
        List<String> out = new ArrayList<>();
        if (s == null || typed == null) return out;
        String lead = typed.startsWith("/") ? "/" : "";
        String body = lead.isEmpty() ? typed : typed.substring(1);
        int sp = body.lastIndexOf(' ');
        String prefix = sp >= 0 ? body.substring(0, sp + 1) : "";
        String word = sp >= 0 ? body.substring(sp + 1) : body;
        String lw = word.toLowerCase(Locale.ROOT);
        Set<String> seen = new LinkedHashSet<>();
        for (String o : sp < 0 ? rootNames(s) : suggestions(s, prefix)) {
            String lo = o.toLowerCase(Locale.ROOT);
            if (lo.startsWith(lw) && !lo.equals(lw)) seen.add(o);
            else if (!lw.isEmpty() && lo.contains(":") && shortName(o).startsWith(lw)) seen.add(o);
        }
        if (sp >= 0 && !lw.isEmpty()) {
            for (String p : players(s)) if (p.toLowerCase(Locale.ROOT).startsWith(lw) && !p.equalsIgnoreCase(word)) seen.add(p);
        }
        int n = 0;
        for (String o : seen) {
            out.add(lead + prefix + o);
            if (++n >= 30) break;
        }
        return out;
    }
}
