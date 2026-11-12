package officemgtapp;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeMap;

final class Operations {
    enum Status {
        RECEIVED("Received"), ACCEPTED("Accepted"), PACKED("Packed"),
        DISPATCHED("Dispatched"), SHIPPED("Shipped"), DELIVERED("Delivered");

        final String label;

        Status(String label) {
            this.label = label;
        }

        Status next() {
            return this == DELIVERED ? null : values()[ordinal() + 1];
        }
    }

    record Customer(long id, String name, String email, String city, String gst, String pan, boolean archived) {
        @Override
        public String toString() {
            return city == null || city.isBlank() ? name : name + " (" + city + ")";
        }
    }

    record Item(long inventoryId, int quantity, BigDecimal unitPrice) {}
    private record OrderLine(Long inventoryId, String product, int quantity, BigDecimal unitPrice) {}
    record InventoryItem(long id, String product, String manufacturer, String grade, String thickness, int stock) {}
    record Employee(String username, String phone, boolean active, String createdAt) {}

    record Order(long id, String customer, String products, Status status, BigDecimal total,
                 BigDecimal paid, LocalDate expectedDelivery, Map<Status, String> milestones) {
        BigDecimal balance() {
            return total.subtract(paid);
        }

        String paymentStatus() {
            if (balance().signum() == 0) {
                return "Paid";
            }
            return paid.signum() == 0 ? "Unpaid" : "Partially paid";
        }
    }

    private record Balance(Status status, BigDecimal due) {}

    record Event(String type, String detail, String employee, String timestamp) {}

    private final Database database;
    private final Accounts.Session session;

    Operations(Database database, Accounts.Session session) {
        this.database = database;
        this.session = session;
    }

    List<Customer> customers() throws SQLException {
        try (Connection connection = open(false);
             PreparedStatement query = query(connection, """
                     SELECT id, name, email, city, gst, pan, archived_at
                     FROM customers WHERE archived_at IS NULL OR ? ORDER BY name, id
                     """, session.role() != Accounts.Role.EMPLOYEE);
             ResultSet rows = query.executeQuery()) {
            List<Customer> customers = new ArrayList<>();
            while (rows.next()) {
                customers.add(new Customer(rows.getLong("id"), rows.getString("name"),
                        rows.getString("email"), rows.getString("city"), rows.getString("gst"),
                        rows.getString("pan"), rows.getObject("archived_at") != null));
            }
            return customers;
        }
    }

    long addCustomer(String name, String email, String city, String gst, String pan) throws SQLException {
        try (Connection connection = open(true)) {
            return insertCustomer(connection, name, email, city, gst, pan);
        }
    }

