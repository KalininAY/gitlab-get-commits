package com.example.gitlabcommits;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.*;
import java.util.List;

public class MainWindow extends JFrame {

    private final AppConfig config;

    private final JTextField hostField;
    private final JPasswordField tokenField;
    private final JTextField projectIdField;
    private final JTextField projectNameField;
    private final JTextField segmentField;
    private final JTextField sinceField;
    private final JTextField untilField;

    private final JTextArea outputArea;
    private final JButton runButton;
    private final JLabel statusLabel;

    public MainWindow(AppConfig config) {
        super("GitLab Get Commits");
        this.config = config;

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(750, 600));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(12, 12, 8, 12));
        GridBagConstraints lc = labelConstraints();
        GridBagConstraints fc = fieldConstraints();

        hostField        = new JTextField(config.get("host",        "http://10.1.5.6"), 30);
        tokenField       = new JPasswordField(config.get("token",   ""), 30);
        projectIdField   = new JTextField(config.get("projectId",   "153"), 10);
        projectNameField = new JTextField(config.get("projectName", "asdko-core"), 20);
        segmentField     = new JTextField(config.get("segment",     "\u041f\u043e\u043b\u0438\u0433\u043e\u043d"), 20);
        sinceField       = new JTextField(config.get("since",       "2025-10-01T00:00:01Z"), 22);
        untilField       = new JTextField(config.get("until",       "2025-11-01T00:00:01Z"), 22);

        int row = 0;
        addRow(form, lc, fc, row++, "GitLab Host:",  hostField);
        addRow(form, lc, fc, row++, "Token:",        tokenField);
        addRow(form, lc, fc, row++, "Project ID:",   projectIdField);
        addRow(form, lc, fc, row++, "Project Name:", projectNameField);
        addRow(form, lc, fc, row++, "Segment:",      segmentField);
        addRow(form, lc, fc, row++, "Since (ISO):",  sinceField);
        addRow(form, lc, fc, row++, "Until (ISO):",  untilField);

        runButton   = new JButton("\u041f\u043e\u043b\u0443\u0447\u0438\u0442\u044c \u043a\u043e\u043c\u043c\u0438\u0442\u044b");
        JButton copyButton  = new JButton("\u0421\u043a\u043e\u043f\u0438\u0440\u043e\u0432\u0430\u0442\u044c");
        JButton clearButton = new JButton("\u041e\u0447\u0438\u0441\u0442\u0438\u0442\u044c");
        statusLabel = new JLabel(" ");
        statusLabel.setForeground(new Color(60, 130, 60));

        runButton.addActionListener(e -> runFetch());
        copyButton.addActionListener(e -> copyToClipboard());
        clearButton.addActionListener(e -> outputArea.setText(""));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        btnPanel.add(runButton);
        btnPanel.add(copyButton);
        btnPanel.add(clearButton);
        btnPanel.add(statusLabel);

        outputArea = new JTextArea();
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        outputArea.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(outputArea);
        scroll.setBorder(BorderFactory.createTitledBorder("\u0420\u0435\u0437\u0443\u043b\u044c\u0442\u0430\u0442 (CSV)"));

        JPanel top = new JPanel(new BorderLayout());
        top.add(form, BorderLayout.CENTER);
        top.add(btnPanel, BorderLayout.SOUTH);

        setLayout(new BorderLayout(0, 4));
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);
    }

    private void runFetch() {
        String host         = hostField.getText().trim();
        String token        = new String(tokenField.getPassword()).trim();
        String projectIdStr = projectIdField.getText().trim();
        String projectName  = projectNameField.getText().trim();
        String segment      = segmentField.getText().trim();
        String since        = sinceField.getText().trim();
        String until        = untilField.getText().trim();

        if (host.isEmpty() || token.isEmpty() || projectIdStr.isEmpty()) {
            JOptionPane.showMessageDialog(this, "\u0417\u0430\u043f\u043e\u043b\u043d\u0438\u0442\u0435 Host, Token \u0438 Project ID", "\u041e\u0448\u0438\u0431\u043a\u0430", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int projectId;
        try {
            projectId = Integer.parseInt(projectIdStr);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Project ID \u0434\u043e\u043b\u0436\u0435\u043d \u0431\u044b\u0442\u044c \u0447\u0438\u0441\u043b\u043e\u043c", "\u041e\u0448\u0438\u0431\u043a\u0430", JOptionPane.ERROR_MESSAGE);
            return;
        }

        config.set("host",        host);
        config.set("token",       token);
        config.set("projectId",   projectIdStr);
        config.set("projectName", projectName);
        config.set("segment",     segment);
        config.set("since",       since);
        config.set("until",       until);
        config.save();

        runButton.setEnabled(false);
        statusLabel.setText("\u0417\u0430\u0433\u0440\u0443\u0437\u043a\u0430...");
        outputArea.setText("");

        GitLabClient client = new GitLabClient(host, token, projectId, since, until, segment, projectName);

        client.fetchAllCommits()
                .thenAccept(commits -> SwingUtilities.invokeLater(() -> {
                    if (commits.isEmpty()) {
                        statusLabel.setText("\u041a\u043e\u043c\u043c\u0438\u0442\u043e\u0432 \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d\u043e");
                    } else {
                        String csv = commits.stream()
                                .sorted((a, b) -> a.committedDate().compareTo(b.committedDate()))
                                .map(CommitDetail::toCsvRow)
                                .distinct()
                                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
                        outputArea.setText(csv);
                        statusLabel.setText("\u0413\u043e\u0442\u043e\u0432\u043e: " + commits.size() + " \u043a\u043e\u043c\u043c\u0438\u0442\u043e\u0432");
                    }
                    runButton.setEnabled(true);
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("\u041e\u0448\u0438\u0431\u043a\u0430: " + ex.getMessage());
                        runButton.setEnabled(true);
                    });
                    return null;
                });
    }

    private void copyToClipboard() {
        String text = outputArea.getText();
        if (text.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        statusLabel.setText("\u0421\u043a\u043e\u043f\u0438\u0440\u043e\u0432\u0430\u043d\u043e \u0432 \u0431\u0443\u0444\u0435\u0440 \u043e\u0431\u043c\u0435\u043d\u0430");
    }

    private void addRow(JPanel panel, GridBagConstraints lc, GridBagConstraints fc,
                        int row, String label, JComponent field) {
        lc.gridy = row;
        fc.gridy = row;
        panel.add(new JLabel(label), lc);
        panel.add(field, fc);
    }

    private GridBagConstraints labelConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(4, 4, 4, 8);
        return c;
    }

    private GridBagConstraints fieldConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets = new Insets(4, 0, 4, 4);
        return c;
    }
}
