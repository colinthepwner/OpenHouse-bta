package openhouse.mod;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.concurrent.locks.Lock;
import net.minecraft.core.world.chunk.ChunkLoaderRegionAsync;
import net.minecraft.core.world.save.mcregion.RegionFileCache;

public final class RegionFiles {
    private RegionFiles() { }

    public static void release() {
        waitForChunkSaves();
        try {
            RegionFileCache.flushCache();
        } catch (Throwable t) {
            OpenHouseMod.LOG.warn("couldn't close the world's region files: {}", t.toString());
        }
    }

    private static void waitForChunkSaves() {
        try {
            Field q = ChunkLoaderRegionAsync.class.getDeclaredField("chunksToSave");
            Field l = ChunkLoaderRegionAsync.class.getDeclaredField("lockChunksToSave");
            q.setAccessible(true);
            l.setAccessible(true);
            Collection<?> queue = (Collection<?>) q.get(null);
            Lock lock = (Lock) l.get(null);

            long until = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < until) {
                lock.lock();
                try {

                    if (queue.isEmpty()) return;
                } finally {
                    lock.unlock();
                }
                Thread.sleep(2);
            }
            OpenHouseMod.LOG.warn("the chunk save thread is still busy after 30 s - closing the region files anyway");
        } catch (Throwable t) {
            OpenHouseMod.LOG.warn("can't see BTA's chunk save queue ({}) - waiting a moment before closing the region files", t.toString());
            try { Thread.sleep(250); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }
}
