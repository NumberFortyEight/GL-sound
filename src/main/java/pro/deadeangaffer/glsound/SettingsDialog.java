package pro.deadeangaffer.glsound;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.regex.Pattern;

public final class SettingsDialog extends JFrame {

    private static final Pattern PROJECT_PATH_RX =
            Pattern.compile("^[A-Za-z0-9._][A-Za-z0-9._\\-/]{0,254}$");

    private final Config cfg;
    private final HttpClient http;
    private final Runnable onApply;

    private final JTextField baseUrlField   = new JTextField();
    private final JTextField projectField   = new JTextField();
    private final JTextField refsField      = new JTextField();
    private final JSpinner   intervalSpin   = new JSpinner(new SpinnerNumberModel(6, 2, 3600, 1));
    private final VolumeKnob volumeKnob     = new VolumeKnob(70);
    private final JPasswordField tokenField = new JPasswordField();
    private final JCheckBox  showToken      = new JCheckBox("Показать токен");

    private final JLabel testStatus    = mutedLabel("не проверено");
    private final JLabel autostartLbl  = mutedLabel("…");
    private final JButton autostartBtn = new JButton("…");

    public SettingsDialog(Config cfg, HttpClient http, Runnable onApply) {
        super("GL-Sound — Настройки");
        this.cfg = cfg;
        this.http = http;
        this.onApply = onApply;
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        var content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        content.add(section("GitLab base URL", baseUrlField, null));

        content.add(section("Путь к проекту", projectField, null));

        content.add(section("Ветки для мониторинга", refsField, "Через запятую."));

        var intervalRow = new JPanel();
        intervalRow.setLayout(new BoxLayout(intervalRow, BoxLayout.X_AXIS));
        intervalRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        intervalRow.add(new JLabel("Интервал опроса (сек): "));
        intervalSpin.setMaximumSize(new Dimension(80, 26));
        intervalRow.add(intervalSpin);
        intervalRow.add(Box.createHorizontalGlue());
        content.add(wrapHint(intervalRow, "Минимум 2 секунды."));

        var tokenPanel = new JPanel();
        tokenPanel.setLayout(new BoxLayout(tokenPanel, BoxLayout.Y_AXIS));
        tokenPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        tokenField.setEchoChar('•');
        tokenField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        tokenPanel.add(tokenField);
        var tokenControls = new JPanel();
        tokenControls.setLayout(new BoxLayout(tokenControls, BoxLayout.X_AXIS));
        tokenControls.setAlignmentX(Component.LEFT_ALIGNMENT);
        var openTokenPage = new JButton("Открыть страницу создания токена");
        openTokenPage.addActionListener(e -> openTokenPage());
        showToken.addActionListener(e -> tokenField.setEchoChar(showToken.isSelected() ? (char) 0 : '•'));
        tokenControls.add(openTokenPage);
        tokenControls.add(Box.createRigidArea(new Dimension(8, 0)));
        tokenControls.add(showToken);
        tokenControls.add(Box.createHorizontalGlue());
        tokenPanel.add(Box.createRigidArea(new Dimension(0, 6)));
        tokenPanel.add(tokenControls);
        content.add(section("Personal Access Token (PAT)",
                tokenPanel,
                "Где взять:<br>" +
                "&nbsp;1. Нажми <b>Открыть страницу создания токена</b><br>" +
                "&nbsp;2. <b>Token name</b> — любое<br>" +
                "&nbsp;3. <b>Scopes</b> — ТОЛЬКО <b>read_api</b><br>" +
                "&nbsp;4. Жми <b>Create</b>, скопируй токен (показывается один раз!) и вставь сюда.<br>" +
                "Токен шифруется Windows DPAPI в %APPDATA%\\GL-Sound\\config.properties."));

        var testRow = horizontalRow();
        var testBtn = new JButton("Проверить подключение");
        testBtn.addActionListener(e -> runTest(testBtn));
        testRow.add(testBtn);
        testRow.add(Box.createRigidArea(new Dimension(10, 0)));
        testRow.add(testStatus);
        testRow.add(Box.createHorizontalGlue());
        content.add(Box.createRigidArea(new Dimension(0, 8)));
        content.add(testRow);

        var autoRow = horizontalRow();
        autostartBtn.addActionListener(e -> toggleAutostart());
        autoRow.add(new JLabel("Автозапуск при входе в Windows: "));
        autoRow.add(autostartLbl);
        autoRow.add(Box.createRigidArea(new Dimension(10, 0)));
        autoRow.add(autostartBtn);
        autoRow.add(Box.createHorizontalGlue());
        content.add(Box.createRigidArea(new Dimension(0, 12)));
        content.add(autoRow);

        var knobSection = new JPanel();
        knobSection.setLayout(new BoxLayout(knobSection, BoxLayout.Y_AXIS));
        knobSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        var knobTitle = new JLabel("Громкость уведомлений");
        knobTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        knobTitle.setFont(knobTitle.getFont().deriveFont(Font.BOLD));
        knobSection.add(knobTitle);
        knobSection.add(Box.createRigidArea(new Dimension(0, 6)));
        volumeKnob.setAlignmentX(Component.CENTER_ALIGNMENT);
        knobSection.add(volumeKnob);
        content.add(knobSection);

        content.add(Box.createVerticalGlue());

        var buttons = new JPanel(new BorderLayout());
        var right = new JPanel();
        var saveBtn = new JButton("Сохранить");
        var cancelBtn = new JButton("Отмена");
        saveBtn.addActionListener(e -> save());
        cancelBtn.addActionListener(e -> dispose());
        right.add(saveBtn);
        right.add(cancelBtn);
        buttons.add(right, BorderLayout.EAST);
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 14, 14, 14));
        getRootPane().setDefaultButton(saveBtn);

        var scroll = new JScrollPane(content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        baseUrlField.setText(cfg.baseUrl());
        projectField.setText(cfg.projectPath());
        refsField.setText(String.join(",", cfg.refs()));
        intervalSpin.setValue(cfg.intervalSeconds());
        volumeKnob.setValue(cfg.volumePercent());
        tokenField.setText(cfg.token());
        refreshAutostart();

        setSize(640, 820);
        setLocationRelativeTo(null);
    }

    private void save() {
        var baseUrl = baseUrlField.getText().trim().replaceAll("/+$", "");
        var projectPath = projectField.getText().trim();
        var token = sanitizeToken(tokenField.getPassword());
        var refs = refsField.getText().trim();
        var newInterval = (Integer) intervalSpin.getValue();
        var newVolume = volumeKnob.getValue();

        if (!baseUrl.isEmpty() && !baseUrl.toLowerCase().startsWith("https://")) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "URL не использует HTTPS — токен будет уходить по открытому каналу.\n"
                            + "Сохранить всё равно?",
                    "Внимание: незащищённое соединение",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }

        if (!projectPath.isEmpty() && !PROJECT_PATH_RX.matcher(projectPath).matches()) {
            JOptionPane.showMessageDialog(this,
                    "Путь к проекту содержит недопустимые символы.\n"
                            + "Разрешены: латиница, цифры, точка, подчёркивание, дефис и слэш.",
                    "Некорректный путь", JOptionPane.ERROR_MESSAGE);
            return;
        }

        cfg.update(baseUrl, projectPath, token, refs, newInterval, newVolume);
        try {
            cfg.save();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Не удалось сохранить конфиг:\n" + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }
        onApply.run();
        dispose();
    }

    private void runTest(JButton btn) {
        var url = baseUrlField.getText().trim().replaceAll("/+$", "");
        var path = projectField.getText().trim();
        var token = sanitizeToken(tokenField.getPassword());
        var refs = Arrays.stream(refsField.getText().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        if (token.isEmpty()) { setStatusError("введи токен"); return; }
        if (refs.isEmpty())  { setStatusError("введи хотя бы одну ветку"); return; }

        btn.setEnabled(false);
        testStatus.setText("проверяю...");
        testStatus.setForeground(new Color(0x666666));

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() {
                try {
                    var c = new GitLabClient(http, url, path, token);
                    var firstRef = refs.getFirst();
                    var maybe = c.latestPipeline(firstRef, null);
                    if (maybe.isEmpty()) {
                        return "OK: подключение есть, но pipeline на ветке '" + firstRef + "' не найден";
                    }
                    var p = maybe.get();
                    return "OK: #" + p.id() + " status=" + p.status() + " ref=" + p.ref();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "Ошибка: проверка прервана";
                } catch (Exception ex) {
                    return "Ошибка: " + ex.getMessage();
                }
            }
            @Override protected void done() {
                try {
                    var msg = get();
                    testStatus.setText(msg);
                    testStatus.setForeground(msg.startsWith("OK") ? new Color(0x2e7d32) : new Color(0xc62828));
                } catch (Exception ignored) {
                } finally {
                    btn.setEnabled(true);
                }
            }
        }.execute();
    }

    private void openTokenPage() {
        var url = baseUrlField.getText().trim().replaceAll("/+$", "")
                + "/-/user_settings/personal_access_tokens";
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Не удалось открыть браузер. Открой вручную:\n" + url,
                    "Открой в браузере", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void toggleAutostart() {
        autostartBtn.setEnabled(false);
        autostartLbl.setText("…");
        autostartLbl.setForeground(new Color(0x666666));

        new SwingWorker<Boolean, Void>() {
            private Exception error;

            @Override protected Boolean doInBackground() {
                try {
                    var installed = Autostart.isInstalled();
                    if (installed) {
                        Autostart.uninstall();
                        return false;
                    }
                    var exe = Autostart.currentExecutable().orElseThrow(
                            () -> new IllegalStateException("Не могу определить путь к exe текущего процесса"));
                    Autostart.install(exe);
                    return true;
                } catch (Exception ex) {
                    error = ex;
                    return null;
                }
            }

            @Override protected void done() {
                try {
                    if (error != null) {
                        JOptionPane.showMessageDialog(SettingsDialog.this,
                                "Не удалось изменить автозапуск:\n" + error.getMessage(),
                                "Ошибка", JOptionPane.ERROR_MESSAGE);
                    }
                } finally {
                    autostartBtn.setEnabled(true);
                    refreshAutostart();
                }
            }
        }.execute();
    }

    private void refreshAutostart() {
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                return Autostart.isInstalled();
            }
            @Override protected void done() {
                try {
                    var on = get();
                    if (on) {
                        autostartLbl.setText("включён");
                        autostartLbl.setForeground(new Color(0x2e7d32));
                        autostartBtn.setText("Выключить");
                    } else {
                        autostartLbl.setText("выключен");
                        autostartLbl.setForeground(new Color(0x666666));
                        autostartBtn.setText("Включить");
                    }
                } catch (Exception ignored) {
                }
            }
        }.execute();
    }

    private void setStatusError(String msg) {
        testStatus.setText(msg);
        testStatus.setForeground(new Color(0xc62828));
    }

    private static String sanitizeToken(char[] raw) {
        if (raw == null) return "";
        var s = new String(raw).trim();
        return s.replace("\r", "").replace("\n", "");
    }

    private static JPanel horizontalRow() {
        var p = new JPanel() {
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel verticalSection() {
        var p = new JPanel() {
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        return p;
    }

    private static JPanel section(String title, JComponent input, String hintHtml) {
        var p = verticalSection();

        var titleLbl = new JLabel(title);
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleLbl.setFont(titleLbl.getFont().deriveFont(Font.BOLD));
        p.add(titleLbl);
        p.add(Box.createRigidArea(new Dimension(0, 4)));

        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (input instanceof JTextField || input instanceof JPasswordField) {
            input.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        }
        p.add(input);

        if (hintHtml != null && !hintHtml.isBlank()) {
            p.add(Box.createRigidArea(new Dimension(0, 4)));
            var hint = htmlLabel(hintHtml);
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
            hint.setForeground(new Color(0x666666));
            p.add(hint);
        }
        return p;
    }

    private static JPanel wrapHint(JComponent input, String hintHtml) {
        var p = verticalSection();
        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(input);
        p.add(Box.createRigidArea(new Dimension(0, 4)));
        var hint = htmlLabel(hintHtml);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        hint.setForeground(new Color(0x666666));
        p.add(hint);
        return p;
    }

    private static JLabel mutedLabel(String text) {
        var l = new JLabel(text);
        l.setForeground(new Color(0x666666));
        return l;
    }

    private static JLabel htmlLabel(String html) {
        return new JLabel("<html><body style='width:540px'>" + html + "</body></html>");
    }

    public static void open(Config cfg, HttpClient http, Runnable onApply) {
        SwingUtilities.invokeLater(() -> new SettingsDialog(cfg, http, onApply).setVisible(true));
    }
}
