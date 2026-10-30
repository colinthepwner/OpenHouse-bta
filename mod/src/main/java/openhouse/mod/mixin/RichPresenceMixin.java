package openhouse.mod.mixin;

import de.jcm.discordgamesdk.ActivityManager;
import de.jcm.discordgamesdk.CreateParams;
import de.jcm.discordgamesdk.DiscordEventAdapter;
import de.jcm.discordgamesdk.activity.Activity;
import net.minecraft.client.discord.RichPresenceHandlerThread;
import openhouse.mod.OpenHouseDiscord;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = RichPresenceHandlerThread.class, remap = false)
public abstract class RichPresenceMixin {
    @ModifyArg(method = "createCore", at = @At(value = "INVOKE", target = "Lde/jcm/discordgamesdk/Core;<init>(Lde/jcm/discordgamesdk/CreateParams;)V"), require = 0)
    private CreateParams openhouse$joinHandler(CreateParams params) {
        params.registerEventHandler(new DiscordEventAdapter() {
            @Override public void onActivityJoin(String secret) { OpenHouseDiscord.join(secret); }
        });
        return params;
    }

    @Redirect(method = "updateActivity", at = @At(value = "INVOKE", target = "Lde/jcm/discordgamesdk/ActivityManager;updateActivity(Lde/jcm/discordgamesdk/activity/Activity;)V"), require = 0)
    private void openhouse$roomInPresence(ActivityManager manager, Activity activity) {
        OpenHouseDiscord.decorate(activity);
        manager.updateActivity(activity);
    }
}
