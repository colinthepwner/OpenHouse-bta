package openhouse.mod.server;

import openhouse.Rtc;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentTypeString;
import com.mojang.brigadier.builder.ArgumentBuilderLiteral;
import com.mojang.brigadier.builder.ArgumentBuilderRequired;
import net.minecraft.core.net.command.CommandManager;
import net.minecraft.core.net.command.CommandSource;

public final class RoomCommand implements CommandManager.CommandRegistry {
    @Override
    public void register(CommandDispatcher<CommandSource> d) {
        ServerRoom room = ServerRoom.get();
        d.register(tree("oh", room));
        d.register(tree("roomcode", room));
    }

    private static ArgumentBuilderLiteral<CommandSource> tree(String name, ServerRoom room) {
        return (ArgumentBuilderLiteral<CommandSource>) ArgumentBuilderLiteral.<CommandSource>literal(name)
                .executes(c -> { status(c.getSource(), room); return 1; })
                .then(ArgumentBuilderLiteral.<CommandSource>literal("new").requires(CommandSource::hasAdmin)
                        .executes(c -> {
                            room.changeCode("");
                            c.getSource().sendMessage("Getting a new room code - the old one stops working. /oh shows it in a moment.");
                            return 1;
                        }))
                .then(ArgumentBuilderLiteral.<CommandSource>literal("set").requires(CommandSource::hasAdmin)
                        .then(ArgumentBuilderRequired.<CommandSource, String>argument("code", ArgumentTypeString.word())
                                .executes(c -> {
                                    String typed = ArgumentTypeString.getString(c, "code");
                                    String code = Rtc.normalizeCode(typed);
                                    if (code == null) {
                                        c.getSource().sendMessage("\"" + typed + "\" can't be a room code: 5 letters/digits, no 0, 1, I or O.");
                                        return 0;
                                    }
                                    room.changeCode(code);
                                    c.getSource().sendMessage("Room code set to " + code + " (if nobody else holds it). /oh shows when it's live.");
                                    return 1;
                                })))
                .then(ArgumentBuilderLiteral.<CommandSource>literal("on").requires(CommandSource::hasAdmin)
                        .executes(c -> {
                            room.setEnabled(true);
                            c.getSource().sendMessage("Room codes on. /oh shows the code in a moment.");
                            return 1;
                        }))
                .then(ArgumentBuilderLiteral.<CommandSource>literal("off").requires(CommandSource::hasAdmin)
                        .executes(c -> {
                            room.setEnabled(false);
                            c.getSource().sendMessage("Room codes off. Players already in stay connected until they leave.");
                            return 1;
                        }));
    }

    static void status(CommandSource s, ServerRoom room) {
        if (!room.enabled) {
            s.sendMessage("Room codes are off" + (s.hasAdmin() ? " (/oh on)." : "."));
            return;
        }
        String code = room.liveCode;
        if (code == null) {
            s.sendMessage("The room is starting (or can't reach the matchmaking server) - try again in a moment.");
            return;
        }
        s.sendMessage("Room code: " + code + "  - join with Multiplayer > Room Code, or the OpenHouse helper.");
        int n = room.players();
        if (n > 0) s.sendMessage(n + (n == 1 ? " player is" : " players are") + " connected through the room.");
    }
}
