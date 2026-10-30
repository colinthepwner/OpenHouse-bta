package openhouse.mod.mixin;

import com.mojang.nbt.tags.CompoundTag;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.server.ScreenSelectServer;
import net.minecraft.client.gui.server.ServerEntry;
import net.minecraft.client.gui.server.ServerManager;
import openhouse.mod.OpenHouseMod;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ServerManager.class, remap = false)
public abstract class ServerManagerMixin {
    @Shadow @Final private Map<UUID, ServerEntry> historyMap;
    @Shadow @Final private List<ServerEntry> history;

    @Shadow public abstract void checkValid(ServerEntry entry);

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void openhouse$dropLoadedRooms(ScreenSelectServer screen, CompoundTag tag, CallbackInfo ci) {

        for (ServerEntry e : openhouse$dropRooms()) checkValid(e);
    }

    @Inject(method = "toTag", at = @At("HEAD"), require = 0)
    private void openhouse$dropRoomsBeforeSave(CallbackInfoReturnable<CompoundTag> cir) {
        openhouse$dropRooms();
    }

    @Unique
    private List<ServerEntry> openhouse$dropRooms() {
        List<ServerEntry> dropped = new java.util.ArrayList<>();
        for (Iterator<ServerEntry> it = history.iterator(); it.hasNext(); ) {
            ServerEntry e = it.next();
            if (e != null && OpenHouseMod.isRoomEntry(e.baseName, e.motd, e.address)) {
                it.remove();
                historyMap.remove(e.uuid);
                dropped.add(e);
            }
        }
        return dropped;
    }
}