    private long insertCustomer(Connection connection, String name, String email, String city, String gst,
                                String pan) throws SQLException {
        return insert(connection, """
                INSERT INTO customers (name, email, city, gst, pan, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, field(name, 120, true), field(email, 254, false), field(city, 100, false),
                field(gst, 30, false), field(pan, 30, false), session.userId());
    }

    void archiveCustomer(long id) throws SQLException {
        try (Connection connection = open(true)) {
            update(connection, "UPDATE customers SET archived_at = UTC_TIMESTAMP(6) WHERE id = ? AND archived_at IS NULL", id);
        }
    }

    void editCustomer(long id, String name, String email, String city, String gst, String pan) throws SQLException {
        try (Connection connection = open(true)) {
            update(connection, """
                    UPDATE customers SET name = ?, email = ?, city = ?, gst = ?, pan = ?
                    WHERE id = ? AND archived_at IS NULL
                    """, field(name, 120, true), field(email, 254, false), field(city, 100, false),
                    field(gst, 30, false), field(pan, 30, false), id);
        }
    }

    List<InventoryItem> inventory() throws SQLException {
        try (Connection connection = open(false);
             PreparedStatement query = query(connection, """
                     SELECT id, product, manufacturer, grade, thickness, stock_quantity
                     FROM inventory_items ORDER BY product, id
                     """);
             ResultSet rows = query.executeQuery()) {
            List<InventoryItem> items = new ArrayList<>();
            while (rows.next()) {
                items.add(new InventoryItem(rows.getLong(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getInt(6)));
            }
            return items;
        }
    }

    void addInventory(String product, String manufacturer, String grade, String thickness, int stock) throws SQLException {
        if (stock < 0) {
            throw new IllegalArgumentException("Stock cannot be negative.");
        }
        try (Connection connection = open(true)) {
            update(connection, """
                    INSERT INTO inventory_items (product, manufacturer, grade, thickness, stock_quantity, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                    """, field(product, 120, true), field(manufacturer, 120, false), field(grade, 60, false),
                    field(thickness, 60, false), stock, session.userId());
        }
    }

    void adjustStock(long id, int delta) throws SQLException {
        try (Connection connection = open(true);
             PreparedStatement update = query(connection, """
                     UPDATE inventory_items SET stock_quantity = stock_quantity + ?
                     WHERE id = ? AND stock_quantity + ? BETWEEN 0 AND 2147483647
                     """, delta, id, delta)) {
            if (delta == 0 || update.executeUpdate() != 1) {
                throw new IllegalArgumentException("Enter a stock adjustment that leaves a valid quantity.");
            }
        }
    }

    List<Order> orders() throws SQLException {
        try (Connection connection = open(false)) {
            boolean admin = session.role() != Accounts.Role.EMPLOYEE;
            Map<Long, Map<Status, String>> progress = admin ? progress(connection) : Map.of();
            try (PreparedStatement query = query(connection, """
                    SELECT o.id, c.name, o.current_status, o.expected_delivery_date,
                           items.products, items.total, COALESCE(paid.total, 0.00)
                    FROM orders o JOIN customers c ON c.id = o.customer_id
                    JOIN (
                        SELECT order_id, GROUP_CONCAT(product ORDER BY id SEPARATOR ', ') AS products,
                               SUM(quantity * unit_price) AS total
                        FROM order_items GROUP BY order_id
                    ) items ON items.order_id = o.id
                    LEFT JOIN (SELECT order_id, SUM(amount) AS total FROM payments GROUP BY order_id) paid
                           ON paid.order_id = o.id
                    ORDER BY o.id DESC
                    """);
                 ResultSet rows = query.executeQuery()) {
                List<Order> orders = new ArrayList<>();
                while (rows.next()) {
                    long id = rows.getLong(1);
                    orders.add(new Order(id, rows.getString(2), rows.getString(5), Status.valueOf(rows.getString(3)),
                            rows.getBigDecimal(6), rows.getBigDecimal(7), rows.getObject(4, LocalDate.class),
                            progress.getOrDefault(id, Map.of())));
                }
                return orders;
            }
        }
    }

    private static Map<Long, Map<Status, String>> progress(Connection connection) throws SQLException {
        Map<Long, Map<Status, String>> progress = new java.util.HashMap<>();
        try (PreparedStatement query = query(connection, """
                SELECT h.order_id, h.status, h.changed_at, u.username
                FROM order_status_history h JOIN users u ON u.id = h.changed_by
                """);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                progress.computeIfAbsent(rows.getLong(1), id -> new EnumMap<>(Status.class))
                        .put(Status.valueOf(rows.getString(2)), "✓ " + rows.getString(3) + " · " + rows.getString(4));
            }
        }
        return progress;
    }

    List<Employee> employees() throws SQLException {
        if (session.role() == Accounts.Role.EMPLOYEE) {
            throw new IllegalArgumentException("Only admins can view the employee directory.");
        }
        try (Connection connection = open(false);
             PreparedStatement query = query(connection, """
                     SELECT username, phone, active, created_at FROM users WHERE role = 'EMPLOYEE' ORDER BY username
                     """);
             ResultSet rows = query.executeQuery()) {
            List<Employee> employees = new ArrayList<>();
            while (rows.next()) {
                employees.add(new Employee(rows.getString(1), rows.getString(2), rows.getBoolean(3), rows.getString(4)));
            }
            return employees;
        }
    }

    long addOrder(long customerId, List<Item> items, LocalDate expectedDelivery) throws SQLException {
        return transaction(connection -> {
            Map<Long, Long> quantities = new TreeMap<>();
            for (Item item : items) {
                if (item.quantity() < 1) {
                    throw new IllegalArgumentException("Item quantities must be positive.");
                }
                quantities.merge(item.inventoryId(), (long) item.quantity(), Long::sum);
            }
            Map<Long, String> products = new HashMap<>();
            for (var quantity : quantities.entrySet()) {
                try (PreparedStatement query = query(connection, """
                        SELECT product, stock_quantity FROM inventory_items WHERE id = ? FOR UPDATE
                        """, quantity.getKey());
                     ResultSet result = query.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalArgumentException("An inventory item is unavailable. Refresh and try again.");
                    }
                    String product = result.getString("product");
                    int stock = result.getInt("stock_quantity");
                    if (quantity.getValue() > stock) {
                        throw new IllegalArgumentException(product + " has only " + stock + " in stock. Refresh and adjust the quantity.");
                    }
                    products.put(quantity.getKey(), product);
                }
            }
            List<OrderLine> lines = items.stream().map(item -> new OrderLine(item.inventoryId(),
                    products.get(item.inventoryId()), item.quantity(), item.unitPrice())).toList();
            long orderId = insertOrder(connection, customerId, lines, expectedDelivery, null);
            for (var quantity : quantities.entrySet()) {
                update(connection, """
                        UPDATE inventory_items SET stock_quantity = stock_quantity - ? WHERE id = ?
                        """, quantity.getValue(), quantity.getKey());
            }
            return orderId;
        });
    }

    private long insertOrder(Connection connection, long customerId, List<OrderLine> items, LocalDate expectedDelivery,
                             String demoKey) throws SQLException {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Add at least one item to the order.");
        }
        try (PreparedStatement customer = query(connection, """
                SELECT id FROM customers WHERE id = ? AND archived_at IS NULL FOR UPDATE
                """, customerId);
             ResultSet result = customer.executeQuery()) {
            if (!result.next()) {
                throw new IllegalArgumentException("Choose an active customer.");
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        for (OrderLine item : items) {
            total = total.add(money(item.unitPrice()).multiply(BigDecimal.valueOf(item.quantity())));
        }
        money(total);
        long id = insert(connection, """
                INSERT INTO orders (customer_id, current_status, expected_delivery_date, created_by, created_at, demo_key)
                VALUES (?, 'RECEIVED', ?, ?, UTC_TIMESTAMP(6), ?)
                """, customerId, expectedDelivery, session.userId(), demoKey);
        for (OrderLine item : items) {
            update(connection, """
                    INSERT INTO order_items (order_id, inventory_item_id, product, quantity, unit_price)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, item.inventoryId(), item.product(), item.quantity(), item.unitPrice());
        }
        addStatus(connection, id, Status.RECEIVED);
        return id;
    }

