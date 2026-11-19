package openhouse.mod.voice;

import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.PlayerLocalMultiplayer;
import net.minecraft.client.net.handler.PacketHandlerClient;
import net.minecraft.core.net.handler.PacketHandler;
import net.minecraft.core.net.packet.PacketCustomPayload;
import net.minecraft.core.entity.player.Player;
import org.lwjgl.input.Keyboard;

public final class Voice {
    private Voice() { }

    public static final int RATE = 48000, FRAME = 960;
    public static final AudioFormat FMT = new AudioFormat(RATE, 16, 1, true, false);

    static final VoiceOut out = new VoiceOut();
    private static VoiceMic mic;
    private static volatile boolean active;
    private static volatile PacketHandlerClient link;
    private static PacketHandlerClient hello_link;
    private static long hello_at;
    private static int hello_tries;
    private static volatile boolean server_ok;

    public static volatile boolean testing;

    public static volatile boolean monitor;
    static final int SELF = Integer.MIN_VALUE;

    public static volatile boolean test_ptt;
    private static boolean key_was;

    static volatile boolean server_muted;

    private static volatile String server_code = "";
    public static String server_code() { return server_code; }

    public static void init() {
        VoiceCfg.load();
        HurtClip.load();
        VoiceWire.local_hurt = (p, pitch) -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || p != mc.thePlayer || mc.isMultiplayerWorld() || !VoiceCfg.hurt_custom) return false;
            short[] pcm = HurtClip.mine_pcm();
            if (pcm == null) return false;
            float vol = 1;
            try { vol = net.minecraft.client.option.GameSettings.MASTER_VOLUME.value * net.minecraft.client.option.GameSettings.ENTITY_SOUNDS_VOLUME.value; } catch (Throwable ignored) { }
            if (vol > 0.001F) { out.hurt(pcm, pitch, 0, 0, 0, vol, true); out.start(); }
            return true;
        };
        VoiceWire.client = Voice::got;
    }

    public static boolean active() { return active; }

    public static boolean ref_wanted() { VoiceMic m = mic; return m != null && VoiceCfg.echo; }
    public static boolean server_has_voice() { return server_ok; }
    public static boolean in_server() { return link != null; }
    public static boolean talking() { VoiceMic m = mic; return m != null && (active && !m.test || testing) && m.talking(); }
    public static float level() { VoiceMic m = mic; return m == null ? 0 : m.level(); }
    public static boolean speaking(int id) { return active && out.speaking(id); }
    public static float loudness(int id) { return out.loudness(id); }
    static boolean denoise_broken() { return VoiceProc.denoise_broken(); }

    public static String problem() {
        if (active && server_muted) return net.minecraft.core.lang.I18n.getInstance().translateKey("gui.openhouse.voice.hud.servermuted");
        VoiceMic m = mic;
        if (m != null && m.problem != null) return m.problem;
        return active ? out.problem : null;
    }

    public static void frame(Minecraft mc) {
        Player me = mc.thePlayer;
        PacketHandlerClient l = mc.currentWorld != null && mc.isMultiplayerWorld() && me instanceof PlayerLocalMultiplayer
                ? ((PlayerLocalMultiplayer) me).sendQueue : null;
        link = l;
        if (l != hello_link) { hello_link = l; server_ok = false; server_muted = false; server_code = ""; hello_at = 0; hello_tries = 0; hurt_sent = false; HurtClip.forget_all(); }
        long now = System.currentTimeMillis();

        if (l != null && !server_ok && hello_tries < 6 && now - hello_at > 2500) {
            hello_at = now;
            hello_tries++;
            hello(l);
        } else if (l != null && server_ok && hello_on != VoiceCfg.on) hello(l);

        if (l != null && server_ok) {
            boolean c = VoiceCfg.hurt_custom && HurtClip.clip != null;
            int v = HurtClip.version;
            if (!hurt_sent || c != hurt_was || c && v != hurt_ver) {
                hurt_sent = true; hurt_was = c; hurt_ver = v;
                byte[] clip = HurtClip.clip;
                byte[] d;
                if (c && clip != null) { d = new byte[clip.length + 1]; d[0] = VoiceWire.HURT_SET; System.arraycopy(clip, 0, d, 1, clip.length); }
                else d = new byte[] { VoiceWire.HURT_SET, 0 };
                l.addToSendQueue(new PacketCustomPayload(VoiceWire.CHANNEL, d));
            }
        }
        hurts(mc);
        boolean want = VoiceCfg.on && l != null && server_ok;
        boolean self = testing && monitor;

        if (want || self || out.hurts_busy()) out.start(); else out.stop();
        if (want != active) {
            active = want;

            if (want && !VoiceCfg.hinted && mc.hudIngame != null) {
                VoiceCfg.hinted = true;
                VoiceCfg.save();
                net.minecraft.core.lang.I18n t = net.minecraft.core.lang.I18n.getInstance();
                mc.hudIngame.addChatMessage(t.translateKeyAndFormat("gui.openhouse.voice.hint", Keyboard.getKeyName(VoiceCfg.mute_key)));
            }
        }

        boolean k = mc.currentScreen == null && me != null && VoiceCfg.mute_key > 0 && Keyboard.isKeyDown(VoiceCfg.mute_key);
        if (k && !key_was && active) { VoiceCfg.muted = !VoiceCfg.muted; VoiceCfg.save(); }
        key_was = k;

        boolean send = want && !VoiceCfg.muted;
        boolean need = send || testing;
        String name = VoiceCfg.device();
        boolean auto = (VoiceCfg.mic == null || VoiceCfg.mic.isEmpty()) && System.getProperty("openhouse.voice.file") == null;
        if (mic != null && (!need || !mic.name.equals(name) || mic.test == send || mic.auto != auto)) stop_mic();
        if (mic == null && need) { mic = new VoiceMic(name, VoiceCfg.denoise, !send, auto); mic.start(); }

        String sw = MicAuto.said;
        if (sw != null && mc.hudIngame != null) {
            MicAuto.said = null;
            mc.hudIngame.addChatMessage(net.minecraft.core.lang.I18n.getInstance().translateKeyAndFormat("gui.openhouse.voice.automic", sw));
        }
        if (mic != null) {
            mic.clean = VoiceCfg.denoise;
            mic.aec = VoiceCfg.echo;

            boolean screen_ok = mc.currentScreen == null || mc.currentScreen instanceof ScreenVoice;
            mic.ptt = VoiceCfg.ptt ? (Boolean) (test_ptt || screen_ok && VoiceCfg.ptt_key > 0 && Keyboard.isKeyDown(VoiceCfg.ptt_key)) : null;
        }

        float master = 1;
        try { master = net.minecraft.client.option.GameSettings.MASTER_VOLUME.value; } catch (Throwable ignored) { }
        out.vol = VoiceCfg.vol * master;
        duck(active ? out.loudest_talker() : 0);
        if (active || self) out.place(mc);
        if (LOG && now - log_at >= 1000) {
            log_at = now;
            VoiceMic m = mic;
            openhouse.mod.OpenHouseMod.LOG.info("[VoiceLog] server={} active={} mic={} talking={} sent={} got={} ptt={}{} muted={} self={} dev={} duck={} echo={}ms/{}dB gate={} hurts={} {}", l == null ? "none" : server_ok ? "voice" : "no-voice",
                    active, m == null ? "closed" : m.problem == null ? (m.test ? "test" : "open") : m.problem, talking(), sent.getAndSet(0), got.getAndSet(0),
                    VoiceCfg.ptt, test_ptt ? "(held)" : "", VoiceCfg.muted, monitor, VoiceCfg.mic == null || VoiceCfg.mic.isEmpty() ? "auto:" + Mics.label(MicAuto.pick, 20) : Mics.label(VoiceCfg.mic, 20), String.format("%.2f", duck),
                    m == null ? -1 : m.echo_delay(), m == null ? 0 : Math.round(m.echo_erle()), m == null ? "-" : m.gate(), hurt_log, out.describe());
            hurt_log = 0;
        }
    }

    static final float DUCK_MIN = 0.35F, DUCK_DOWN = 0.15F, DUCK_UP = 1.2F;
    private static volatile float duck = 1;
    private static long duck_at;
    private static void duck(float loud) {
        long now = System.nanoTime();
        float dt = duck_at == 0 ? 0 : Math.min(0.25F, (now - duck_at) / 1e9F);
        duck_at = now;
        float want = 1 - (1 - DUCK_MIN) * Math.min(1, loud * 1.6F);
        float tau = want < duck ? DUCK_DOWN : DUCK_UP;
        float d = duck + (want - duck) * (1 - (float) Math.exp(-dt / tau));
        duck = Math.abs(d - 1) < 0.003F ? 1 : d;
    }
    public static float duck_now() { return duck; }

    private static boolean hello_on, hurt_sent, hurt_was;
    private static int hurt_ver;

    private static void hello(PacketHandlerClient l) {
        hello_on = VoiceCfg.on;
        l.addToSendQueue(new PacketCustomPayload(VoiceWire.CHANNEL, new byte[] { VoiceWire.HELLO, (byte) (VoiceCfg.on ? VoiceWire.PROTO : 0) }));
    }

    private static void hurts(Minecraft mc) {
        float[] h;
        while ((h = HurtClip.plays.poll()) != null) {
            int id = (int) h[0];
            short[] pcm = HurtClip.pcm_of(id);
            if (pcm == null || mc.currentWorld == null || mc.thePlayer == null) continue;
            float vol = 1;
            try { vol = net.minecraft.client.option.GameSettings.MASTER_VOLUME.value * net.minecraft.client.option.GameSettings.ENTITY_SOUNDS_VOLUME.value; } catch (Throwable ignored) { }
            if (vol <= 0.001F) continue;
            Player me = mc.thePlayer;
            if (id == me.id) { out.hurt(pcm, h[1], 0, 0, 0, vol, true); hurt_log++; continue; }
            net.minecraft.core.entity.Entity e = null;
            try { e = mc.currentWorld.getEntityByID(id); } catch (Throwable ignored) { }
            if (e == null) continue;
            double dx = e.x - me.x, dy = e.y - me.y, dz = e.z - me.z;
            float g = vol * (float) Math.max(0, 1 - Math.sqrt(dx * dx + dy * dy + dz * dz) / 16);
            if (g > 0.001F) { out.hurt(pcm, h[1], (float) e.x, (float) e.y, (float) e.z, g, false); hurt_log++; }
        }
    }
    private static int hurt_log;

    private static final boolean LOG = Boolean.getBoolean("openhouse.voicelog");
    private static final java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger(), got = new java.util.concurrent.atomic.AtomicInteger();
    private static long log_at;

    private static void stop_mic() {
        if (mic != null) { mic.stop(); mic = null; }
    }

    public static void shutdown() {
        active = false;
        stop_mic();
        out.stop();
    }

    static void send(byte[] opus, int loud) {
        PacketHandlerClient l = link;
        if (!active || l == null) return;
        byte[] d;
        if (opus.length == 0) d = new byte[] { VoiceWire.UP };
        else {
            d = new byte[opus.length + 2];
            d[0] = VoiceWire.UP;
            d[1] = (byte) Math.max(1, Math.min(255, loud));
            System.arraycopy(opus, 0, d, 2, opus.length);
        }
        l.addToSendQueue(new PacketCustomPayload(VoiceWire.CHANNEL, d));
        sent.incrementAndGet();
    }

    static void self(byte[] opus, int loud) {
        if (!testing || !monitor || VoiceCfg.muted) {
            if (opus.length == 0) out.receive(SELF, opus, 0, 0, 0);
            return;
        }
        out.receive(SELF, opus, 0, opus.length, loud);
    }

    static void got(PacketHandler from, byte[] d) {
        if (d.length == 0) return;
        if (d[0] == VoiceWire.HELLO) {
            if (d.length >= 3 && d.length >= 3 + (d[2] & 0xFF)) server_code = new String(d, 3, d[2] & 0xFF, java.nio.charset.StandardCharsets.US_ASCII);
            server_ok = true;
            return;
        }
        if (d[0] == VoiceWire.STATE) { server_muted = d.length > 1 && (d[1] & 1) != 0; return; }
        if (d[0] == VoiceWire.HURT_CLIP) { HurtClip.got_clip(d); return; }
        if (d[0] == VoiceWire.HURT_PLAY) { HurtClip.got_play(d); return; }
        if (d[0] != VoiceWire.DOWN || d.length < 5) return;
        int id = (d[1] & 0xFF) << 24 | (d[2] & 0xFF) << 16 | (d[3] & 0xFF) << 8 | d[4] & 0xFF;
        got.incrementAndGet();
        if (!active) return;
        if (d.length <= 6) out.receive(id, d, 6, 0, 0);
        else out.receive(id, d, 6, d.length - 6, d[5] & 0xFF);
    }

    public static List<Player> speaking(Minecraft mc) {
        List<Player> r = new ArrayList<>();
        if (!active || mc.currentWorld == null) return r;
        try {
            for (Player p : mc.currentWorld.players) if (p != mc.thePlayer && out.speaking(p.id)) r.add(p);
        } catch (RuntimeException ignored) { }
        return r;
    }
}
