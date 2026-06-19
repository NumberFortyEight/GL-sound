package pro.deadeangaffer.glsound;

import javax.swing.JComponent;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.EventListenerList;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;

public final class VolumeKnob extends JComponent {

    private static final int MIN = 0;
    private static final int MAX = 100;
    private static final int WHEEL_STEP = 1;
    private static final double START_DEG = 225.0;
    private static final double SWEEP_DEG = 270.0;
    private static final double GAP_MID = (360.0 - SWEEP_DEG) / 2.0;

    private static final Color TRACK_COLOR = new Color(0xE4E7EA);
    private static final Color TEXT = new Color(0x37474F);

    private static final Color[] STOPS = {
            new Color(0x9E9E9E),
            new Color(0x8FA995),
            new Color(0x7BB382),
            new Color(0x66BB6A),
            new Color(0x7CC356),
            new Color(0x9CCC3F),
            new Color(0xC0CA33),
            new Color(0xE3D32E),
            new Color(0xFFC107),
            new Color(0xFF9800),
            new Color(0xF44336),
    };

    private int value;
    private final EventListenerList listeners = new EventListenerList();

    public VolumeKnob(int initial) {
        this.value = clamp(initial);
        setOpaque(false);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        var handler = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); updateFromPoint(e); }
            @Override public void mouseDragged(MouseEvent e) { updateFromPoint(e); }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                setValue(value - e.getWheelRotation() * WHEEL_STEP);
            }
        };
        addMouseListener(handler);
        addMouseMotionListener(handler);
        addMouseWheelListener(handler);
    }

    public int getValue() { return value; }

    public void setValue(int v) {
        int c = clamp(v);
        if (c != value) {
            value = c;
            repaint();
            fireChange();
        }
    }

    public void addChangeListener(ChangeListener l) { listeners.add(ChangeListener.class, l); }

    private void fireChange() {
        var e = new ChangeEvent(this);
        for (var l : listeners.getListeners(ChangeListener.class)) l.stateChanged(e);
    }

    private static int clamp(int v) { return Math.max(MIN, Math.min(MAX, v)); }

    private void updateFromPoint(MouseEvent e) {
        int cx = getWidth() / 2;
        int cy = getHeight() / 2;
        double thetaDeg = Math.toDegrees(Math.atan2(-(e.getY() - cy), e.getX() - cx));
        double phase = ((START_DEG - thetaDeg) % 360.0 + 360.0) % 360.0;
        if (phase <= SWEEP_DEG) {
            setValue((int) Math.round(phase / SWEEP_DEG * 100.0));
        } else if (phase < SWEEP_DEG + GAP_MID) {
            setValue(100);
        } else {
            setValue(0);
        }
    }

    private static Color colorFor(int v) {
        double pos = (v / 100.0) * (STOPS.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(STOPS.length - 1, lo + 1);
        double t = pos - lo;
        var a = STOPS[lo];
        var b = STOPS[hi];
        int r = (int) (a.getRed()   + (b.getRed()   - a.getRed())   * t);
        int g = (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = (int) (a.getBlue()  + (b.getBlue()  - a.getBlue())  * t);
        return new Color(r, g, bl);
    }

    @Override
    public Dimension getPreferredSize() { return new Dimension(110, 110); }

    @Override
    public Dimension getMinimumSize() { return new Dimension(90, 90); }

    @Override
    public Dimension getMaximumSize() { return new Dimension(140, 140); }

    @Override
    protected void paintComponent(Graphics g) {
        var g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        int diameter = Math.min(w, h) - 14;
        int cx = w / 2;
        int cy = h / 2;
        int r = diameter / 2;
        var arcRect = new Rectangle2D.Double(cx - r, cy - r, diameter, diameter);

        g2.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(TRACK_COLOR);
        g2.draw(new Arc2D.Double(arcRect, START_DEG, -SWEEP_DEG, Arc2D.OPEN));

        if (value > 0) {
            double sweep = -SWEEP_DEG * (value / 100.0);
            g2.setColor(colorFor(value));
            g2.draw(new Arc2D.Double(arcRect, START_DEG, sweep, Arc2D.OPEN));
        }

        var font = getFont().deriveFont(Font.PLAIN, r * 0.55f);
        g2.setFont(font);
        g2.setColor(TEXT);
        var fm = g2.getFontMetrics();
        String s = value + "%";
        int tx = cx - fm.stringWidth(s) / 2;
        int ty = cy + (fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(s, tx, ty);

        g2.dispose();
    }
}
