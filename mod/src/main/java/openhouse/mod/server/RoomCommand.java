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

    static boolean owner(CommandSource s) {
        return s.hasAdmin() || ServerRoom.isLanHost(s);
    }

    private static ArgumentBuilderLiteral<CommandSource> tree(String name, ServerRoom room) {
        return (ArgumentBuilderLiteral<CommandSource>) ArgumentBuilderLiteral.<CommandSource>literal(name)
                .executes(c -> { status(c.getSource(), room); return 1; })
                .then(ArgumentBuilderLiteral.<CommandSource>literal("new").requires(RoomCommand::owner)
                        .executes(c -> {
                            room.changeCode("");
                            c.getSource().sendMessage("Getting a new room code - the old one stops working. /oh shows it in a moment.");
                            return 1;
                        }))
                .then(ArgumentBuilderLiteral.<CommandSource>literal("set").requires(RoomCommand::owner)
                        .then(ArgumentBuilderRequired.<CommandSource, String>argument("code", ArgumentTypeString.word())
                                .executes(c -> {
                                    String typed = ArgumentTypeString.getString(c, "code");
                                    String code = Rtc.normalizeCode(typed);

                                    if (code == null || !Rtc.isPicked(code)) {
                                        c.getSource().sendMessage("\"" + typed + "\" can't be a room code: use " + Rtc.PICKED_MIN + " to " + Rtc.PICKED_MAX + " letters/digits, like MYWORLD.");
                                        return 0;
                                    }
                                    room.changeCode(code);
                                    c.getSource().sendMessage("Room code set to " + code + ". /oh shows when it's live - or tells you if another server already uses it.");
                                    return 1;
                                })))
                .then(ArgumentBuilderLiteral.<CommandSource>literal("on").requires(RoomCommand::owner)
                        .executes(c -> {
                            room.setEnabled(true);
                            c.getSource().sendMessage("Room codes on. /oh shows the code in a moment.");
                            return 1;
                        }))
                .then(ArgumentBuilderLiteral.<CommandSource>literal("off").requires(RoomCommand::owner)
                        .executes(c -> {
                            room.setEnabled(false);
                            c.getSource().sendMessage("Room codes off. Players already in stay connected until they leave.");
                            return 1;
                        }))

                .then(ArgumentBuilderLiteral.<CommandSource>literal("password").requires(RoomCommand::owner)
                        .executes(c -> {
                            c.getSource().sendMessage(room.password.isEmpty()
                                    ? "No password - anyone with the code can join. Set one: /oh password <password>"
                                    : "A password is set. Change it: /oh password <new>, or remove it: /oh password off");
                            return 1;
                        })
                        .then(ArgumentBuilderLiteral.<CommandSource>literal("off").executes(c -> {
                            room.setPassword("");
                            ServerRoom.LOG.info("the server password was removed");
                            c.getSource().sendMessage("Password removed - anyone with the code can join.");
                            return 1;
                        }))
                        .then(ArgumentBuilderRequired.<CommandSource, String>argument("password", ArgumentTypeString.greedyString())
                                .executes(c -> {
                                    String pw = ArgumentTypeString.getString(c, "password");
                                    if (pw.trim().isEmpty()) { c.getSource().sendMessage("A password can't be empty - /oh password off removes it."); return 0; }
                                    room.setPassword(pw);

                                    ServerRoom.LOG.info("the server password was changed");
                                    c.getSource().sendMessage("Password set. Players type it once when they join; anyone already on stays.");
                                    return 1;
                                })))
                .then(ArgumentBuilderLiteral.<CommandSource>literal("whitelist").requires(RoomCommand::owner)
                        .executes(c -> { whitelistStatus(c.getSource(), room); return 1; })
                        .then(ArgumentBuilderLiteral.<CommandSource>literal("on").executes(c -> {
                            room.setWhitelist(true);
                            c.getSource().sendMessage("Whitelist on - only listed players (and ops) can join. " + listLine(room));
                            return 1;
                        }))
                        .then(ArgumentBuilderLiteral.<CommandSource>literal("off").executes(c -> {
                            room.setWhitelist(false);
                            c.getSource().sendMessage("Whitelist off - anyone with the code can join.");
                            return 1;
                        }))
                        .then(ArgumentBuilderLiteral.<CommandSource>literal("list").executes(c -> {
                            c.getSource().sendMessage(listLine(room));
                            return 1;
                        }))
                        .then(ArgumentBuilderLiteral.<CommandSource>literal("add")
                                .then(ArgumentBuilderRequired.<CommandSource, String>argument("name", ArgumentTypeString.word())
                                        .executes(c -> {
                                            String n = ArgumentTypeString.getString(c, "name");
                                            if (!validName(n)) { c.getSource().sendMessage("\"" + n + "\" can't be a player name."); return 0; }
                                            boolean added = room.whitelistAdd(n);
                                            c.getSource().sendMessage(added ? n + " is on the whitelist." + (room.whitelist ? "" : " (It's off - /oh whitelist on)") : n + " was already on the whitelist.");
                                            return 1;
                                        })))
                        .then(ArgumentBuilderLiteral.<CommandSource>literal("remove")
                                .then(ArgumentBuilderRequired.<CommandSource, String>argument("name", ArgumentTypeString.word())
                                        .executes(c -> {
                                            String n = ArgumentTypeString.getString(c, "name");
                                            boolean removed = room.whitelistRemove(n);
                                            c.getSource().sendMessage(removed ? n + " is off the whitelist." + (room.whitelist ? " If they're playing, they stay until they leave." : "") : n + " wasn't on the whitelist.");
                                            return 1;
                                        }))))

                .then(ArgumentBuilderLiteral.<CommandSource>literal("memory").requires(s -> owner(s) && ServerRoom.OPENHOST)
                        .executes(c -> {
                            String v = openhouse.mod.OpenHost.memoryValue(HostUi.read(openhouse.mod.OpenHost.MEMORY_KEY, "auto"));
                            c.getSource().sendMessage("Memory: " + (v == null ? "automatic" : v.replace("G", " GB").replace("M", " MB"))
                                    + " (this run has " + HostWindow.gb(Runtime.getRuntime().maxMemory()) + "). Change it: /oh memory <4G | 2048M | auto>");
                            return 1;
                        })
                        .then(ArgumentBuilderRequired.<CommandSource, String>argument("size", ArgumentTypeString.word())
                                .executes(c -> {
                                    String typed = ArgumentTypeString.getString(c, "size");
                                    String v = typed.equalsIgnoreCase("auto") ? "auto" : openhouse.mod.OpenHost.memoryValue(typed);
                                    if (v == null) { c.getSource().sendMessage("\"" + typed + "\" isn't a memory size - like 4G or 2048M (512M at least), or auto."); return 0; }
                                    HostUi.write(openhouse.mod.OpenHost.MEMORY_KEY, v);
                                    c.getSource().sendMessage("Memory: " + (v.equals("auto") ? "automatic" : v.replace("G", " GB").replace("M", " MB")) + " from the next start - /oh restart to use it now.");
                                    return 1;
                                })))

                .then(ArgumentBuilderLiteral.<CommandSource>literal("restart").requires(s -> owner(s) && ServerRoom.OPENHOST)
                        .executes(c -> {
                            c.getSource().sendMessage("Restarting - the world is saved first; everyone can join again in a minute.");
                            ServerRoom.restart(net.minecraft.server.MinecraftServer.getInstance());
                            return 1;
                        }))

                .then(ArgumentBuilderLiteral.<CommandSource>literal("ui").requires(s -> s.hasAdmin() && ServerRoom.OPENHOST)
                        .executes(c -> {
                            c.getSource().sendMessage("OpenHost's window: " + HostUi.read(HostUi.KEY, "modern") + " (oh ui modern | classic | console)");
                            return 1;
                        })
                        .then(ArgumentBuilderLiteral.<CommandSource>literal("modern").executes(c -> { HostUi.switchTo("modern", c.getSource()); return 1; }))
                        .then(ArgumentBuilderLiteral.<CommandSource>literal("classic").executes(c -> { HostUi.switchTo("classic", c.getSource()); return 1; }))
                        .then(ArgumentBuilderLiteral.<CommandSource>literal("console").executes(c -> { HostUi.switchTo("console", c.getSource()); return 1; })));
    }

    static boolean validName(String n) {
        return n != null && n.matches("[A-Za-z0-9_]{1,16}");
    }

    static String listLine(ServerRoom room) {
        java.util.List<String> names = room.whitelisted();
        return names.isEmpty() ? "Nobody is on the whitelist yet: /oh whitelist add <name>" : "On the whitelist: " + String.join(", ", names);
    }

    static void whitelistStatus(CommandSource s, ServerRoom room) {
        s.sendMessage("Whitelist " + (room.whitelist ? "on" : "off") + ". " + listLine(room));
        s.sendMessage("/oh whitelist on | off | add <name> | remove <name>");
    }

    static void status(CommandSource s, ServerRoom room) {
        if (!room.enabled) {
            s.sendMessage("Room codes are off" + (s.hasAdmin() ? " (/oh on)." : "."));
            return;
        }
        String code = room.liveCode;
        String taken = room.takenCode;
        if (code == null && taken != null) {
            s.sendMessage("Room code " + taken + " is taken - another server is using it." + (s.hasAdmin() ? " Pick another: /oh set <name>" : ""));
            return;
        }
        if (code == null) {
            s.sendMessage("The room is starting (or can't reach the matchmaking server) - try again in a moment.");
            return;
        }
        s.sendMessage("Room code: " + code + "  - join with Multiplayer > Room Code, or the OpenHouse helper.");
        int n = room.players();
        if (n > 0) s.sendMessage(n + (n == 1 ? " player is" : " players are") + " connected through the room.");
        if (owner(s)) s.sendMessage("Password: " + (room.password.isEmpty() ? "none" : "set") + ".  Whitelist: " + (room.whitelist ? "on (" + room.whitelisted().size() + ")" : "off") + ".  (/oh password, /oh whitelist)");
    }
}
