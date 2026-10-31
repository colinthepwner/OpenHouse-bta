package openhouse.mod;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.data.registry.recipe.RecipeEntryBase;
import net.minecraft.core.data.registry.recipe.RecipeGroup;
import net.minecraft.core.data.registry.recipe.RecipeNamespace;
import net.minecraft.core.data.registry.recipe.RecipeRegistry;
import net.minecraft.core.data.registry.recipe.RecipeSymbol;

public final class ServerRecipes {
    private ServerRecipes() { }

    private static final Map<String, Map<String, RecipeSymbol>> KNOWN = new LinkedHashMap<>();

    private static final Set<String> TOLD = new HashSet<>();

    public static synchronized void remember(RecipeRegistry r) {
        if (r == null) return;
        try {
            for (String ns : r.keySet()) {
                RecipeNamespace n = r.getItem(ns);
                if (n == null) continue;
                Map<String, RecipeSymbol> groups = KNOWN.computeIfAbsent(ns, k -> new LinkedHashMap<>());
                for (String g : n.keySet()) {
                    RecipeGroup<?> group = n.getItem(g);
                    if (group != null) groups.putIfAbsent(g, group.getMachine());
                }
            }
        } catch (Throwable t) {
            OpenHouseMod.LOG.warn("couldn't look at this game's recipe list before joining: {}", t.toString());
        }
    }

    public static void add(RecipeRegistry r, String key, RecipeEntryBase<?, ?, ?> recipe) {
        try {
            ensureGroup(r, key);
        } catch (Throwable t) {

            OpenHouseMod.LOG.warn("couldn't check the place for server recipe {}: {}", key, t.toString());
        }
        try {
            r.addCustomRecipe(key, recipe);
        } catch (IllegalArgumentException e) {

            String where = key.contains("/") ? key.substring(0, key.indexOf('/')) : key;
            if (tell("skip " + where))
                OpenHouseMod.LOG.warn("left out the server's recipes in {} (this game has no {} for them: {})", where, where.contains(":") ? "recipe group" : "recipe namespace", e.getMessage());
        }
    }

    private static synchronized void ensureGroup(RecipeRegistry r, String key) {
        int colon = key.indexOf(':');
        int slash = key.indexOf('/', colon + 1);
        if (colon <= 0 || slash <= colon + 1) return;
        String ns = key.substring(0, colon);
        String g = key.substring(colon + 1, slash);
        RecipeNamespace n = r.getItem(ns);
        if (n != null && n.getItem(g) != null) return;
        Map<String, RecipeSymbol> had = KNOWN.get(ns);
        if (had == null || !had.containsKey(g)) return;
        if (n == null) {
            n = new RecipeNamespace();
            r.register(ns, n);
            if (tell("ns " + ns))
                OpenHouseMod.LOG.info("made recipe namespace '{}' again for the server's recipes (its mod only makes it when the game starts)", ns);
        }

        n.register(g, new RecipeGroup<>(had.get(g)));
        if (tell("group " + ns + ":" + g))
            OpenHouseMod.LOG.info("made recipe group '{}:{}' again for the server's recipes", ns, g);
    }

    private static synchronized boolean tell(String what) {
        return TOLD.add(what);
    }
}
