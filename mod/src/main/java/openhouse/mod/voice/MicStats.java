package openhouse.mod.voice;

final class MicStats {
    static final float MIN_SPEECH_RMS = 200;
    int frames, speech, onsets;
    private boolean was;
    private float floor = 1e9F;
    private double speech_e, speech_d;
    private int peak;
    private double e_sum, e_sq;

    void add(short[] f) { add(f, VoiceEcho.playing_now()); }

    void add(short[] f, boolean far) {
        if (far) { was = false; return; }
        double e = 0, d = 0;
        int zc = 0, pk = 0, prev = f[0];
        for (int i = 0; i < f.length; i++) {
            int x = f[i];
            e += (double) x * x;
            if (i > 0) { double dx = x - prev; d += dx * dx; if ((x >= 0) != (prev >= 0)) zc++; }
            pk = Math.max(pk, Math.abs(x));
            prev = x;
        }
        float rms = (float) Math.sqrt(e / f.length);
        peak = Math.max(peak, pk);
        double le = Math.log10(e / f.length + 1);
        e_sum += le; e_sq += le * le;

        floor = rms < floor ? Math.max(1, rms) : floor + (rms - floor) * 0.003F;
        float zcr = zc / (float) f.length;
        boolean sp = rms > MIN_SPEECH_RMS && rms > floor * 3 && zcr > 0.01F && zcr < 0.35F;
        frames++;
        if (sp) { speech++; speech_e += e; speech_d += d; if (!was) onsets++; }
        was = sp;
    }

    boolean dead() {
        if (frames < 25) return false;
        double mean = e_sum / frames, var = e_sq / frames - mean * mean;
        return peak < 8 || var < 1e-4 && mean < 1.5;
    }

    float activity() {
        if (frames == 0 || onsets < 2) return 0;
        return speech / (float) frames;
    }

    float quality() {
        if (speech == 0) return 0;
        double sp_rms = Math.sqrt(speech_e / (speech * 960.0));
        float snr_db = (float) (20 * Math.log10(sp_rms / Math.max(1, floor)));
        float hf = (float) (speech_d / Math.max(1, speech_e));
        return Math.max(0, Math.min(1, snr_db / 30)) * 0.7F + Math.max(0, Math.min(1, hf / 0.5F)) * 0.3F;
    }

    float score() { return activity() * (0.5F + 0.5F * quality()); }

    public String toString() {
        return String.format("act=%.2f q=%.2f score=%.2f dead=%s floor=%.0f onsets=%d", activity(), quality(), score(), dead(), floor, onsets);
    }
}
