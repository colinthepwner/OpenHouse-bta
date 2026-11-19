package openhouse.mod.mixin.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.sound.SoundTypes;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MinecraftServer.class, remap = false)
public abstract class ModSoundsMixin {
    private static final Logger LOG = LoggerFactory.getLogger("OpenHouse");

    @Inject(method = "startServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/sound/SoundTypes;registerSounds()V", shift = At.Shift.AFTER), require = 0)
    private void openhouse$modSounds(CallbackInfoReturnable<Boolean> cir) {
        int cnt = 0, mods = 0;
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            for (Path root : c.getRootPaths()) {
                Path assets = root.resolve("assets");
                if (!Files.isDirectory(assets)) continue;
                try (Stream<Path> nss = Files.list(assets)) {
                    for (Path ns : (Iterable<Path>) nss::iterator) {
                        String name = ns.getFileName().toString().replace("/", "");
                        if (name.equals("minecraft")) continue;
                        Path json = ns.resolve("sounds").resolve("sounds.json");
                        if (!Files.isRegularFile(json)) continue;
                        int got = add_sounds(name, json);
                        if (got > 0) { cnt += got; mods++; }
                    }
                } catch (Throwable t) {
                    LOG.warn("couldn't read sounds of {}: {}", c.getMetadata().getId(), t.toString());
                }
            }
        }
        if (cnt > 0) LOG.info("registered {} mod sounds from {} namespaces for the server", cnt, mods);
    }

    private static int add_sounds(String ns, Path json) throws Exception {
        JsonObject o;
        try (Reader r = new InputStreamReader(Files.newInputStream(json), StandardCharsets.UTF_8)) {
            o = JsonParser.parseReader(r).getAsJsonObject();
        }
        int n = 0;
        for (String k : o.keySet()) {
            String full = ns + ":" + k;
            if (full.length() > 128) continue;
            if (SoundTypes.register(full)) n++;
        }
        return n;
    }
}
