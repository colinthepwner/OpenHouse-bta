package openhouse.mod.voice;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import openhouse.mod.OpenHouseMod;
import org.lwjgl.input.Keyboard;

public final class VoiceCfg {
    private VoiceCfg() { }

    public static volatile boolean on = true;
    public static volatile boolean muted;
    public static volatile String mic = "";
    public static final String DEFAULT = "*default";

    public static String device() {
        String m = mic == null ? "" : mic;
        return m.isEmpty() ? MicAuto.pick : m.equals(DEFAULT) ? "" : m;
    }
    public static volatile float vol = 1.0F;
    public static volatile boolean denoise = true;
    public static volatile int mute_key = Keyboard.KEY_M;
    public static volatile boolean echo = true;
    public static volatile boolean ptt;
    public static volatile int ptt_key = Keyboard.KEY_V;
    public static volatile boolean hinted;
    public static volatile boolean hurt_custom;

    public static volatile String auto_mic = "";

    private static File file() { return new File(OpenHouseMod.dataDir(), "voice.properties"); }

    public static void load() {
        Properties p = new Properties();
        File f = file();
        if (f.isFile()) try (FileInputStream in = new FileInputStream(f)) { p.load(in); } catch (Exception ignored) { }
        on = !"false".equals(p.getProperty("voice-chat"));
        muted = "true".equals(p.getProperty("muted"));
        mic = p.getProperty("microphone", "");
        denoise = !"false".equals(p.getProperty("noise-removal"));
        try { vol = Math.max(0, Math.min(2, Float.parseFloat(p.getProperty("volume", "1")))); } catch (NumberFormatException ignored) { }
        try { mute_key = Integer.parseInt(p.getProperty("mute-key", "" + Keyboard.KEY_M)); } catch (NumberFormatException ignored) { }
        echo = !"false".equals(p.getProperty("echo-cancelling"));
        ptt = "true".equals(p.getProperty("push-to-talk"));
        hinted = "true".equals(p.getProperty("hinted"));
        hurt_custom = "true".equals(p.getProperty("hurt-sound-custom"));
        auto_mic = p.getProperty("auto-microphone", "");
        MicAuto.start_on(auto_mic);
        try { ptt_key = Integer.parseInt(p.getProperty("push-to-talk-key", "" + Keyboard.KEY_V)); } catch (NumberFormatException ignored) { }
    }

    public static void save() {
        try { VoiceVolumeOption.sync(); } catch (Throwable ignored) { }
        Properties p = new Properties();
        p.setProperty("voice-chat", "" + on);
        p.setProperty("muted", "" + muted);
        p.setProperty("microphone", mic == null ? "" : mic);
        p.setProperty("noise-removal", "" + denoise);
        p.setProperty("volume", "" + vol);
        p.setProperty("mute-key", "" + mute_key);
        p.setProperty("echo-cancelling", "" + echo);
        p.setProperty("push-to-talk", "" + ptt);
        p.setProperty("push-to-talk-key", "" + ptt_key);
        p.setProperty("hinted", "" + hinted);
        p.setProperty("hurt-sound-custom", "" + hurt_custom);
        p.setProperty("auto-microphone", auto_mic == null ? "" : auto_mic);
        File d = OpenHouseMod.dataDir();
        d.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file())) { p.store(out, "OpenHouse voice chat"); } catch (Exception e) {
            OpenHouseMod.LOG.warn("can't save voice settings: {}", e.toString());
        }
    }
}
