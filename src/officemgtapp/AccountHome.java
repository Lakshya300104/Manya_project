package officemgtapp;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalDate;
import java.time.ZoneOffset;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

final class AccountHome extends JFrame {
    private final Accounts accounts;
    private final Accounts.Session session;

    AccountHome(Database database, Accounts accounts, Accounts.Session session) {
        super(session.role() == Accounts.Role.EMPLOYEE ? "Office Management | Employee" : "Office Management | Admin");
        this.accounts = accounts;
        this.session = session;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                logout(true);
            }
        });
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(15, 20, 16, 20));
        boolean employee = session.role() == Accounts.Role.EMPLOYEE;
        var operations = new Operations(database, session);
        var workspace = new OperationsPanel(this, operations, employee);
        JPanel header = OfficeTheme.card(new BorderLayout(12, 0), 19);
        JPanel identity = OfficeTheme.transparent(new BorderLayout(0, 5));
        identity.add(OfficeTheme.heading(employee ? "Employee workspace" : "Admin overview", 25f), BorderLayout.NORTH);
        identity.add(OfficeTheme.secondary("Signed in as " + session.username()), BorderLayout.SOUTH);
        header.add(identity, BorderLayout.WEST);
        JPanel actions = OfficeTheme.transparent(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        if (session.role() != Accounts.Role.EMPLOYEE) {
            JButton employees = new JButton("Employees");
            employees.addActionListener(event -> showEmployees(operations));
            actions.add(employees);
            JButton activity = new JButton("Employee login activity");
            activity.addActionListener(event -> showActivity());
            actions.add(activity);
        }
        if (session.role() == Accounts.Role.ROOT_ADMIN) {
            JButton requests = new JButton("Pending admin requests");
            requests.addActionListener(event -> showRequests());
            actions.add(requests);
        }
        JButton logout = new JButton("Log out");
        logout.addActionListener(event -> logout(false));
        actions.add(logout);
        header.add(actions, BorderLayout.EAST);
        JPanel overview = OfficeTheme.transparent(new BorderLayout(0, 5));
        overview.add(header, BorderLayout.NORTH);
        overview.add(workspace.overview(), BorderLayout.SOUTH);
        content.add(overview, BorderLayout.NORTH);
        content.add(workspace, BorderLayout.CENTER);
        setContentPane(content);
        pack();
        setLocationRelativeTo(null);
        workspace.refresh();
    }

    private void showEmployees(Operations operations) {
        Login.runTask(this, operations::employees, employees -> {
            var model = new DefaultTableModel(new String[] {"Employee", "Phone", "Account", "Created (UTC)"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            for (var employee : employees) {
                model.addRow(new Object[] {employee.username(), employee.phone(),
                        employee.active() ? "Active" : "Inactive", employee.createdAt()});
            }
            var table = OfficeTheme.table();
            table.setModel(model);
            var pane = OfficeTheme.scroll(table);
            pane.setPreferredSize(new Dimension(560, 240));
            JOptionPane.showMessageDialog(this, pane, "Employees", JOptionPane.PLAIN_MESSAGE);
        });
    }

    private void showRequests() {
        Login.runTask(this, () -> accounts.pendingRequests(session), requests -> {
            if (requests.isEmpty()) {
                JOptionPane.showMessageDialog(this, "There are no pending admin requests.");
                return;
            }
            var choices = new JComboBox<>(requests.toArray(Accounts.Request[]::new));
            Object[] options = {"Approve", "Reject", "Close"};
            int action = JOptionPane.showOptionDialog(this, choices, "Pending admin requests",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[2]);
            if (action == 0 || action == 1) {
                var request = (Accounts.Request) choices.getSelectedItem();
                Login.runTask(this, () -> {
                    accounts.review(session, request.id(), action == 0);
                    return null;
                }, ignored -> JOptionPane.showMessageDialog(this, action == 0 ? "Admin approved." : "Request rejected."));
            }
        });
    }

    private void showActivity() {
        String date = JOptionPane.showInputDialog(this, "Login date (YYYY-MM-DD, UTC)", LocalDate.now(ZoneOffset.UTC));
        if (date == null) {
            return;
        }
        Login.runTask(this, () -> accounts.employeeActivity(session, LocalDate.parse(date.strip())), activity -> {
            var model = new DefaultTableModel(new String[] {"Employee", "Login (UTC)", "Logout (UTC)", "Daily logins"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            for (var entry : activity) {
                model.addRow(new Object[] {entry.username(), entry.login(),
                        entry.logout() == null ? "Unclosed" : entry.logout(), entry.dailyLogins()});
            }
            var table = OfficeTheme.table();
            table.setModel(model);
            var pane = OfficeTheme.scroll(table);
            pane.setPreferredSize(new Dimension(660, 260));
            JOptionPane.showMessageDialog(this, pane, "Employee login activity", JOptionPane.PLAIN_MESSAGE);
        });
    }

    private void logout(boolean close) {
        if (!isEnabled()) {
            return;
        }
        Login.runTask(this, () -> {
            accounts.logout(session);
            return null;
        }, ignored -> {
            if (!close) {
                new Login().setVisible(true);
            }
            dispose();
        });
    }
}
