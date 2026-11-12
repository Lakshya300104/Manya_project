package officemgtapp;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.EventQueue;
import java.awt.Window;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;

public final class Login extends JFrame {
    private final JTextField username = new JTextField(22);
    private final JPasswordField password = new JPasswordField(22);
    private final JButton signIn = new JButton("Sign in");
    private final JButton signup = new JButton("Create account");
    private final JButton reset = new JButton("Forgot password");
    private final JLabel status = OfficeTheme.secondary("Connecting to MySQL…");
    private Accounts accounts;
    private Database database;

    public Login() {
        this(false);
    }

    Login(boolean showSignup) {
        super("Office Management | Sign in");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        JPanel form = OfficeTheme.transparent(new GridLayout(0, 1, 0, 7));
        addField(form, "Username", username);
        addField(form, "Password", password);
        JPanel secondaryActions = OfficeTheme.transparent(new FlowLayout(FlowLayout.CENTER, 5, 0));
        secondaryActions.add(signup);
        secondaryActions.add(reset);
        JPanel actions = OfficeTheme.transparent(new BorderLayout(0, 8));
        actions.add(signIn, BorderLayout.NORTH);
        actions.add(secondaryActions, BorderLayout.SOUTH);
        JPanel heading = OfficeTheme.transparent(new GridLayout(0, 1, 0, 5));
        heading.add(OfficeTheme.secondary("Office Management"));
        heading.add(OfficeTheme.heading("Sign in", 30f));
        heading.add(status);
        JPanel card = OfficeTheme.card(new BorderLayout(0, 18), 29);
        card.add(heading, BorderLayout.NORTH);
        card.add(form, BorderLayout.CENTER);
        card.add(actions, BorderLayout.SOUTH);
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(22, 24, 24, 24));
        content.add(card);
        setContentPane(content);
        getRootPane().setDefaultButton(signIn);
        signIn.setEnabled(false);
        signup.setEnabled(false);
        reset.setEnabled(false);
        signIn.addActionListener(event -> signIn());
        signup.addActionListener(event -> new SignUp(this, accounts).setVisible(true));
        reset.addActionListener(event -> resetPassword());
        pack();
        setLocationRelativeTo(null);
        runTask(this, () -> {
            Database database = new Database();
            database.initialize();
            return database;
        }, result -> {
            database = result;
            accounts = new Accounts(database);
            status.setText("Sign in to continue");
            signIn.setEnabled(true);
            signup.setEnabled(true);
            reset.setEnabled(Accounts.demoResetEnabled());
            if (showSignup) {
                new SignUp(this, accounts).setVisible(true);
            }
        }, () -> status.setText("Database setup failed. Check settings and reopen the app."));
    }

    private void signIn() {
        String name = username.getText();
        char[] secret = password.getPassword();
        password.setText("");
        runTask(this, () -> {
            try {
                return accounts.login(name, secret);
            } finally {
                Arrays.fill(secret, '\0');
            }
        }, session -> {
            if (session == null) {
                status.setText("Sign-in failed. Check your credentials or approval status.");
            } else {
                new AccountHome(database, accounts, session).setVisible(true);
                dispose();
            }
        });
    }

    private void resetPassword() {
        String phone = JOptionPane.showInputDialog(this, "Enter the phone number used at signup.");
        if (phone != null) {
            runTask(this, () -> accounts.beginReset(phone), challenge -> {
                if (challenge == null) {
                    resetFailed();
                } else {
                    verifyResetCode(challenge);
                }
            });
        }
    }

    private void verifyResetCode(Accounts.ResetChallenge challenge) {
        JPasswordField otp = new JPasswordField(10);
        JPanel prompt = OfficeTheme.transparent(new GridLayout(0, 1, 0, 8));
        prompt.add(new JLabel("OTP sent to mobile (prototype)."));
        addField(prompt, "Enter OTP", otp);
        if (JOptionPane.showConfirmDialog(this, prompt, "Verify mobile number",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        char[] code = otp.getPassword();
        otp.setText("");
        boolean verified;
        try {
            verified = accounts.verifyResetCode(challenge, code);
        } finally {
            Arrays.fill(code, '\0');
        }
        if (verified) {
            new ResetPassword(this, accounts, challenge).setVisible(true);
        } else {
            resetFailed();
        }
    }

    private void resetFailed() {
        JOptionPane.showMessageDialog(this, "Verification failed. Try again.");
        password.setText("");
        username.requestFocusInWindow();
    }

    static void addField(JPanel form, String name, JComponent field) {
        JLabel label = new JLabel(name);
        label.setLabelFor(field);
        form.add(label);
        form.add(field);
    }

    static <T> void runTask(Window window, Callable<T> task, Consumer<T> success) {
        runTask(window, task, success, () -> {});
    }

    private static <T> void runTask(Window window, Callable<T> task, Consumer<T> success, Runnable failure) {
        window.setEnabled(false);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.call();
            }

            @Override
            protected void done() {
                window.setEnabled(true);
                try {
                    success.accept(get());
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    String message = cause instanceof SQLException sql
                            ? "MySQL operation failed (SQLState " + sql.getSQLState() + ", error " + sql.getErrorCode()
                                    + "). Check the connection settings and account permissions."
                            : cause.getMessage();
                    JOptionPane.showMessageDialog(window, message, "Office Management", JOptionPane.ERROR_MESSAGE);
                    failure.run();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    failure.run();
                }
            }
        }.execute();
    }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--setup-demo")) {
            try {
                Database database = new Database();
                database.initialize();
                DemoData.load(database);
                System.out.println("office_mgt is ready. Demo accounts, customers, orders, and inventory are available.");
            } catch (SQLException ex) {
                System.err.printf("MySQL setup failed (SQLState %s, error %d).%n", ex.getSQLState(), ex.getErrorCode());
                System.exit(1);
            } catch (Exception ex) {
                System.err.println(ex.getMessage());
                System.exit(1);
            }
            return;
        }
        EventQueue.invokeLater(() -> {
            OfficeTheme.install();
            new Login().setVisible(true);
        });
    }
}
