package pro.deadeangaffer.glsound;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SoundPlayer {

    private static final Logger LOG = Logger.getLogger(SoundPlayer.class.getName());

    private static final int SAMPLE_RATE = 44_100;
    private static final double MAX_MULTIPLIER = 1.7;
    private static final AudioFormat FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

    private static final double[] BELL_PARTIALS = {1.0, 2.0, 3.0, 4.2};
    private static final double[] BELL_AMPS     = {1.0, 0.6, 0.35, 0.18};
    private static final double[] BELL_DECAYS   = {3.0, 5.5, 8.0, 11.0};

    private volatile double volumeMultiplier = MAX_MULTIPLIER;

    public void setVolumePercent(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        this.volumeMultiplier = (clamped / 100.0) * MAX_MULTIPLIER;
    }

    public void playStart() {
        play(bell(880.0, 450, 0.45));
    }

    public void playSuccess() {
        play(arpeggio(new double[]{523.25, 659.25, 783.99, 1046.50}, 80, 600));
    }

    public void playFailure() {
        play(arpeggio(new double[]{587.33, 440.00, 349.23, 293.66}, 100, 700));
    }

    private void play(double[] samples) {
        double multiplier = volumeMultiplier;
        if (multiplier <= 0.0) return;
        byte[] buf = toPcm16(samples, multiplier);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(FORMAT)) {
            line.open(FORMAT);
            line.start();
            line.write(buf, 0, buf.length);
            line.drain();
        } catch (LineUnavailableException e) {
            LOG.log(Level.WARNING, "Аудио-линия недоступна: %s".formatted(e.getMessage()));
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Аудио-формат не поддерживается: %s".formatted(e.getMessage()));
        }
    }

    private static double[] arpeggio(double[] freqs, int staggerMs, int noteDecayMs) {
        int totalMs = staggerMs * (freqs.length - 1) + noteDecayMs + 50;
        double[] mix = new double[msToSamples(totalMs)];
        for (int i = 0; i < freqs.length; i++) {
            double[] note = bell(freqs[i], noteDecayMs, 0.35);
            int offset = msToSamples(staggerMs * i);
            for (int j = 0; j < note.length && offset + j < mix.length; j++) {
                mix[offset + j] += note[j];
            }
        }
        normalize(mix, 0.85);
        return mix;
    }

    private static double[] bell(double freqHz, int durMs, double amp) {
        int n = msToSamples(durMs);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;
            double s = 0;
            for (int p = 0; p < BELL_PARTIALS.length; p++) {
                double f = freqHz * BELL_PARTIALS[p];
                s += BELL_AMPS[p] * Math.sin(2 * Math.PI * f * t) * Math.exp(-BELL_DECAYS[p] * t);
            }
            out[i] = s * amp;
        }
        int fadeIn = msToSamples(2);
        for (int i = 0; i < fadeIn; i++) out[i] *= i / (double) fadeIn;
        return out;
    }

    private static int msToSamples(int ms) {
        return (int) (SAMPLE_RATE * ms / 1000.0);
    }

    private static void normalize(double[] s, double target) {
        double max = 0;
        for (double v : s) max = Math.max(max, Math.abs(v));
        if (max <= 0) return;
        double k = target / max;
        for (int i = 0; i < s.length; i++) s[i] *= k;
    }

    private static byte[] toPcm16(double[] samples, double multiplier) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            double v = samples[i] * multiplier;
            if (v > 1.0) v = 1.0;
            else if (v < -1.0) v = -1.0;
            int iv = (int) (v * Short.MAX_VALUE);
            out[i * 2]     = (byte) (iv & 0xff);
            out[i * 2 + 1] = (byte) ((iv >> 8) & 0xff);
        }
        return out;
    }
}
