package officemgtapp;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.Arrays;
import javax.swing.*;

final class ResetPassword extends JDialog {
    ResetPassword(Login owner, Accounts accounts, Accounts.ResetChallenge challenge) {
        super(owner, "Reset password", true);
        JPasswordField password = new JPasswordField(18);
        JPasswordField confirmation = new JPasswordField(18);
        JPanel form = OfficeTheme.transparent(new GridLayout(0, 2, 12, 10));
        Login.addField(form, "New password", password);
        Login.addField(form, "Confirm password", confirmation);
        JButton submit = new JButton("Reset password");
        JPanel content = OfficeTheme.card(new BorderLayout(0, 14), 24);
        content.add(OfficeTheme.heading("Reset password", 24f), BorderLayout.NORTH);
        content.add(form, BorderLayout.CENTER);
        content.add(submit, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(submit);
        submit.addActionListener(event -> {
            char[] secret = password.getPassword();
            char[] repeated = confirmation.getPassword();
            boolean matches = Arrays.equals(secret, repeated);
            Arrays.fill(repeated, '\0');
            password.setText("");
            confirmation.setText("");
            if (!matches) {
                Arrays.fill(secret, '\0');
                JOptionPane.showMessageDialog(this, "The passwords do not match.");
                return;
            }
            Login.runTask(this, () -> {
                try {
                    accounts.resetPassword(challenge, secret);
                    return null;
                } finally {
                    Arrays.fill(secret, '\0');
                }
            }, ignored -> {
                JOptionPane.showMessageDialog(this, "Password reset. Sign in using your new password.");
                dispose();
            });
        });
        pack();
        setLocationRelativeTo(owner);
    }
}
