package officemgtapp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

/** Runs the workflow against a disposable MySQL instance using its working-directory settings file. */
public final class OperationsSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--order-entry")) {
            SwingUtilities.invokeAndWait(OperationsSmoke::orderEntry);
            System.out.println("Inventory dropdown, quantity controls, and calendar component passed.");
            return;
        }
        Database database = new Database();
        if (args.length == 1 && args[0].equals("--verify-demo")) {
            verifyDemo(database);
            return;
        }
        check(args.length == 1 && args[0].equals("--disposable"), "Pass --disposable for a fresh test instance.");
        database.initialize();
        database.initialize();
        DemoData.load(database);
        DemoData.load(database);
        verifyDemo(database);

        Accounts accounts = new Accounts(database);
        char[] password = "SmokePassword123!".toCharArray();
        accounts.signup("smoke_employee", "9550000001", password, false);
        accounts.signup("smoke_admin", "9550000002", password, true);
        accounts.signup("smoke_rejected", "9550000003", password, true);
        check(accounts.login("smoke_admin", password) == null, "Pending admin was able to sign in.");
        check(accounts.login("smoke_employee", "wrong".toCharArray()) == null, "Wrong password was accepted.");
        var original = accounts.login(Database.ADMIN_USERNAME, Database.ADMIN_PASSWORD.toCharArray());
        var employee = accounts.login("smoke_employee", password);
        var employee2 = accounts.login("smoke_employee", password);
        reject(() -> accounts.pendingRequests(employee));
        for (var request : accounts.pendingRequests(original)) {
            accounts.review(original, request.id(), request.username().equals("smoke_admin"));
        }
        check(accounts.login("smoke_rejected", password) == null, "Rejected admin was able to sign in.");
        var admin = accounts.login("smoke_admin", password);
        check(admin.role() == Accounts.Role.ADMIN, "Approved user did not get the admin role.");
        reject(() -> accounts.pendingRequests(admin));

        Operations operations = new Operations(database, employee);
        Operations other = new Operations(database, employee2);
        Operations monitor = new Operations(database, admin);
        long customer = operations.addCustomer("Smoke Customer", "", "Pune", "", "");
        operations.editCustomer(customer, "Updated Smoke Customer", "", "Pune", "", "");
        reject(() -> monitor.addCustomer("Forbidden customer", "", "", "", ""));
        reject(operations::employees);
        check(monitor.employees().stream().anyMatch(user -> user.username().equals("smoke_employee")),
                "Employee missing from admin directory.");
        operations.addInventory("First item", "", "", "", 3);
        operations.addInventory("Second item", "", "", "", 1);
        long first = inventoryId(operations, "First item");
        long second = inventoryId(operations, "Second item");
        reject(() -> operations.addOrder(customer, List.of(new Operations.Item(first, 4, amount("1.00"))), null));
        reject(() -> operations.addOrder(customer, List.of(new Operations.Item(first, 2, amount("1.00")),
                new Operations.Item(first, 2, amount("1.00"))), null));
        operations.adjustStock(first, -1);
        reject(() -> operations.addOrder(customer, List.of(new Operations.Item(first, 3, amount("1.00"))), null));
        LocalDate delivery = LocalDate.of(2027, 1, 1);
        long order = operations.addOrder(customer, List.of(new Operations.Item(first, 2, amount("3000.00")),
                new Operations.Item(second, 1, amount("4000.00"))), delivery);
        var received = find(operations, order);
        check(received.status() == Operations.Status.RECEIVED && delivery.equals(received.expectedDelivery()),
                "Initial order status or expected delivery date is wrong.");
        check(stock(operations, first) == 0 && stock(operations, second) == 0,
                "Saving the order did not deduct stock.");
        reject(() -> operations.advance(order, Operations.Status.ACCEPTED));
        reject(() -> operations.remind(order));
        operations.advance(order, Operations.Status.RECEIVED);
        operations.advance(order, Operations.Status.ACCEPTED);
        operations.remind(order);
        reject(() -> operations.advance(order, Operations.Status.PACKED));
        operations.recordPayment(order, amount("4000.00"), "Transfer", "Part 1");
        check(find(operations, order).balance().compareTo(amount("6000.00")) == 0, "Partial payment total is wrong.");
        reject(() -> operations.recordPayment(order, amount("6000.01"), "Transfer", ""));
        reject(() -> operations.recordPayment(order, amount("0.001"), "Transfer", ""));
        reject(() -> operations.advance(order, Operations.Status.PACKED));
        competingPayments(operations, other, order);
        check(find(operations, order).balance().signum() == 0, "Final payment did not clear balance.");
        reject(() -> operations.remind(order));
        operations.advance(order, Operations.Status.PACKED);
        operations.advance(order, Operations.Status.DISPATCHED);
        operations.advance(order, Operations.Status.SHIPPED);
        reject(() -> operations.advance(order, Operations.Status.DELIVERED));
        var report = find(monitor, order);
        check(report.status() == Operations.Status.DELIVERED && report.milestones().size() == 6
                && report.milestones().values().stream().allMatch(text -> text.contains("smoke_employee")),
                "Admin progress grid is missing status history or attribution.");
        var events = monitor.activity(order);
        check(events.size() == 9 && events.stream().anyMatch(event -> event.type().equals("Demo reminder")),
                "Admin order activity is incomplete.");
        operations.archiveCustomer(customer);
        check(operations.customers().stream().noneMatch(entry -> entry.id() == customer)
                && monitor.customers().stream().anyMatch(entry -> entry.id() == customer && entry.archived())
                && find(monitor, order).customer().equals("Updated Smoke Customer"), "Customer archival lost history.");
        reject(() -> operations.addOrder(customer, List.of(new Operations.Item(first, 1, amount("1.00"))), null));

        operations.addInventory("Smoke stock", "", "", "", 4);
        var stock = monitor.inventory().stream().filter(item -> item.product().equals("Smoke stock")).findFirst().orElseThrow();
        operations.adjustStock(stock.id(), -2);
        reject(() -> operations.adjustStock(stock.id(), -3));
        check(monitor.inventory().stream().anyMatch(item -> item.id() == stock.id() && item.stock() == 2),
                "Stock adjustment did not preserve the correct quantity.");
        long stockCustomer = operations.addCustomer("Stock test customer", "", "", "", "");
        operations.addInventory("Reservation test item", "", "", "", 25);
        long reservation = inventoryId(operations, "Reservation test item");
        operations.addOrder(stockCustomer, List.of(new Operations.Item(reservation, 1, amount("1.00")),
                new Operations.Item(reservation, 2, amount("1.00"))), null);
        check(stock(operations, reservation) == 22, "Repeated item rows did not deduct a combined quantity of three.");
        reject(() -> operations.addOrder(customer, List.of(new Operations.Item(reservation, 1, amount("1.00"))), null));
        check(stock(operations, reservation) == 22, "A rejected order deducted stock.");
        competingOrders(operations, other, stockCustomer, reservation);
        check(stock(operations, reservation) == 2, "Concurrent orders did not preserve the correct stock.");

        accounts.logout(employee);
        reject(operations::customers);
        var sessions = accounts.employeeActivity(admin, LocalDate.now(ZoneOffset.UTC)).stream()
                .filter(entry -> entry.username().equals("smoke_employee")).toList();
        check(sessions.size() == 2 && sessions.stream().allMatch(entry -> entry.dailyLogins() == 2)
                && sessions.stream().filter(entry -> entry.logout() != null).count() == 1,
                "Session timestamps or daily counts are wrong.");
        check(accounts.beginReset("9550000009") == null, "Unknown phone started a reset.");
        check(accounts.beginReset("bad phone") == null, "Invalid phone started a reset.");
        check(accounts.beginReset("9999999999") != null, "The registered original admin phone was not recognized.");
        var challenge = accounts.beginReset("9550000001");
        char[] replacement = "SmokeReplacement123!".toCharArray();
        reject(() -> accounts.resetPassword(challenge, replacement));
        check(!accounts.verifyResetCode(challenge, "1234".toCharArray()), "Wrong OTP was accepted.");
        check(!accounts.verifyResetCode(challenge, "0000".toCharArray()), "Failed challenge could be reused.");
        var validChallenge = accounts.beginReset("9550000001");
        check(accounts.verifyResetCode(validChallenge, "0000".toCharArray()), "Correct prototype OTP was rejected.");
        accounts.resetPassword(validChallenge, replacement);
        reject(() -> accounts.resetPassword(validChallenge, replacement));
        check(accounts.login("smoke_employee", password) == null, "Old password survived reset.");
        accounts.logout(accounts.login("smoke_employee", replacement));
        accounts.logout(employee2);
        accounts.logout(admin);
        accounts.logout(original);
        System.out.println("Fresh setup, demo data, access, order history, payment and dispatch concurrency, inventory, sessions, and reset passed.");
    }

    private static void verifyDemo(Database database) throws Exception {
        try (var connection = database.connect(); var query = connection.createStatement()) {
            try (var result = query.executeQuery("""
                    SELECT username, phone, role, password_hash FROM users
                    WHERE username IN ('admin123', 'demo_employee', 'demo_admin')
                    """)) {
                int found = 0;
                while (result.next()) {
                    String username = result.getString(1);
                    boolean root = username.equals(Database.ADMIN_USERNAME);
                    String role = root ? "ROOT_ADMIN" : username.equals("demo_employee") ? "EMPLOYEE" : "ADMIN";
                    check(role.equals(result.getString(3)), "Demo role mismatch.");
                    check(!root || "9999999999".equals(result.getString(2)), "Original admin phone mismatch.");
                    check(Passwords.matches((root ? Database.ADMIN_PASSWORD : DemoData.PASSWORD).toCharArray(),
                            result.getString(4)), "Demo credentials mismatch.");
                    found++;
                }
                check(found == 3, "Demo accounts are missing.");
            }
            try (var result = query.executeQuery("""
                    SELECT o.current_status, p.amount
                    FROM orders o JOIN payments p ON p.order_id = o.id
                    WHERE o.demo_key IN ('demo-packed', 'demo-delivered') ORDER BY o.demo_key
                    """)) {
                check(result.next() && result.getString(1).equals("DELIVERED")
                        && result.getBigDecimal(2).compareTo(amount("2500.00")) == 0, "Delivered demo data mismatch.");
                check(result.next() && result.getString(1).equals("PACKED")
                        && result.getBigDecimal(2).compareTo(amount("4000.00")) == 0, "Packed demo data mismatch.");
                check(!result.next(), "Demo orders or payments were duplicated.");
            }
        }
        System.out.println("Verified office_mgt and three accounts, one packed demo order, and one delivered demo order.");
    }

    private static void competingPayments(Operations first, Operations second, long order) throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = workers.submit(payment(first, start, order));
            var b = workers.submit(payment(second, start, order));
            start.countDown();
            check(a.get() != b.get(), "Concurrent payment should succeed exactly once.");
        }
    }

    private static Callable<Boolean> payment(Operations operations, CountDownLatch start, long order) {
        return () -> {
            start.await();
            try {
                operations.recordPayment(order, amount("6000.00"), "Transfer", "Final");
                return true;
            } catch (IllegalArgumentException ex) {
                return false;
            }
        };
    }

    private static Operations.Order find(Operations operations, long orderId) throws Exception {
        return operations.orders().stream().filter(order -> order.id() == orderId).findFirst().orElseThrow();
    }

    private static long inventoryId(Operations operations, String product) throws Exception {
        return operations.inventory().stream().filter(item -> item.product().equals(product)).findFirst().orElseThrow().id();
    }

    private static int stock(Operations operations, long id) throws Exception {
        return operations.inventory().stream().filter(item -> item.id() == id).findFirst().orElseThrow().stock();
    }

    private static void competingOrders(Operations first, Operations second, long customer, long inventory) throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = workers.submit(reserve(first, start, customer, inventory));
            var b = workers.submit(reserve(second, start, customer, inventory));
            start.countDown();
            check(a.get() != b.get(), "Concurrent orders should reserve stock exactly once.");
        }
    }

    private static Callable<Boolean> reserve(Operations operations, CountDownLatch start, long customer, long inventory) {
        return () -> {
            start.await();
            try {
                operations.addOrder(customer, List.of(new Operations.Item(inventory, 20, amount("1.00"))), null);
                return true;
            } catch (IllegalArgumentException ex) {
                return false;
            }
        };
    }

    private static void orderEntry() {
        OfficeTheme.install();
        var entry = new OrderEntry(List.of(new Operations.Customer(1, "Customer", null, null, null, null, false)),
                List.of(new Operations.InventoryItem(1, "In-stock item", null, null, null, 3)));
        var dropdowns = components(entry, JComboBox.class);
        check(dropdowns.size() == 2 && dropdowns.get(1).getItemCount() == 1, "Inventory dropdown is missing.");
        var buttons = components(entry, JButton.class);
        var plus = buttons.stream().filter(button -> "+".equals(button.getText())).findFirst().orElseThrow();
        var minus = buttons.stream().filter(button -> "−".equals(button.getText())).findFirst().orElseThrow();
        check(!plus.isEnabled(), "Quantity can increase without an item.");
        dropdowns.get(1).setSelectedIndex(0);
        plus.doClick(0);
        plus.doClick(0);
        check(!plus.isEnabled(), "Plus is enabled at the stock limit.");
        minus.doClick(0);
        check(plus.isEnabled(), "Plus did not re-enable below the stock limit.");
        buttons.stream().filter(button -> "Add item".equals(button.getText())).findFirst().orElseThrow().doClick(0);
        components(entry, JComboBox.class).get(2).setSelectedIndex(0);
        check(components(entry, JButton.class).stream().filter(button -> "+".equals(button.getText()))
                .noneMatch(JButton::isEnabled), "Repeated items exceeded the combined stock limit.");
        var date = components(entry, com.toedter.calendar.JDateChooser.class).getFirst();
        check(date.getCalendarButton().isEnabled(), "Expected delivery calendar is unavailable.");
    }

    private static <T extends Component> List<T> components(Container parent, Class<T> type) {
        List<T> matches = new ArrayList<>();
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component)) {
                matches.add(type.cast(component));
            }
            if (component instanceof Container children) {
                matches.addAll(components(children, type));
            }
        }
        return matches;
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static void reject(CheckedAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected action to be rejected.");
        } catch (IllegalArgumentException expected) {
            // Rejected by an application rule.
        }
    }
}
