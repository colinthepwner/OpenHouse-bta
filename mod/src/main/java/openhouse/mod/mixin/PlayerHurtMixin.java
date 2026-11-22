package openhouse.mod.mixin;

import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.data.gamerule.GameRules;
import net.minecraft.core.entity.player.Player;
import openhouse.mod.voice.VoiceWire;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Player.class, remap = false)
public abstract class PlayerHurtMixin {
    @Inject(method = "playHurtSound", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$customHurt(CallbackInfo ci) { openhouse$play(ci, false); }

    @Inject(method = "playDeathSound", at = @At("HEAD"), cancellable = true, require = 0)
    private void openhouse$customDeath(CallbackInfo ci) { openhouse$play(ci, true); }

    @org.spongepowered.asm.mixin.Unique
    private void openhouse$play(CallbackInfo ci, boolean death) {
        Player p = (Player) (Object) this;
        VoiceWire.HurtHook h = VoiceWire.hurt, sp = VoiceWire.local_hurt;
        if (h == null && sp == null || p.world == null || p.world.isClientSide) return;
        try {
            float base = p.world.getGameRuleValue(GameRules.DWARF_MODE) ? 2.0F : 1.0F;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            float pitch = (r.nextFloat() - r.nextFloat()) * 0.2F + base;

            if (h != null && h.play(p, pitch, death) || sp != null && sp.play(p, pitch, death)) ci.cancel();
        } catch (Throwable ignored) { }
    }
}
