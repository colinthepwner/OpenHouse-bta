package openhouse.mod.mixin.server;

import net.minecraft.server.gui.ServerGui;
import openhouse.mod.server.ServerRoom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = ServerGui.class, remap = false)
public abstract class ServerGuiMixin {
    @ModifyArg(method = "initGui", at = @At(value = "INVOKE", target = "Ljavax/swing/JFrame;<init>(Ljava/lang/String;)V"), require = 0)
    private static String openhouse$title(String title) {
        return ServerRoom.OPENHOST ? "OpenHost" : title;
    }
}
