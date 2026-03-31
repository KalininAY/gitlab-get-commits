package com.example.gitlabcommits;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        // Disable SSL certificate verification globally (self-signed / corporate CA)
        TrustAllCerts.install();

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        AppConfig config = new AppConfig();
        SwingUtilities.invokeLater(() -> new MainWindow(config).setVisible(true));
    }
}
