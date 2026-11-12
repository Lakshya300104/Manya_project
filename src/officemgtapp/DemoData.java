package officemgtapp;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class DemoData {
    static final String PASSWORD = "OfficeDemo123!";

    private DemoData() {}

    static void load(Database database) throws SQLException {
        try (Connection connection = database.connect(); Statement lock = connection.createStatement()) {
            try (ResultSet result = lock.executeQuery("SELECT GET_LOCK('office_mgt_demo', 10)")) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new SQLException("Could not acquire the demo setup lock.");
                }
            }
            loadOnce(database);
        }
    }

    private static void loadOnce(Database database) throws SQLException {
        Accounts accounts = new Accounts(database);
        char[] password = PASSWORD.toCharArray();
        var original = accounts.login(Database.ADMIN_USERNAME, Database.ADMIN_PASSWORD.toCharArray());
        if (original == null || original.role() != Accounts.Role.ROOT_ADMIN) {
            throw new IllegalArgumentException("Demo setup needs the original admin credentials.");
        }
        try {
            var employee = accounts.login("demo_employee", password);
            if (employee == null) {
                accounts.signup("demo_employee", "9000000001", password, false);
                employee = accounts.login("demo_employee", password);
            }
            try {
                var admin = accounts.login("demo_admin", password);
                if (admin == null) {
                    var request = accounts.pendingRequests(original).stream()
                            .filter(entry -> entry.username().equals("demo_admin") && entry.phone().equals("9000000002"))
                            .findFirst();
                    if (request.isEmpty()) {
                        accounts.signup("demo_admin", "9000000002", password, true);
                        request = accounts.pendingRequests(original).stream()
                                .filter(entry -> entry.username().equals("demo_admin") && entry.phone().equals("9000000002"))
                                .findFirst();
                    }
                    accounts.review(original, request.orElseThrow().id(), true);
                } else {
                    accounts.logout(admin);
                }
                Operations operations = new Operations(database, employee);
                operations.seedOrder("demo-packed", "Demo Customer – Balance Due", new BigDecimal("10000.00"),
                        new BigDecimal("4000.00"), Operations.Status.PACKED);
                operations.seedOrder("demo-delivered", "Demo Customer – Delivered", new BigDecimal("2500.00"),
                        new BigDecimal("2500.00"), Operations.Status.DELIVERED);
                if (operations.inventory().stream().noneMatch(item -> item.product().equals("Demo product")
                        && "Demo manufacturer".equals(item.manufacturer()))) {
                    operations.addInventory("Demo product", "Demo manufacturer", "A", "2 mm", 25);
                }
            } finally {
                accounts.logout(employee);
            }
        } finally {
            accounts.logout(original);
        }
    }
}
