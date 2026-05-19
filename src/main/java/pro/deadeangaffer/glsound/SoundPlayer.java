package pro.deadeangaffer.glsound;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

public final class SoundPlayer {

    private static final int SAMPLE_RATE = 44_100;
    private static final AudioFormat FORMAT =
            new AudioFormat(SAMPLE_RATE, 16, 1, true, false);

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
        try (var line = AudioSystem.getSourceDataLine(FORMAT)) {
            line.open(FORMAT);
            line.start();
            for (var t : tones) {
                byte[] buf = synth(t);
                line.write(buf, 0, buf.length);
            }
            line.drain();
        } catch (LineUnavailableException e) {
        }
    }

    private static byte[] synth(Tone t) {
        int samples = (int) (SAMPLE_RATE * t.durMs / 1000.0);
        byte[] out = new byte[samples * 2];
        int fadeSamples = Math.min(samples / 8, SAMPLE_RATE / 100);
        for (int i = 0; i < samples; i++) {
            double env = 1.0;
            if (i < fadeSamples) env = i / (double) fadeSamples;
            else if (i > samples - fadeSamples) env = (samples - i) / (double) fadeSamples;
            double s = Math.sin(2 * Math.PI * t.freqHz * i / SAMPLE_RATE);
            int v = (int) (s * env * t.volume * Short.MAX_VALUE);
            out[i * 2] = (byte) (v & 0xff);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        return out;
    }
}
