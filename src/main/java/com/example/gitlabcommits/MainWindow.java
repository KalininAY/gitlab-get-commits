package com.example.gitlabcommits;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ItemEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class MainWindow extends JFrame {

    private final AppConfig config;
    private final ProjectNameResolver nameResolver = new ProjectNameResolver();

    private final HistoryComboBox hostCombo;
    private final HistoryComboBox tokenCombo;
    private final HistoryComboBox segmentCombo;
    private final HistoryComboBox projectIdsCombo;
    private final JTextField      projectNamesField;
    private final HistoryComboBox sinceCombo;
    private final HistoryComboBox untilCombo;

    private final JTextArea    outputArea;
    private final JButton      runButton;
    private final JButton      copyButton;
    private final JLabel       statusLabel;
    private final JProgressBar progressBar;
    private final JLabel       phaseLabel;
    private final JScrollPane  outputScroll;

    // Holds CSV when done, null while running (log mode)
    private String csvResult = null;

    private ScheduledFuture<?> pendingLookup;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });

    public MainWindow(AppConfig config) {
        super("GitLab Get Commits");
        this.config = config;

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(860, 700));

        List<String> hosts = config.getHosts();
        String firstHost = hosts.isEmpty() ? "http://10.1.5.6" : hosts.get(0);

        hostCombo       = new HistoryComboBox(hosts, firstHost);
        tokenCombo      = new HistoryComboBox(config.getList(firstHost, "token"),      "");
        segmentCombo    = new HistoryComboBox(config.getList(firstHost, "segment"),    "Полигон");
        projectIdsCombo = new HistoryComboBox(config.getList(firstHost, "projectIds"), "153");
        sinceCombo      = new HistoryComboBox(config.getList(firstHost, "since"),      "2025-10-01T00:00:01Z");
        untilCombo      = new HistoryComboBox(config.getList(firstHost, "until"),      "2025-11-01T00:00:01Z");

        projectNamesField = new JTextField("");
        projectNamesField.setEditable(false);
        projectNamesField.setForeground(new Color(80, 80, 80));
        projectNamesField.setFont(projectNamesField.getFont().deriveFont(Font.BOLD));
        projectNamesField.setToolTipText("Имена проектов разрешаются автоматически");

        hostCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) reloadCombos(hostCombo.getCurrentValue());
        });

        JTextField idsEditor = (JTextField) projectIdsCombo.getEditor().getEditorComponent();
        idsEditor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { scheduleNameLookup(); }
            public void removeUpdate(DocumentEvent e)  { scheduleNameLookup(); }
            public void changedUpdate(DocumentEvent e) { scheduleNameLookup(); }
        });
        projectIdsCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) scheduleNameLookup();
        });

        // Form
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(12, 12, 8, 12));
        int row = 0;
        addRow(form, row++, "GitLab Host:",                 hostCombo);
        addRow(form, row++, "Token:",                       tokenCombo);
        addRow(form, row++, "Segment:",                     segmentCombo);
        addRow(form, row++, "Project IDs (через запятую):",  projectIdsCombo);
        addRow(form, row++, "Project Names:",                projectNamesField);
        addRow(form, row++, "Since (ISO 8601):",             sinceCombo);
        addRow(form, row++, "Until (ISO 8601):",             untilCombo);

        scheduleNameLookup();

        // Buttons
        runButton  = new JButton("Получить коммиты");
        copyButton = new JButton("Скопировать");
        JButton clearButton = new JButton("Очистить");
        statusLabel = new JLabel(" ");
        statusLabel.setForeground(new Color(50, 120, 50));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        btnPanel.add(runButton);
        btnPanel.add(copyButton);
        btnPanel.add(clearButton);
        btnPanel.add(statusLabel);

        // Progress panel
        progressBar = new JProgressBar(0, 1);
        progressBar.setStringPainted(true);
        progressBar.setString("");
        progressBar.setPreferredSize(new Dimension(0, 22));

        phaseLabel = new JLabel("");
        phaseLabel.setFont(phaseLabel.getFont().deriveFont(Font.PLAIN, 11f));
        phaseLabel.setForeground(new Color(100, 100, 100));

        JPanel progressPanel = new JPanel(new BorderLayout(6, 0));
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.add(phaseLabel,  BorderLayout.EAST);
        progressPanel.setVisible(false);

        // Output area
        outputArea = new JTextArea();
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        outputArea.setLineWrap(false);
        outputScroll = new JScrollPane(outputArea);
        outputScroll.setBorder(BorderFactory.createTitledBorder("Результат (CSV)"));

        runButton.addActionListener(e  -> runFetch());
        copyButton.addActionListener(e -> copyToClipboard());
        clearButton.addActionListener(e -> {
            csvResult = null;
            outputArea.setText("");
            statusLabel.setText(" ");
            setOutputTitle("Результат (CSV)");
        });

        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.add(progressPanel, BorderLayout.NORTH);
        top.add(form,          BorderLayout.CENTER);
        top.add(btnPanel,      BorderLayout.SOUTH);

        setLayout(new BorderLayout(0, 4));
        add(top,         BorderLayout.NORTH);
        add(outputScroll, BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);
    }

    // -----------------------------------------------------------------------
    // Output area title
    // -----------------------------------------------------------------------

    private void setOutputTitle(String title) {
        if (outputScroll.getBorder() instanceof TitledBorder) {
            ((TitledBorder) outputScroll.getBorder()).setTitle(title);
            outputScroll.repaint();
        }
    }

    // -----------------------------------------------------------------------
    // Log helpers
    // -----------------------------------------------------------------------

    private void appendLog(String line) {
        SwingUtilities.invokeLater(() -> {
            if (csvResult != null) return;
            outputArea.append(line + "\n");
            outputArea.setCaretPosition(outputArea.getDocument().getLength());
        });
    }

    // -----------------------------------------------------------------------
    // Progress
    // -----------------------------------------------------------------------

    private JPanel getProgressPanel() {
        return (JPanel) progressBar.getParent();
    }

    private void updateProgress(int d, int t) {
        SwingUtilities.invokeLater(() -> {
            if (t == 0) {
                progressBar.setIndeterminate(true);
                progressBar.setString("считаю...");
            } else {
                progressBar.setIndeterminate(false);
                progressBar.setMaximum(t);
                progressBar.setValue(d);
                progressBar.setString(d + " / " + t);
            }
        });
    }

    private void updatePhase(String phase) {
        appendLog(phase);
        SwingUtilities.invokeLater(() -> phaseLabel.setText(phase));
    }

    // -----------------------------------------------------------------------
    // Project name lookup
    // -----------------------------------------------------------------------

    private void scheduleNameLookup() {
        if (pendingLookup != null && !pendingLookup.isDone()) pendingLookup.cancel(false);
        pendingLookup = scheduler.schedule(this::doNameLookup, 600, TimeUnit.MILLISECONDS);
    }

    private void doNameLookup() {
        String host   = hostCombo.getCurrentValue();
        String token  = tokenCombo.getCurrentValue();
        String idsStr = projectIdsCombo.getCurrentValue();

        if (host.isEmpty() || token.isEmpty() || idsStr.isEmpty()) {
            SwingUtilities.invokeLater(() -> projectNamesField.setText(""));
            return;
        }

        List<Integer> ids;
        try {
            ids = Arrays.stream(idsStr.split("[,;\\s]+"))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());
        } catch (NumberFormatException e) {
            SwingUtilities.invokeLater(() -> projectNamesField.setText("— некорректный ID"));
            return;
        }

        if (ids.isEmpty()) {
            SwingUtilities.invokeLater(() -> projectNamesField.setText(""));
            return;
        }

        SwingUtilities.invokeLater(() -> projectNamesField.setText("…"));

        nameResolver.resolve(host, token, ids)
                .thenAccept(nameMap -> {
                    String names = ids.stream()
                            .map(id -> nameMap.getOrDefault(id, "#" + id))
                            .collect(Collectors.joining(", "));
                    SwingUtilities.invokeLater(() -> projectNamesField.setText(names));
                })
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> projectNamesField.setText("— ошибка запроса"));
                    return null;
                });
    }

    // -----------------------------------------------------------------------
    // Combos reload
    // -----------------------------------------------------------------------

    private void reloadCombos(String host) {
        reloadCombo(tokenCombo,      config.getList(host, "token"),      "");
        reloadCombo(segmentCombo,    config.getList(host, "segment"),     "Полигон");
        reloadCombo(projectIdsCombo, config.getList(host, "projectIds"),  "153");
        reloadCombo(sinceCombo,      config.getList(host, "since"),       "2025-10-01T00:00:01Z");
        reloadCombo(untilCombo,      config.getList(host, "until"),       "2025-11-01T00:00:01Z");
        scheduleNameLookup();
    }

    private void reloadCombo(HistoryComboBox combo, List<String> items, String def) {
        combo.removeAllItems();
        for (String item : items) combo.addItem(item);
        if (items.isEmpty() && !def.isEmpty()) combo.addItem(def);
        if (combo.getItemCount() > 0) combo.setSelectedIndex(0);
    }

    // -----------------------------------------------------------------------
    // Main fetch
    // -----------------------------------------------------------------------

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

        // UI reset -> log mode
        csvResult = null;
        runButton.setEnabled(false);
        copyButton.setEnabled(false);
        outputArea.setText("");
        statusLabel.setText(" ");
        progressBar.setMaximum(1);
        progressBar.setValue(0);
        progressBar.setIndeterminate(true);
        progressBar.setString("считаю...");
        phaseLabel.setText("");
        getProgressPanel().setVisible(true);
        setOutputTitle("Лог выполнения");

        GitLabService service = new GitLabService(
                host, token, segment, since, until,
                this::updateProgress,
                this::updatePhase);

        service.fetchProjects(projectIds)
                .thenAccept(commits -> SwingUtilities.invokeLater(() -> {
                    int n = commits.size();
                    progressBar.setIndeterminate(false);
                    progressBar.setMaximum(Math.max(1, progressBar.getMaximum()));
                    progressBar.setValue(progressBar.getMaximum());
                    progressBar.setString("Готово — " + n + " коммитов");
                    phaseLabel.setText("");

                    if (n == 0) {
                        statusLabel.setText("Коммитов не найдено");
                        setOutputTitle("Результат (CSV)");
                    } else {
                        String csv = commits.stream()
                                .sorted(Comparator.comparing(CommitDetail::projectName)
                                        .thenComparing(CommitDetail::committedDate))
                                .map(CommitDetail::toCsvRow)
                                .collect(Collectors.joining("\n"));
                        csvResult = csv;
                        outputArea.setText(csv);
                        outputArea.setCaretPosition(0);
                        statusLabel.setText("Найдено: " + n);
                        setOutputTitle("Результат (CSV)");
                    }
                    runButton.setEnabled(true);
                    copyButton.setEnabled(true);
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setIndeterminate(false);
                        progressBar.setString("Ошибка");
                        phaseLabel.setText("");
                        statusLabel.setText("Ошибка: " +
                                (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                        setOutputTitle("Лог выполнения");
                        runButton.setEnabled(true);
                    });
                    return null;
                });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
