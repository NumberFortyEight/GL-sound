package pro.deadeangaffer.glsound;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

public final class SplashOverlay {

    private static final int HOLD_MS = 1500;
    private static final int FADE_STEPS = 8;
    private static final int FADE_STEP_MS = 25;

    private SplashOverlay() {}

    public static void show(String text) {
        EventQueue.invokeLater(() -> render(text));
    }

    private static void render(String text) {
        var window = new JWindow();
        window.setBackground(new Color(0, 0, 0, 0));
        window.setAlwaysOnTop(true);

        var panel = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(28, 30, 34, 235));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                g2.setColor(new Color(255, 255, 255, 40));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 18, 18);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(18, 28, 18, 28));

        var label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 14f));
        label.setForeground(new Color(0xECEFF4));
        panel.add(label, BorderLayout.CENTER);

        window.setContentPane(panel);
        window.pack();
        var size = panel.getPreferredSize();
        window.setSize(new Dimension(Math.max(320, size.width), size.height));
        window.setLocationRelativeTo(null);
        window.setOpacity(1f);
        window.setVisible(true);

        var hold = new Timer(HOLD_MS, e -> fadeOut(window));
        hold.setRepeats(false);
        hold.start();
    }

    private static void fadeOut(JWindow window) {
        int[] step = { FADE_STEPS };
        var fade = new Timer(FADE_STEP_MS, null);
        fade.addActionListener(e -> {
            step[0]--;
            if (step[0] <= 0) {
                fade.stop();
                window.dispose();
            } else {
                window.setOpacity(step[0] / (float) FADE_STEPS);
            }
        });
        fade.setRepeats(true);
        fade.start();
    }
}
