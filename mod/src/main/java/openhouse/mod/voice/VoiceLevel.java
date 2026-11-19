package openhouse.mod.voice;

final class VoiceLevel {
    private VoiceLevel() { }

    static final float TARGET = 2500;
    static final float MIN_GAIN = 0.2F, MAX_GAIN = 6;
    static final float VOICED = 180;
    static final float CEIL = 18000, KNEE = 14000;

    static final class State { float norm = 1, applied = 1; }

    static void apply(State s, short[] f) {
        double e = 0;
        int pk = 0;
        for (short x : f) { e += (double) x * x; pk = Math.max(pk, Math.abs(x)); }
        float rms = (float) Math.sqrt(e / f.length);
        if (rms > VOICED) {
            float want = Math.max(MIN_GAIN, Math.min(MAX_GAIN, TARGET / rms));
            s.norm += (want - s.norm) * (want < s.norm ? 0.4F : 0.03F);
        }
        float g = s.norm;
        if (pk * g > CEIL) g = CEIL / pk;

        float g0 = g < s.applied ? g : s.applied;
        for (int i = 0; i < f.length; i++) {
            float k = g0 + (g - g0) * i / f.length;
            float v = f[i] * k, a = Math.abs(v);
            if (a > KNEE) v = Math.signum(v) * (KNEE + (32767 - KNEE) * (float) Math.tanh((a - KNEE) / (32767 - KNEE)));
            f[i] = (short) Math.max(-32767, Math.min(32767, v));
        }
        s.applied = g;
    }
}
