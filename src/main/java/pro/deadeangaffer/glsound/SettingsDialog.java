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
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class SettingsDialog extends JFrame {

    private static final Pattern PROJECT_PATH_RX = Pattern.compile("^[A-Za-z0-9._][A-Za-z0-9._\\-/]{0,254}$");
    private static final Color MUTED = new Color(0x666666);
    private static final Color OK_GREEN = new Color(0x2e7d32);
    private static final Color ERR_RED = new Color(0xc62828);

    private final Config cfg;
    private final HttpClient http;
    private final Runnable onApply;

    private final JTextField baseUrlField = new JTextField();
    private final JTextField projectField = new JTextField();
    private final JTextField refsField = new JTextField();
    private final JSpinner intervalSpin = new JSpinner(new SpinnerNumberModel(6, 2, 3600, 1));
    private final VolumeKnob volumeKnob = new VolumeKnob(70);
    private final JPasswordField tokenField = new JPasswordField();
    private final JCheckBox showToken = new JCheckBox("Показать токен");

    private final JLabel testStatus = mutedLabel("не проверено");
    private final JLabel autostartLbl = mutedLabel("…");
    private final JButton autostartBtn = new JButton("…");

    public SettingsDialog(Config cfg, HttpClient http, Runnable onApply) {
        super("GL-Sound — Настройки");
        this.cfg = cfg;
        this.http = http;
        this.onApply = onApply;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        content.add(section("GitLab base URL", baseUrlField, null));
        content.add(section("Путь к проекту", projectField, null));
        content.add(section("Ветки для мониторинга", refsField, "Через запятую."));
        content.add(wrapHint(intervalRow(), "Минимум 2 секунды."));
        content.add(section("Personal Access Token (PAT)", tokenPanel(), tokenHintHtml()));
        content.add(Box.createRigidArea(new Dimension(0, 8)));
        content.add(testRow());
        content.add(Box.createRigidArea(new Dimension(0, 12)));
        content.add(autoRow());
        content.add(knobSection());
        content.add(Box.createVerticalGlue());

        JPanel buttons = new JPanel(new BorderLayout());
        JPanel right = new JPanel();
        JButton saveBtn = new JButton("Сохранить");
        JButton cancelBtn = new JButton("Отмена");
        saveBtn.addActionListener(e -> save());
        cancelBtn.addActionListener(e -> dispose());
        right.add(saveBtn);
        right.add(cancelBtn);
        buttons.add(right, BorderLayout.EAST);
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 14, 14, 14));
        getRootPane().setDefaultButton(saveBtn);

        JScrollPane scroll = new JScrollPane(content,
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

    private JPanel intervalRow() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(new JLabel("Интервал опроса (сек): "));
        intervalSpin.setMaximumSize(new Dimension(80, 26));
        row.add(intervalSpin);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel tokenPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        tokenField.setEchoChar('•');
        tokenField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        panel.add(tokenField);
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
        controls.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton openTokenPage = new JButton("Открыть страницу создания токена");
        openTokenPage.addActionListener(e -> openTokenPage());
        showToken.addActionListener(e -> tokenField.setEchoChar(showToken.isSelected() ? (char) 0 : '•'));
        controls.add(openTokenPage);
        controls.add(Box.createRigidArea(new Dimension(8, 0)));
        controls.add(showToken);
        controls.add(Box.createHorizontalGlue());
        panel.add(Box.createRigidArea(new Dimension(0, 6)));
        panel.add(controls);
        return panel;
    }

    private static String tokenHintHtml() {
        return """
                Где взять:<br>
                &nbsp;1. Нажми <b>Открыть страницу создания токена</b><br>
                &nbsp;2. <b>Token name</b> — любое<br>
                &nbsp;3. <b>Scopes</b> — ТОЛЬКО <b>read_api</b><br>
                &nbsp;4. Жми <b>Create</b>, скопируй токен (показывается один раз!) и вставь сюда.<br>
                Токен шифруется Windows DPAPI в %APPDATA%\\GL-Sound\\config.properties.""";
    }

    private JPanel testRow() {
        JPanel row = horizontalRow();
        JButton testBtn = new JButton("Проверить подключение");
        testBtn.addActionListener(e -> runTest(testBtn));
        row.add(testBtn);
        row.add(Box.createRigidArea(new Dimension(10, 0)));
        row.add(testStatus);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel autoRow() {
        JPanel row = horizontalRow();
        autostartBtn.addActionListener(e -> toggleAutostart());
        row.add(new JLabel("Автозапуск при входе в Windows: "));
        row.add(autostartLbl);
        row.add(Box.createRigidArea(new Dimension(10, 0)));
        row.add(autostartBtn);
        row.add(Box.createHorizontalGlue());
        return row;
    }

    private JPanel knobSection() {
        JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel title = new JLabel("Громкость уведомлений");
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        section.add(title);
        section.add(Box.createRigidArea(new Dimension(0, 6)));
        volumeKnob.setAlignmentX(Component.CENTER_ALIGNMENT);
        section.add(volumeKnob);
        return section;
    }

    private void save() {
        String baseUrl = baseUrlField.getText().trim().replaceAll("/+$", "");
        String projectPath = projectField.getText().trim();
        String token = sanitizeToken(tokenField.getPassword());
        String refs = refsField.getText().trim();
        int newInterval = (Integer) intervalSpin.getValue();
        int newVolume = volumeKnob.getValue();

        if (!baseUrl.isEmpty() && !baseUrl.toLowerCase().startsWith("https://") && !confirmInsecureUrl()) return;
        if (!projectPath.isEmpty() && !PROJECT_PATH_RX.matcher(projectPath).matches()) {
            JOptionPane.showMessageDialog(this,
                    "Путь к проекту содержит недопустимые символы.\nРазрешены: латиница, цифры, точка, подчёркивание, дефис и слэш.",
                    "Некорректный путь", JOptionPane.ERROR_MESSAGE);
            return;
        }

        cfg.update(baseUrl, projectPath, token, refs, newInterval, newVolume);
        try {
            cfg.save();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Не удалось сохранить конфиг:\n%s".formatted(ex.getMessage()),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }
        onApply.run();
        dispose();
    }

    private boolean confirmInsecureUrl() {
        int choice = JOptionPane.showConfirmDialog(this,
                "URL не использует HTTPS — токен будет уходить по открытому каналу.\nСохранить всё равно?",
                "Внимание: незащищённое соединение",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    private void runTest(JButton btn) {
        String url = baseUrlField.getText().trim().replaceAll("/+$", "");
        String path = projectField.getText().trim();
        String token = sanitizeToken(tokenField.getPassword());
        List<String> refs = Arrays.stream(refsField.getText().split(","))
                .map(String::trim)
                .filter(Predicate.not(String::isEmpty))
                .toList();

        if (token.isEmpty()) { setStatusError("введи токен"); return; }
        if (refs.isEmpty()) { setStatusError("введи хотя бы одну ветку"); return; }

        btn.setEnabled(false);
        testStatus.setText("проверяю...");
        testStatus.setForeground(MUTED);

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    GitLabClient c = new GitLabClient(http, url, path, token);
                    String firstRef = refs.getFirst();
                    Optional<PipelineInfo> maybe = c.latestPipeline(firstRef, null);
                    if (maybe.isEmpty()) return "OK: подключение есть, но pipeline на ветке '%s' не найден".formatted(firstRef);
                    PipelineInfo p = maybe.get();
                    return "OK: #%d status=%s ref=%s".formatted(p.id(), p.status(), p.ref());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "Ошибка: проверка прервана";
                } catch (Exception ex) {
                    return "Ошибка: %s".formatted(ex.getMessage());
                }
            }

            @Override
            protected void done() {
                try {
                    String msg = get();
                    testStatus.setText(msg);
                    testStatus.setForeground(msg.startsWith("OK") ? OK_GREEN : ERR_RED);
                } catch (Exception ignored) {
                } finally {
                    btn.setEnabled(true);
                }
            }
        }.execute();
    }

    private void openTokenPage() {
        String url = "%s/-/user_settings/personal_access_tokens".formatted(baseUrlField.getText().trim().replaceAll("/+$", ""));
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            showOpenManually(url);
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception ex) {
            showOpenManually(url);
        }
    }

    private void showOpenManually(String url) {
        JOptionPane.showMessageDialog(this,
                "Не удалось открыть браузер. Открой вручную:\n%s".formatted(url),
                "Открой в браузере", JOptionPane.INFORMATION_MESSAGE);
    }

    private void toggleAutostart() {
        autostartBtn.setEnabled(false);
        autostartLbl.setText("…");
        autostartLbl.setForeground(MUTED);

        new SwingWorker<Boolean, Void>() {
            private Exception error;

            @Override
            protected Boolean doInBackground() {
                try {
                    if (Autostart.isInstalled()) {
                        Autostart.uninstall();
                        return false;
                    }
                    Autostart.install(Autostart.currentExecutable()
                            .orElseThrow(() -> new IllegalStateException("Не могу определить путь к exe текущего процесса")));
                    return true;
                } catch (Exception ex) {
                    error = ex;
                    return null;
                }
            }

            @Override
            protected void done() {
                Optional.ofNullable(error).ifPresent(e ->
                        JOptionPane.showMessageDialog(SettingsDialog.this,
                                "Не удалось изменить автозапуск:\n%s".formatted(e.getMessage()),
                                "Ошибка", JOptionPane.ERROR_MESSAGE));
                autostartBtn.setEnabled(true);
                refreshAutostart();
            }
        }.execute();
    }

    private void refreshAutostart() {
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return Autostart.isInstalled();
            }

            @Override
            protected void done() {
                try {
                    applyAutostartState(get());
                } catch (Exception ignored) {
                }
            }
        }.execute();
    }

    private void applyAutostartState(boolean on) {
        autostartLbl.setText(on ? "включён" : "выключен");
        autostartLbl.setForeground(on ? OK_GREEN : MUTED);
        autostartBtn.setText(on ? "Выключить" : "Включить");
    }

    private void setStatusError(String msg) {
        testStatus.setText(msg);
        testStatus.setForeground(ERR_RED);
    }

    private static String sanitizeToken(char[] raw) {
        if (raw == null) return "";
        return new String(raw).trim().replace("\r", "").replace("\n", "");
    }

    private static JPanel horizontalRow() {
        JPanel p = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel verticalSection() {
        JPanel p = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        return p;
    }

    private static JPanel section(String title, JComponent input, String hintHtml) {
        JPanel p = verticalSection();
        JLabel titleLbl = new JLabel(title);
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleLbl.setFont(titleLbl.getFont().deriveFont(Font.BOLD));
        p.add(titleLbl);
        p.add(Box.createRigidArea(new Dimension(0, 4)));
        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (input instanceof JTextField || input instanceof JPasswordField) {
            input.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        }
        p.add(input);
        Optional.ofNullable(hintHtml)
                .filter(Predicate.not(String::isBlank))
                .ifPresent(html -> {
                    p.add(Box.createRigidArea(new Dimension(0, 4)));
                    p.add(hintLabel(html));
                });
        return p;
    }

    private static JPanel wrapHint(JComponent input, String hintHtml) {
        JPanel p = verticalSection();
        input.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(input);
        p.add(Box.createRigidArea(new Dimension(0, 4)));
        p.add(hintLabel(hintHtml));
        return p;
    }

    private static JLabel hintLabel(String html) {
        JLabel hint = htmlLabel(html);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
        hint.setForeground(MUTED);
        return hint;
    }

    private static JLabel mutedLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(MUTED);
        return l;
    }

    private static JLabel htmlLabel(String html) {
        return new JLabel("<html><body style='width:540px'>%s</body></html>".formatted(html));
    }

    public static void open(Config cfg, HttpClient http, Runnable onApply) {
        SwingUtilities.invokeLater(() -> new SettingsDialog(cfg, http, onApply).setVisible(true));
    }
}
