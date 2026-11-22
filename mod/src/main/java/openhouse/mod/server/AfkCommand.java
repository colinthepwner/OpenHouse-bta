package openhouse.mod.server;

import com.mojang.brigadier.arguments.ArgumentTypeInteger;
import com.mojang.brigadier.builder.ArgumentBuilderLiteral;
import com.mojang.brigadier.builder.ArgumentBuilderRequired;
import net.minecraft.core.data.gamerule.GameRuleBoolean;
import net.minecraft.core.net.command.CommandSource;
import net.minecraft.core.net.packet.PacketGameRule;
import net.minecraft.core.world.World;
import net.minecraft.server.MinecraftServer;
import openhouse.mod.voice.VoiceWire;

final class AfkCommand {
    private AfkCommand() { }

    static ArgumentBuilderLiteral<CommandSource> tree() {
        return (ArgumentBuilderLiteral<CommandSource>) ArgumentBuilderLiteral.<CommandSource>literal("afk").requires(RoomCommand::owner)
                .executes(c -> status(c.getSource()))
                .then(num("idle", false))
                .then(num("kick", true))
                .then(rule("fade", VoiceWire.AFK_FADE))
                .then(rule("mobs", VoiceWire.AFK_MOBS));
    }

    private static ArgumentBuilderLiteral<CommandSource> num(String word, boolean kick) {
        return (ArgumentBuilderLiteral<CommandSource>) ArgumentBuilderLiteral.<CommandSource>literal(word)
                .then(ArgumentBuilderRequired.<CommandSource, Integer>argument("seconds", ArgumentTypeInteger.integer(0, 86400))
                        .executes(c -> {
                            int s = c.getArgument("seconds", Integer.class);
                            if (kick) AfkServer.kick_s = s; else AfkServer.idle_s = s;
                            AfkServer.save();
                            return status(c.getSource());
                        }));
    }

    private static ArgumentBuilderLiteral<CommandSource> rule(String word, GameRuleBoolean r) {
        return (ArgumentBuilderLiteral<CommandSource>) ArgumentBuilderLiteral.<CommandSource>literal(word)
                .then(ArgumentBuilderLiteral.<CommandSource>literal("on").executes(c -> set(c.getSource(), r, true)))
                .then(ArgumentBuilderLiteral.<CommandSource>literal("off").executes(c -> set(c.getSource(), r, false)));
    }

    private static int set(CommandSource src, GameRuleBoolean r, boolean on) {
        World w = world();
        if (w == null) return 0;
        w.getLevelData().getGameRules().setValue(r, on);
        src.sendPacketToAllPlayers(() -> new PacketGameRule(w.getLevelData().getGameRules()));
        AfkServer.hello();
        return status(src);
    }

    static World world() { MinecraftServer s = MinecraftServer.getInstance(); return s == null ? null : s.getDimensionWorld(0); }

    static boolean rule_on(GameRuleBoolean r) { World w = world(); return w == null || !Boolean.FALSE.equals(w.getLevelData().getGameRules().getValue(r)); }

    private static int status(CommandSource src) {
        int i = AfkServer.idle_s, k = AfkServer.kick_s;
        src.sendMessage("AFK: " + (i > 0 ? "after " + i + " s without input" : "only when tabbed off or paused") + ", "
                + (k > 0 ? "disconnected after " + k + " s" : "never disconnected") + ", fade " + (rule_on(VoiceWire.AFK_FADE) ? "on" : "off")
                + ", mobs ignore them " + (rule_on(VoiceWire.AFK_MOBS) ? "on" : "off") + ".");
        src.sendMessage("/oh afk idle|kick <seconds>, /oh afk fade|mobs on|off");
        return 1;
    }
}
