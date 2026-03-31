package com.example.gitlabcommits;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        AppConfig config = new AppConfig();
        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow(config);
            window.setVisible(true);
        });
    }
}
