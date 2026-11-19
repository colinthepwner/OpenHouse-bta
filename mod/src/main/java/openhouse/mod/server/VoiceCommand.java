package openhouse.mod.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilderLiteral;
import com.mojang.brigadier.builder.ArgumentBuilderRequired;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.net.command.CommandManager;
import net.minecraft.core.net.command.CommandSource;
import net.minecraft.core.net.command.arguments.ArgumentTypeEntity;
import net.minecraft.core.net.command.helpers.EntitySelector;
import net.minecraft.server.MinecraftServer;

public final class VoiceCommand implements CommandManager.CommandRegistry {
    @Override
    public void register(CommandDispatcher<CommandSource> d) {
        d.register(tree("mute", true));
        d.register(tree("unmute", false));
    }

    private static ArgumentBuilderLiteral<CommandSource> tree(String name, boolean m) {
        return (ArgumentBuilderLiteral<CommandSource>) ArgumentBuilderLiteral.<CommandSource>literal(name)
                .executes(c -> list(c.getSource()))
                .then(ArgumentBuilderRequired.<CommandSource, EntitySelector>argument("players", ArgumentTypeEntity.usernames())
                        .executes(c -> mine(c, m))
                        .then(((ArgumentBuilderRequired<CommandSource, EntitySelector>) ArgumentBuilderRequired.<CommandSource, EntitySelector>argument("listeners", ArgumentTypeEntity.usernames())
                                .requires(RoomCommand::owner))
                                .executes(c -> theirs(c, m))));
    }

    private static List<String> names(CommandContext<CommandSource> c, String arg) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        List<String> out = new ArrayList<>();
        for (Entity e : c.getArgument(arg, EntitySelector.class).get(c.getSource()))
            if (e instanceof Player && ((Player) e).username != null) out.add(((Player) e).username);
        return out;
    }

    private static int list(CommandSource s) {
        Player me = s.getSender();
        if (me == null) { s.sendMessage("Voice mutes: /mute <player> (just you), /mute <player> @a (ops: everybody)."); return 1; }
        Set<String> mine = VoiceRelay.muted_by(me.username);
        s.sendMessage(mine.isEmpty() ? "You haven't muted anyone. /mute <player> stops you hearing them." : "You've muted: " + String.join(", ", mine) + ".  /unmute <player> to hear them again.");
        if (VoiceRelay.muted_for_all(me.username)) s.sendMessage("An op has muted you, nobody hears your voice.");
        return 1;
    }

    private static int mine(CommandContext<CommandSource> c, boolean m) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSource s = c.getSource();
        Player me = s.getSender();
        if (me == null) { s.sendMessage("From the console, say who stops hearing them: /" + (m ? "mute" : "unmute") + " <player> @a"); return 0; }
        List<String> who = names(c, "players");
        who.removeIf(n -> n.equalsIgnoreCase(me.username));
        if (who.isEmpty()) { s.sendMessage(m ? "Nobody to mute (to mute your own mic, use the mute key or the mic button in the pause menu)." : "Nobody to unmute."); return 0; }
        for (String n : who) VoiceRelay.set_mute(me.username, n, m);
        s.sendMessage((m ? "Muted " : "Unmuted ") + String.join(", ", who) + (m ? ", just for you." : "."));
        return who.size();
    }

    private static int theirs(CommandContext<CommandSource> c, boolean m) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSource s = c.getSource();
        List<String> who = names(c, "players");
        if (who.isEmpty()) { s.sendMessage("No such player online."); return 0; }
        String[] typed = c.getInput().trim().split("\\s+");
        boolean all = typed.length >= 3 && typed[2].startsWith("@a");
        MinecraftServer srv = MinecraftServer.getInstance();
        if (all) {
            for (String n : who) {
                VoiceRelay.set_mute(VoiceRelay.ALL, n, m);

                if (!m) for (net.minecraft.server.entity.player.PlayerServer p : VoiceRelay.players(srv)) VoiceRelay.set_mute(p.username, n, false);
                if (srv != null) VoiceRelay.tell_state(srv, n);
            }
            s.sendMessage((m ? "Muted " : "Unmuted ") + String.join(", ", who) + (m ? " for everybody." : " for everybody."));
            if (srv != null) for (String n : who) for (net.minecraft.server.entity.player.PlayerServer p : VoiceRelay.players(srv))
                if (p.username != null && p.username.equalsIgnoreCase(n)) s.sendMessage(p, m ? "An op muted your voice chat, nobody hears you." : "An op unmuted your voice chat.");
            return who.size();
        }
        List<String> to = names(c, "listeners");
        int k = 0;
        for (String n : who) for (String l : to) if (!l.equalsIgnoreCase(n)) { VoiceRelay.set_mute(l, n, m); k++; }
        s.sendMessage((m ? "Muted " : "Unmuted ") + String.join(", ", who) + " for " + String.join(", ", to) + ".");
        return k;
    }
}
