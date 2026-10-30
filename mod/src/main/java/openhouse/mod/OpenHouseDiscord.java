package openhouse.mod;

import de.jcm.discordgamesdk.activity.Activity;
import net.minecraft.client.Minecraft;

public final class OpenHouseDiscord {
    private OpenHouseDiscord() { }

    static final String SECRET = "openhouse:";
    public static volatile boolean shareCode;
    static volatile String pendingJoin;
    private static volatile String lastSaid;

    public static void join(String secret) {
        if (secret == null || !secret.startsWith(SECRET)) return;
        String code = openhouse.Rtc.normalizeCode(secret.substring(SECRET.length()));
        if (code == null) return;
        OpenHouseMod.LOG.info("joining room {} from Discord", code);
        pendingJoin = code;
    }

    public static void decorate(Activity a) {
        try {
            LocalServer ls = LocalServer.current();
            if (ls == null || !LocalServer.hosting() || ls.code == null) return;
            Minecraft mc = Minecraft.getMinecraft();
            a.setDetails("Hosting a world - OpenHouse");
            String said = shareCode ? "public " + ls.code : "friends";
            if (!said.equals(lastSaid)) { lastSaid = said; OpenHouseMod.LOG.info("Discord presence: {}", shareCode ? "room code " + ls.code + " with Join" : "playing with friends (no code)"); }
            if (!shareCode) {
                a.setState("Playing with friends");
                return;
            }
            a.setState("Room code " + ls.code + " - join me!");
            a.party().setID("openhouse-" + ls.code);
            int players = mc != null && mc.currentWorld != null ? Math.max(1, mc.currentWorld.players.size()) : 1;
            a.party().size().setCurrentSize(players);
            a.party().size().setMaxSize(8);
            a.secrets().setJoinSecret(SECRET + ls.code);
        } catch (Throwable t) {

        }
    }
}