    void advance(long orderId, Status expected) throws SQLException {
        transaction(connection -> {
            Balance balance = balanceForUpdate(connection, orderId);
            if (balance.status() != expected) {
                throw new IllegalArgumentException("Another user changed the order. Refresh and try again.");
            }
            Status next = expected.next();
            if (next == null) {
                throw new IllegalArgumentException("This order has already been delivered.");
            }
            if ((next == Status.DISPATCHED || next == Status.SHIPPED) && balance.due().signum() > 0) {
                throw new IllegalArgumentException("Record the remaining payment before dispatching or shipping.");
            }
            update(connection, "UPDATE orders SET current_status = ? WHERE id = ?", next.name(), orderId);
            addStatus(connection, orderId, next);
            return null;
        });
    }

    void recordPayment(long orderId, BigDecimal amount, String method, String reference) throws SQLException {
        BigDecimal payment = money(amount);
        transaction(connection -> {
            Balance balance = balanceForUpdate(connection, orderId);
            if (payment.compareTo(balance.due()) > 0) {
                throw new IllegalArgumentException("Payment exceeds the remaining balance of " + balance.due() + ".");
            }
            addPayment(connection, orderId, payment, field(method, 30, true), field(reference, 120, false));
            return null;
        });
    }

    void remind(long orderId) throws SQLException {
        transaction(connection -> {
            Balance balance = balanceForUpdate(connection, orderId);
            if (balance.status() != Status.PACKED || balance.due().signum() <= 0) {
                throw new IllegalArgumentException("Reminders are available for packed orders with a balance due.");
            }
            addReminder(connection, orderId, balance.due());
            return null;
        });
    }

    List<Event> activity(long orderId) throws SQLException {
        try (Connection connection = open(false);
             PreparedStatement query = query(connection, """
                     SELECT 'Status' AS type, h.status AS detail, u.username, h.changed_at AS happened_at
                     FROM order_status_history h JOIN users u ON u.id = h.changed_by WHERE h.order_id = ?
                     UNION ALL
                     SELECT 'Payment', CONCAT(p.amount, ' · ', p.method, COALESCE(CONCAT(' · ', p.payment_reference), '')),
                            u.username, p.recorded_at
                     FROM payments p JOIN users u ON u.id = p.recorded_by WHERE p.order_id = ?
                     UNION ALL
                     SELECT 'Demo reminder', CONCAT('Balance due: ', r.balance), u.username, r.recorded_at
                     FROM payment_reminders r JOIN users u ON u.id = r.recorded_by WHERE r.order_id = ?
                     ORDER BY happened_at
                     """, orderId, orderId, orderId);
             ResultSet rows = query.executeQuery()) {
            List<Event> events = new ArrayList<>();
            while (rows.next()) {
                String type = rows.getString(1);
                String detail = rows.getString(2);
                events.add(new Event(type, type.equals("Status") ? Status.valueOf(detail).label : detail,
                        rows.getString(3), rows.getString(4)));
            }
            return events;
        }
    }

