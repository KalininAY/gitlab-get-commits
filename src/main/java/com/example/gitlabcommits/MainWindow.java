package com.example.gitlabcommits;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ItemEvent;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class MainWindow extends JFrame {

    private final AppConfig config;

    private final HistoryComboBox hostCombo;
    private final HistoryComboBox tokenCombo;
    private final HistoryComboBox segmentCombo;
    private final HistoryComboBox projectIdsCombo;
    private final HistoryComboBox sinceCombo;
    private final HistoryComboBox untilCombo;

    private final JTextArea outputArea;
    private final JButton runButton;
    private final JButton copyButton;
    private final JLabel statusLabel;
    private final JProgressBar progressBar;

    public MainWindow(AppConfig config) {
        super("GitLab Get Commits");
        this.config = config;

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(800, 650));

        List<String> hosts = config.getHosts();
        String firstHost = hosts.isEmpty() ? "http://10.1.5.6" : hosts.get(0);

        hostCombo       = new HistoryComboBox(hosts, firstHost);
        tokenCombo      = new HistoryComboBox(config.getList(firstHost, "token"),      "");
        segmentCombo    = new HistoryComboBox(config.getList(firstHost, "segment"),    "Полигон");
        projectIdsCombo = new HistoryComboBox(config.getList(firstHost, "projectIds"), "153");
        sinceCombo      = new HistoryComboBox(config.getList(firstHost, "since"),      "2025-10-01T00:00:01Z");
        untilCombo      = new HistoryComboBox(config.getList(firstHost, "until"),      "2025-11-01T00:00:01Z");

        // When host is selected from dropdown, reload all other combos
        hostCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                reloadCombos(hostCombo.getCurrentValue());
            }
        });

        // Form
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(12, 12, 8, 12));
        int row = 0;
        addRow(form, row++, "GitLab Host:",                hostCombo);
        addRow(form, row++, "Token:",                      tokenCombo);
        addRow(form, row++, "Segment:",                    segmentCombo);
        addRow(form, row++, "Project IDs (через запятую):", projectIdsCombo);
        addRow(form, row++, "Since (ISO 8601):",            sinceCombo);
        addRow(form, row++, "Until (ISO 8601):",            untilCombo);

        // Buttons
        runButton  = new JButton("Получить коммиты");
        copyButton = new JButton("Скопировать");
        JButton clearButton = new JButton("Очистить");
        statusLabel = new JLabel(" ");
        statusLabel.setForeground(new Color(50, 120, 50));

        runButton.addActionListener(e  -> runFetch());
        copyButton.addActionListener(e -> copyToClipboard());
        clearButton.addActionListener(e -> { outputArea.setText(""); statusLabel.setText(" "); });

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        btnPanel.add(runButton);
        btnPanel.add(copyButton);
        btnPanel.add(clearButton);
        btnPanel.add(statusLabel);

        // Progress bar
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(false);
        progressBar.setStringPainted(true);
        progressBar.setString("");
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(0, 20));

        // Output area
        outputArea = new JTextArea();
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        outputArea.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(outputArea);
        scroll.setBorder(BorderFactory.createTitledBorder("Результат (CSV)"));

        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.add(progressBar, BorderLayout.NORTH);
        top.add(form,        BorderLayout.CENTER);
        top.add(btnPanel,    BorderLayout.SOUTH);

        setLayout(new BorderLayout(0, 4));
        add(top,    BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);
    }

    private void reloadCombos(String host) {
        reloadCombo(tokenCombo,      config.getList(host, "token"),      "");
        reloadCombo(segmentCombo,    config.getList(host, "segment"),     "Полигон");
        reloadCombo(projectIdsCombo, config.getList(host, "projectIds"),  "153");
        reloadCombo(sinceCombo,      config.getList(host, "since"),       "2025-10-01T00:00:01Z");
        reloadCombo(untilCombo,      config.getList(host, "until"),       "2025-11-01T00:00:01Z");
    }

    private void reloadCombo(HistoryComboBox combo, List<String> items, String def) {
        combo.removeAllItems();
        for (String item : items) combo.addItem(item);
        if (items.isEmpty() && !def.isEmpty()) combo.addItem(def);
        if (combo.getItemCount() > 0) combo.setSelectedIndex(0);
    }

    private void runFetch() {
        String host       = hostCombo.getCurrentValue();
        String token      = tokenCombo.getCurrentValue();
        String segment    = segmentCombo.getCurrentValue();
        String projectStr = projectIdsCombo.getCurrentValue();
        String since      = sinceCombo.getCurrentValue();
        String until      = untilCombo.getCurrentValue();

        if (host.isEmpty() || token.isEmpty() || projectStr.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Заполните Host, Token и Project IDs", "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }

        List<Integer> projectIds;
        try {
            projectIds = Arrays.stream(projectStr.split("[,;\\s]+"))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this,
                    "Project IDs должны быть числами, разделёнными запятыми",
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Persist
        config.addHost(host);
        hostCombo.pushValue(host);
        saveCombo(host, "token",      tokenCombo,      token);
        saveCombo(host, "segment",    segmentCombo,    segment);
        saveCombo(host, "projectIds", projectIdsCombo, projectStr);
        saveCombo(host, "since",      sinceCombo,      since);
        saveCombo(host, "until",      untilCombo,      until);
        config.save();

        // UI
        runButton.setEnabled(false);
        copyButton.setEnabled(false);
        outputArea.setText("");
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        progressBar.setString("Инициализация...");
        statusLabel.setText(" ");

        GitLabService service = new GitLabService(host, token, segment, since, until,
                msg -> SwingUtilities.invokeLater(() -> progressBar.setString(msg)));

        service.fetchProjects(projectIds)
                .thenAccept(commits -> SwingUtilities.invokeLater(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(100);
                    if (commits.isEmpty()) {
                        progressBar.setString("Готово — 0 коммитов");
                        statusLabel.setText("Коммитов не найдено");
                    } else {
                        String csv = commits.stream()
                                .map(CommitDetail::toCsvRow)
                                .collect(Collectors.joining("\n"));
                        outputArea.setText(csv);
                        progressBar.setString("Готово — " + commits.size() + " коммитов");
                        statusLabel.setText("Найдено: " + commits.size());
                    }
                    runButton.setEnabled(true);
                    copyButton.setEnabled(true);
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setIndeterminate(false);
                        progressBar.setString("Ошибка");
                        statusLabel.setText("Ошибка: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                        runButton.setEnabled(true);
                    });
                    return null;
                });
    }

    private void saveCombo(String host, String key, HistoryComboBox combo, String currentValue) {
        combo.pushValue(currentValue);
        config.setList(host, key, currentValue, combo.getAllItems());
    }

    private void copyToClipboard() {
        String text = outputArea.getText();
        if (text.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        statusLabel.setText("Скопировано в буфер обмена");
    }

    private void addRow(JPanel panel, int row, String labelText, JComponent field) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = row;
        lc.anchor = GridBagConstraints.EAST;
        lc.insets = new Insets(4, 4, 4, 8);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1; fc.gridy = row;
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.insets = new Insets(4, 0, 4, 4);

        panel.add(new JLabel(labelText), lc);
        panel.add(field, fc);
    }
}
