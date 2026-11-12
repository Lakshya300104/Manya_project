package officemgtapp;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.Arrays;
import javax.swing.*;

final class SignUp extends JDialog {
    private final JTextField username = new JTextField(22);
    private final JTextField phone = new JTextField(22);
    private final JPasswordField password = new JPasswordField(22);
    private final JPasswordField confirmation = new JPasswordField(22);
    private final JComboBox<String> role = new JComboBox<>(new String[] {"Employee", "Admin"});

    SignUp(Login owner, Accounts accounts) {
        super(owner, "Create account", true);
        JPanel form = OfficeTheme.transparent(new GridLayout(0, 2, 12, 10));
        Login.addField(form, "Username", username);
        Login.addField(form, "Phone number", phone);
        Login.addField(form, "Password", password);
        Login.addField(form, "Confirm password", confirmation);
        Login.addField(form, "Access type", role);
        JButton submit = new JButton("Submit");
        JButton cancel = new JButton("Cancel");
        JPanel actions = OfficeTheme.transparent(new FlowLayout(FlowLayout.RIGHT));
        actions.add(cancel);
        actions.add(submit);
        JPanel content = OfficeTheme.card(new BorderLayout(0, 14), 24);
        content.add(OfficeTheme.heading("Create account", 24f), BorderLayout.NORTH);
        content.add(form, BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(submit);
        cancel.addActionListener(event -> dispose());
        submit.addActionListener(event -> submit(accounts));
        pack();
        setLocationRelativeTo(owner);
    }

    private void submit(Accounts accounts) {
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
        String name = username.getText();
        String number = phone.getText();
        boolean admin = role.getSelectedIndex() == 1;
        Login.runTask(this, () -> {
            try {
                accounts.signup(name, number, secret, admin);
                return null;
            } finally {
                Arrays.fill(secret, '\0');
            }
        }, ignored -> {
            JOptionPane.showMessageDialog(this, admin
                    ? "Admin request submitted. The original admin must approve it before you can sign in."
                    : "Account created. You can now sign in.");
            dispose();
        });
    }
}
