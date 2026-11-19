package openhouse.mod.voice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.WeakHashMap;
import javax.sound.sampled.AudioFormat;
import org.lwjgl.openal.AL10;

public final class GameRef {
    private GameRef() { }

    private static Field f_buffer, f_gain, f_vol, f_stream, f_data, f_format;
    private static Method m_pitch;
    private static boolean broken;

    public static void played(Object s) {
        if (broken || s == null || !Voice.ref_wanted()) return;
        try {
            if (f_buffer == null) {
                Class<?> src = s.getClass().getSuperclass();
                f_buffer = src.getDeclaredField("soundBuffer"); f_buffer.setAccessible(true);
                f_gain = src.getField("gain"); f_vol = src.getField("sourceVolume"); f_stream = src.getField("toStream");
                m_pitch = src.getMethod("getPitch");
            }
            if ((Boolean) f_stream.get(s)) return;
            Object b = f_buffer.get(s);
            if (b == null) return;
            if (f_data == null) { f_data = b.getClass().getField("audioData"); f_format = b.getClass().getField("audioFormat"); }
            byte[] d = (byte[]) f_data.get(b);
            AudioFormat af = (AudioFormat) f_format.get(b);
            if (d == null || af == null) return;
            float g = (Float) f_gain.get(s) * (Float) f_vol.get(s);
            if (g <= 0.001F) return;
            float pitch = Math.max(0.25F, (Float) m_pitch.invoke(s));
            float[] m = mono48k(d, d.length, af.getSampleRate() * pitch, af.getChannels(), af.getSampleSizeInBits(), af.isBigEndian());
            if (m != null) VoiceEcho.write(VoiceEcho.clock(), m, g, false);
        } catch (Throwable t) { broken(t); }
    }

    private static final Map<Object, long[]> next = new WeakHashMap<>();

    private static Field c_src, c_fmt, c_rate;

    public static void streamed(Object ch, byte[] data) {
        if (broken || ch == null || data == null || data.length == 0 || !Voice.ref_wanted()) return;
        try {
            if (c_src == null) { Class<?> c = ch.getClass(); c_src = c.getField("ALSource"); c_fmt = c.getField("ALformat"); c_rate = c.getField("sampleRate"); }
            IntBuffer ib = (IntBuffer) c_src.get(ch);
            int al_source = ib == null ? 0 : ib.get(0), al_format = (Integer) c_fmt.get(ch), rate = (Integer) c_rate.get(ch);
            int chans = al_format == AL10.AL_FORMAT_STEREO16 || al_format == AL10.AL_FORMAT_STEREO8 ? 2 : 1;
            int bits = al_format == AL10.AL_FORMAT_MONO8 || al_format == AL10.AL_FORMAT_STEREO8 ? 8 : 16;
            float g = 1;
            try { g = AL10.alGetSourcef(al_source, AL10.AL_GAIN); } catch (Throwable ignored) { }
            float[] m = mono48k(data, data.length, rate, chans, bits, false);
            if (m == null) return;
            long now = VoiceEcho.clock(), at;
            synchronized (next) {
                long[] n = next.computeIfAbsent(ch, k -> new long[1]);
                if (n[0] < now) n[0] = now;
                at = n[0];
                n[0] += m.length;
            }
            VoiceEcho.write(at, m, g, false);
        } catch (Throwable t) { broken(t); }
    }

    static float[] mono48k(byte[] d, int len, float rate, int chans, int bits, boolean big) {
        if (rate <= 0 || chans <= 0) return null;
        int bps = bits / 8 * chans, frames = len / bps;
        if (frames <= 0) return null;
        double step = rate / 48000.0;
        int out = (int) (frames / step);
        if (out <= 0) return null;
        float[] r = new float[out];
        for (int i = 0; i < out; i++) {
            double p = i * step;
            int k = (int) p;
            float t = (float) (p - k);
            float a = sample(d, k, bps, chans, bits, big), b = k + 1 < frames ? sample(d, k + 1, bps, chans, bits, big) : a;
            r[i] = a + (b - a) * t;
        }
        return r;
    }

    private static float sample(byte[] d, int frame, int bps, int chans, int bits, boolean big) {
        int o = frame * bps;
        float s = 0;
        for (int c = 0; c < chans; c++) {
            if (bits == 8) s += ((d[o + c] & 0xFF) - 128) << 8;
            else {
                int i = o + c * 2;
                s += big ? (short) ((d[i] << 8) | (d[i + 1] & 0xFF)) : (short) ((d[i] & 0xFF) | (d[i + 1] << 8));
            }
        }
        return s / chans;
    }

    private static void broken(Throwable t) {
        broken = true;
        openhouse.mod.OpenHouseMod.LOG.info("[voice] game sounds left out of echo cancelling: {}", t.toString());
    }
}
