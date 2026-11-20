package openhouse.mod.server;

import com.mojang.brigadier.builder.ArgumentBuilderLiteral;
import com.mojang.brigadier.builder.ArgumentBuilderRequired;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.net.command.CommandSource;
import net.minecraft.core.net.command.arguments.ArgumentTypeEntity;
import net.minecraft.core.net.command.helpers.EntitySelector;
import net.minecraft.core.net.packet.PacketGameRule;
import net.minecraft.core.world.World;
import net.minecraft.server.MinecraftServer;
import openhouse.mod.voice.VoiceWire;

final class LocatorCommand {
    private LocatorCommand() { }

    static ArgumentBuilderLiteral<CommandSource> tree() {
        return (ArgumentBuilderLiteral<CommandSource>) ArgumentBuilderLiteral.<CommandSource>literal("locator").requires(RoomCommand::owner)
                .executes(c -> status(c.getSource()))
                .then(each("on", Boolean.TRUE))
                .then(each("off", Boolean.FALSE))
                .then(ArgumentBuilderLiteral.<CommandSource>literal("default")
                        .then(ArgumentBuilderRequired.<CommandSource, EntitySelector>argument("players", ArgumentTypeEntity.usernames())
                                .executes(c -> per_player(c, null))));
    }

    private static ArgumentBuilderLiteral<CommandSource> each(String word, Boolean v) {
        return (ArgumentBuilderLiteral<CommandSource>) ArgumentBuilderLiteral.<CommandSource>literal(word)
                .executes(c -> rule(c.getSource(), v))
                .then(ArgumentBuilderRequired.<CommandSource, EntitySelector>argument("players", ArgumentTypeEntity.usernames())
                        .executes(c -> per_player(c, v)));
    }

    static boolean rule_on(MinecraftServer s) {
        World w = s == null ? null : s.getDimensionWorld(0);
        return w == null || !Boolean.FALSE.equals(w.getLevelData().getGameRules().getValue(VoiceWire.LOCATOR));
    }

    static int rule(CommandSource src, boolean on) {
        MinecraftServer s = MinecraftServer.getInstance();
        World w = s == null ? null : s.getDimensionWorld(0);
        if (w == null) return 0;
        w.getLevelData().getGameRules().setValue(VoiceWire.LOCATOR, on);
        src.sendPacketToAllPlayers(() -> new PacketGameRule(w.getLevelData().getGameRules()));
        Map<String, Boolean> per = VoiceRelay.locator_all();
        long other = per.values().stream().filter(b -> b != on).count();
        src.sendMessage("Player locator " + (on ? "on" : "off") + " for everyone." + (other > 0 ? " (" + other + " player" + (other == 1 ? " has" : "s have") + " their own setting: /oh locator to see.)" : ""));
        return 1;
    }

    private static int per_player(CommandContext<CommandSource> c, Boolean v) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        List<String> who = new ArrayList<>();
        for (Entity e : c.getArgument("players", EntitySelector.class).get(c.getSource()))
            if (e instanceof Player && ((Player) e).username != null) who.add(((Player) e).username);
        if (who.isEmpty()) { c.getSource().sendMessage("Nobody matched."); return 0; }
        MinecraftServer s = MinecraftServer.getInstance();
        for (String n : who) { VoiceRelay.set_locator(n, v); VoiceRelay.tell_state(s, n); }
        String names = String.join(", ", who);
        c.getSource().sendMessage(v == null ? "Player locator for " + names + " follows the game rule again (" + (rule_on(s) ? "on" : "off") + ")."
                : "Player locator " + (v ? "on" : "off") + " for " + names + ".");
        return who.size();
    }

    private static int status(CommandSource src) {
        MinecraftServer s = MinecraftServer.getInstance();
        List<String> on = new ArrayList<>(), off = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : VoiceRelay.locator_all().entrySet()) (e.getValue() ? on : off).add(e.getKey());
        src.sendMessage("Player locator: " + (rule_on(s) ? "on" : "off") + " for everyone (game rule showPlayerLocator)."
                + (off.isEmpty() ? "" : " Off for " + String.join(", ", off) + ".") + (on.isEmpty() ? "" : " On for " + String.join(", ", on) + "."));
        src.sendMessage("/oh locator on|off [players], /oh locator default <players>");
        return 1;
    }
}