    long seedOrder(String key, String name, BigDecimal total, BigDecimal paid, Status status) throws SQLException {
        return transaction(connection -> {
            try (PreparedStatement query = query(connection, "SELECT id FROM orders WHERE demo_key = ?", key);
                 ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    return result.getLong(1);
                }
            }
            long customer = insertCustomer(connection, name, key + "@example.test", "Demo city", "", "");
            long order = insertOrder(connection, customer, List.of(new OrderLine(null, "Demo product", 1, total)), null, key);
            if (paid.signum() > 0) {
                addPayment(connection, order, paid, "Bank transfer", "Demo payment");
            }
            for (Status step = Status.ACCEPTED; step.ordinal() <= status.ordinal(); step = step.next()) {
                addStatus(connection, order, step);
                if (step == status) {
                    break;
                }
            }
            update(connection, "UPDATE orders SET current_status = ? WHERE id = ?", status.name(), order);
            if (status == Status.PACKED && paid.compareTo(total) < 0) {
                addReminder(connection, order, total.subtract(paid));
            }
            return order;
        });
    }

    private Balance balanceForUpdate(Connection connection, long orderId) throws SQLException {
        Status status;
        try (PreparedStatement query = query(connection, "SELECT current_status FROM orders WHERE id = ? FOR UPDATE", orderId);
             ResultSet result = query.executeQuery()) {
            if (!result.next()) {
                throw new IllegalArgumentException("Order not found.");
            }
            status = Status.valueOf(result.getString(1));
        }
        try (PreparedStatement query = query(connection, """
                SELECT (SELECT SUM(quantity * unit_price) FROM order_items WHERE order_id = ?)
                       - (SELECT COALESCE(SUM(amount), 0.00) FROM payments WHERE order_id = ?)
                """, orderId, orderId);
             ResultSet result = query.executeQuery()) {
            result.next();
            return new Balance(status, result.getBigDecimal(1));
        }
    }

    private void addStatus(Connection connection, long orderId, Status status) throws SQLException {
        update(connection, """
                INSERT INTO order_status_history (order_id, status, changed_by, changed_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6))
                """, orderId, status.name(), session.userId());
    }

    private void addPayment(Connection connection, long orderId, BigDecimal amount, String method, String reference)
            throws SQLException {
        update(connection, """
                INSERT INTO payments (order_id, amount, method, payment_reference, recorded_by, recorded_at)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
                """, orderId, amount, method, reference, session.userId());
    }

    private void addReminder(Connection connection, long orderId, BigDecimal balance) throws SQLException {
        update(connection, """
                INSERT INTO payment_reminders (order_id, balance, recorded_by, recorded_at)
                VALUES (?, ?, ?, UTC_TIMESTAMP(6))
                """, orderId, balance, session.userId());
    }

    private Connection open(boolean write) throws SQLException {
        Connection connection = database.connect();
        try (PreparedStatement query = query(connection, """
                SELECT u.role FROM login_sessions s JOIN users u ON u.id = s.user_id
                WHERE s.id = ? AND u.id = ? AND s.logged_out_at IS NULL AND u.active
                """, session.id(), session.userId());
             ResultSet result = query.executeQuery()) {
            if (!result.next() || !result.getString(1).equals(session.role().name())
                    || (write && session.role() != Accounts.Role.EMPLOYEE)) {
                throw new IllegalArgumentException("An active employee session is required to make changes.");
            }
            return connection;
        } catch (SQLException | RuntimeException ex) {
            connection.close();
            throw ex;
        }
    }

    @FunctionalInterface
    private interface Change<T> {
        T apply(Connection connection) throws SQLException;
    }

    private <T> T transaction(Change<T> change) throws SQLException {
        try (Connection connection = open(true)) {
            connection.setAutoCommit(false);
            try {
                T result = change.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    private static PreparedStatement query(Connection connection, String sql, Object... values) throws SQLException {
        return bind(connection.prepareStatement(sql), values);
    }

    private static PreparedStatement bind(PreparedStatement statement, Object... values) throws SQLException {
        try {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement;
        } catch (SQLException ex) {
            statement.close();
            throw ex;
        }
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = query(connection, sql, values)) {
            statement.executeUpdate();
        }
    }

    private static long insert(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = bind(connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS), values)) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("MySQL did not return a generated ID.");
                }
                return keys.getLong(1);
            }
        }
    }

    private static String field(String value, int maxLength, boolean required) {
        String text = value.strip();
        if ((required && text.isEmpty()) || text.length() > maxLength) {
            throw new IllegalArgumentException("Check required fields and keep values within " + maxLength + " characters.");
        }
        return text.isEmpty() ? null : text;
    }

    private static BigDecimal money(BigDecimal amount) {
        if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(new BigDecimal("999999999999.99")) > 0) {
            throw new IllegalArgumentException("Enter a positive amount with up to two decimal places.");
        }
        return amount.setScale(2);
    }
}
