package openhouse.mod;

import java.util.Map;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.world.World;

public final class LostDim {
    private LostDim() { }

    public static boolean has(int dim) {
        try {
            Object m = net.minecraft.core.world.Dimension.class.getMethod("getDimensionList").invoke(null);
            return !(m instanceof Map) || ((Map<?, ?>) m).get(Integer.valueOf(dim)) != null;
        } catch (Throwable t) { return true; }
    }

    public static void to_spawn(Entity p, int was) {
        World w = p.world;
        if (w != null) try {

            Object sp = World.class.getMethod("getSpawnPoint").invoke(w);
            int x = sp.getClass().getField("x").getInt(sp), y = sp.getClass().getField("y").getInt(sp), z = sp.getClass().getField("z").getInt(sp);
            int top = w.findTopSolidBlock(x, z);
            p.moveTo(x + 0.5, top > 0 ? top : y, z + 0.5, p.yRot, p.xRot);
        } catch (Throwable t) {
            MissingKeep.LOG.warn("couldn't find the spawn for a player out of a missing dimension", t);
        }
        MissingKeep.LOG.info("a player was in dimension " + was + ", which no mod here adds: moved to the overworld spawn");
    }
}
