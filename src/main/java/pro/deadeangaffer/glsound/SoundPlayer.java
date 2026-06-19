package pro.deadeangaffer.glsound;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SoundPlayer {

    private static final Logger LOG = Logger.getLogger(SoundPlayer.class.getName());

    private static final int SAMPLE_RATE = 44_100;
    private static final double MAX_MULTIPLIER = 1.7;
    private static final AudioFormat FORMAT =
            new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

    private volatile double volumeMultiplier = MAX_MULTIPLIER;

    public void setVolumePercent(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        this.volumeMultiplier = (clamped / 100.0) * MAX_MULTIPLIER;
    }

    public void playSuccess() {
        playTones(new Tone(523.25, 120, 0.35), new Tone(659.25, 200, 0.40));
    }

    public void playFailure() {
        playTones(new Tone(440.00, 150, 0.45),
                  new Tone(329.63, 150, 0.45),
                  new Tone(220.00, 300, 0.50));
    }

    public void playStart() {
        playTones(new Tone(440.00, 80, 0.25));
    }

    private record Tone(double freqHz, int durMs, double volume) {}

    private void playTones(Tone... tones) {
        double multiplier = volumeMultiplier;
        if (multiplier <= 0.0) return;
        try (var line = AudioSystem.getSourceDataLine(FORMAT)) {
            line.open(FORMAT);
            line.start();
            for (var t : tones) {
                byte[] buf = synth(t, multiplier);
                line.write(buf, 0, buf.length);
            }
            line.drain();
        } catch (LineUnavailableException e) {
            LOG.log(Level.WARNING, "Аудио-линия недоступна: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            LOG.log(Level.WARNING, "Аудио-формат не поддерживается: " + e.getMessage());
        }
    }

    private static byte[] synth(Tone t, double multiplier) {
        int samples = (int) (SAMPLE_RATE * t.durMs / 1000.0);
        byte[] out = new byte[samples * 2];
        int fadeSamples = Math.min(samples / 8, SAMPLE_RATE / 100);
        double effectiveVolume = Math.min(1.0, t.volume * multiplier);
        for (int i = 0; i < samples; i++) {
            double env = 1.0;
            if (i < fadeSamples) env = i / (double) fadeSamples;
            else if (i > samples - fadeSamples) env = (samples - i) / (double) fadeSamples;
            double s = Math.sin(2 * Math.PI * t.freqHz * i / SAMPLE_RATE);
            int v = (int) (s * env * effectiveVolume * Short.MAX_VALUE);
            out[i * 2] = (byte) (v & 0xff);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        return out;
    }
}
