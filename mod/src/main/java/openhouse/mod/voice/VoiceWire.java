package openhouse.mod.voice;

import net.minecraft.core.data.gamerule.GameRuleBoolean;
import net.minecraft.core.data.gamerule.GameRules;
import net.minecraft.core.net.handler.PacketHandler;

public final class VoiceWire {
    private VoiceWire() { }

    public static final String CHANNEL = "openhouse:voice";
    public static final int PROTO = 1;
    public static final byte HELLO = 0, UP = 1, DOWN = 2, STATE = 3, HURT_SET = 4, HURT_CLIP = 5, HURT_PLAY = 6, HURT_ADD = 7, MSG_KEYS = 8, DEATH_SET = 9, AFK = 10, AFK_LIST = 11;
    public static final int HURT_MAX = 3;
    public static final int CLIP_MAX = 12000;
    public static final int CLIP_FRAMES = 60;

    public static final float DEATH_LOUDER = 1.4F;

    public interface Sink { void got(PacketHandler from, byte[] data); }
    public static volatile Sink client, server;

    public interface HurtHook { boolean play(Object player, float pitch, boolean death); }
    public static volatile HurtHook hurt;

    public static volatile HurtHook local_hurt;

    public static java.util.List<byte[]> clip_frames(byte[] d, int off) {
        if (d == null || off >= d.length) return null;
        int n = d[off] & 0xFF, o = off + 1;
        if (n == 0 || n > CLIP_FRAMES) return null;
        java.util.List<byte[]> r = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (o + 2 > d.length) return null;
            int len = (d[o] & 0xFF) << 8 | d[o + 1] & 0xFF;
            o += 2;
            if (len <= 0 || len > 1275 || o + len > d.length) return null;
            byte[] f = new byte[len];
            System.arraycopy(d, o, f, 0, len);
            r.add(f);
            o += len;
        }
        return r;
    }

    public static final GameRuleBoolean MOBS_HEAR = GameRules.register(new GameRuleBoolean("mobsHearVoice", "gamerule.mobs_hear_voice", false));

    public static final GameRuleBoolean LOCATOR = GameRules.register(new GameRuleBoolean("showPlayerLocator", "gamerule.show_player_locator", true));

    public static final GameRuleBoolean AFK_FADE = GameRules.register(new GameRuleBoolean("afkFade", "gamerule.afk_fade", true));
    public static final GameRuleBoolean AFK_MOBS = GameRules.register(new GameRuleBoolean("afkMobsIgnore", "gamerule.afk_mobs_ignore", true));
}
