package openhouse.mod.mixin;

import net.minecraft.client.gui.server.ServerEntryComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ServerEntryComponent.class, remap = false)
public abstract class ServerEntryComponentMixin {
    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Ljava/lang/StringBuilder;setLength(I)V"), require = 0)
    private void openhouse$cut_only(StringBuilder sb, int n) {
        if (n < sb.length()) sb.setLength(Math.max(n, 0));
    }
}
