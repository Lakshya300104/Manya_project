package officemgtapp;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

final class OperationsPanel extends JTabbedPane {
    private final JFrame owner;
    private final Operations operations;
    private final boolean employee;
    private final JTable customerTable = OfficeTheme.table();
    private final JTable orderTable = OfficeTheme.table();
    private final JTable stockTable = OfficeTheme.table();
    private final JLabel customerCount = OfficeTheme.heading("—", 24f);
    private final JLabel openOrderCount = OfficeTheme.heading("—", 24f);
    private final JLabel balanceCount = OfficeTheme.heading("—", 24f);
    private final JButton advance = new JButton("Advance status");
    private final JButton payment = new JButton("Record payment");
    private final JButton reminder = new JButton("Notify customer");
    private List<Operations.Customer> customers = List.of();
    private List<Operations.Order> orders = List.of();
    private List<Operations.InventoryItem> inventory = List.of();

    OperationsPanel(JFrame owner, Operations operations, boolean employee) {
        this.owner = owner;
        this.operations = operations;
        this.employee = employee;
        addTab("Customers", customersTab());
        addTab("Orders, delivery & payments", ordersTab());
        addTab("Inventory", inventoryTab());
        setPreferredSize(new Dimension(1050, 420));
        orderTable.getSelectionModel().addListSelectionListener(event -> updateOrderActions());
        updateOrderActions();
    }

    void refresh() {
        Login.runTask(owner, this::load, this::show);
    }

    JPanel overview() {
        JPanel overview = OfficeTheme.transparent(new GridLayout(1, 3, 10, 0));
        overview.add(metric("Active customers", customerCount));
        overview.add(metric("Open orders", openOrderCount));
        overview.add(metric("Orders with balance due", balanceCount));
        return overview;
    }

    private static JPanel metric(String title, JLabel value) {
        JPanel metric = OfficeTheme.card(new BorderLayout(0, 4), 16);
        metric.add(OfficeTheme.secondary(title), BorderLayout.NORTH);
        metric.add(value, BorderLayout.CENTER);
        return metric;
    }

    private record Snapshot(List<Operations.Customer> customers, List<Operations.Order> orders,
                            List<Operations.InventoryItem> inventory) {}

    private Snapshot load() throws Exception {
        return new Snapshot(operations.customers(), operations.orders(), operations.inventory());
    }

    private void show(Snapshot snapshot) {
        int selectedRow = orderTable.getSelectedRow();
        long selectedOrder = selectedRow < 0 ? -1 : orders.get(orderTable.convertRowIndexToModel(selectedRow)).id();
        orderTable.clearSelection();
        customers = snapshot.customers();
        orders = snapshot.orders();
        inventory = snapshot.inventory();
        customerCount.setText(Long.toString(customers.stream().filter(customer -> !customer.archived()).count()));
        openOrderCount.setText(Long.toString(orders.stream().filter(order -> order.status() != Operations.Status.DELIVERED).count()));
        balanceCount.setText(Long.toString(orders.stream().filter(order -> order.balance().signum() > 0).count()));
        List<Object[]> customerRows = customers.stream()
                .map(row -> new Object[] {row.name(), row.email(), row.city(), row.gst(), row.pan(),
                        row.archived() ? "Archived" : "Active"}).toList();
        model(customerTable, new String[] {"Customer", "Email", "City", "GST", "PAN", "Status"}, customerRows);

        List<String> columns = new ArrayList<>(List.of("Order", "Customer", "Products", "Status",
                "Expected delivery", "Total", "Paid", "Balance", "Payment status"));
        if (!employee) {
            for (Operations.Status status : Operations.Status.values()) {
                columns.add(status.label + " (UTC)");
            }
        }
        List<Object[]> orderRows = new ArrayList<>();
        for (var order : orders) {
            List<Object> row = new ArrayList<>();
            java.util.Collections.addAll(row, order.id(), order.customer(), order.products(), order.status().label,
                    order.expectedDelivery(), order.total(), order.paid(), order.balance(), order.paymentStatus());
            if (!employee) {
                for (Operations.Status status : Operations.Status.values()) {
                    row.add(order.milestones().getOrDefault(status, ""));
                }
            }
            orderRows.add(row.toArray());
        }
        model(orderTable, columns.toArray(String[]::new), orderRows);
        if (!employee) {
            orderTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            for (int column = 0; column < columns.size(); column++) {
                orderTable.getColumnModel().getColumn(column).setPreferredWidth(column < 9 ? 120 : 270);
            }
        }
        List<Object[]> inventoryRows = inventory.stream().map(item -> new Object[] {item.product(),
                item.manufacturer(), item.grade(), item.thickness(), item.stock()}).toList();
        model(stockTable, new String[] {"Product", "Manufacturer", "Grade", "Thickness", "Stock"}, inventoryRows);
        for (int row = 0; row < orders.size(); row++) {
            if (orders.get(row).id() == selectedOrder) {
                int viewRow = orderTable.convertRowIndexToView(row);
                orderTable.setRowSelectionInterval(viewRow, viewRow);
                break;
            }
        }
        updateOrderActions();
    }

    private JPanel customersTab() {
        JPanel actions = actions();
        if (employee) {
            actions.add(button("Add customer", () -> customerForm(null)));
            actions.add(button("Edit customer", () -> {
                var customer = selected(customerTable, customers);
                if (customer != null) {
                    customerForm(customer);
                }
            }));
            actions.add(button("Archive customer", () -> {
                var customer = selected(customerTable, customers);
                if (customer != null && JOptionPane.showConfirmDialog(owner,
                        "Archive " + customer.name() + "? Existing orders will remain visible.", "Archive customer",
                        JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    change(() -> {
                        operations.archiveCustomer(customer.id());
                        return null;
                    });
                }
            }));
        }
        return tab(customerTable, actions);
    }

    private JPanel ordersTab() {
        JPanel actions = actions();
        actions.add(button("Order history", () -> {
            var order = selected(orderTable, orders);
            if (order != null) {
                Login.runTask(owner, () -> operations.activity(order.id()), events -> showHistory(order.id(), events));
            }
        }));
        if (employee) {
            actions.add(button("New order", this::orderForm));
            OfficeTheme.primary(advance);
            advance.addActionListener(event -> {
                var order = selected(orderTable, orders);
                if (order != null) {
                    change(() -> {
                        operations.advance(order.id(), order.status());
                        return null;
                    });
                }
            });
            payment.addActionListener(event -> paymentForm());
            reminder.addActionListener(event -> {
                var order = selected(orderTable, orders);
                if (order != null) {
                    Login.runTask(owner, () -> {
                        operations.remind(order.id());
                        return load();
                    }, result -> {
                        show(result);
                        JOptionPane.showMessageDialog(owner, "Customer notification sent (demo).");
                    });
                }
            });
            actions.add(advance);
            actions.add(payment);
            actions.add(reminder);
        }
        return tab(orderTable, actions);
    }

    private JPanel inventoryTab() {
        JPanel actions = actions();
        if (employee) {
            actions.add(button("Add inventory", () -> {
                String[] values = form("Add inventory", new String[] {"Product", "Manufacturer", "Grade", "Thickness", "Stock"},
                        new String[] {"", "", "", "", "0"});
                if (values != null) {
                    change(() -> {
                        operations.addInventory(values[0], values[1], values[2], values[3], wholeNumber(values[4]));
                        return null;
                    });
                }
            }));
            actions.add(button("Adjust stock", () -> {
                var item = selected(stockTable, inventory);
                if (item != null) {
                    String delta = JOptionPane.showInputDialog(owner, "Stock adjustment (e.g. 5 or -5)");
                    if (delta != null) {
                        change(() -> {
                            operations.adjustStock(item.id(), wholeNumber(delta));
                            return null;
                        });
                    }
                }
            }));
        }
        return tab(stockTable, actions);
    }

    private void updateOrderActions() {
        int row = orderTable.getSelectedRow();
        var order = row < 0 || row >= orders.size() ? null : orders.get(orderTable.convertRowIndexToModel(row));
        Operations.Status next = order == null ? null : order.status().next();
        boolean due = order != null && order.balance().signum() > 0;
        boolean blocked = due && (next == Operations.Status.DISPATCHED || next == Operations.Status.SHIPPED);
        advance.setText(next == null ? "Advance status" : "Mark as " + next.label.toLowerCase(java.util.Locale.ROOT));
        advance.setEnabled(next != null && !blocked);
        advance.setToolTipText(blocked ? "Record the remaining payment before this step." : null);
        payment.setEnabled(due);
        reminder.setEnabled(due && order.status() == Operations.Status.PACKED);
    }

    private void customerForm(Operations.Customer customer) {
        String[] initial = customer == null ? new String[] {"", "", "", "", ""}
                : new String[] {customer.name(), customer.email(), customer.city(), customer.gst(), customer.pan()};
        String[] values = form(customer == null ? "Add customer" : "Edit customer",
                new String[] {"Customer name", "Email", "City", "GST", "PAN"}, initial);
        if (values != null) {
            change(() -> {
                if (customer == null) {
                    operations.addCustomer(values[0], values[1], values[2], values[3], values[4]);
                } else {
                    operations.editCustomer(customer.id(), values[0], values[1], values[2], values[3], values[4]);
                }
                return null;
            });
        }
    }

    private void orderForm() {
        Login.runTask(owner, this::load, snapshot -> {
            show(snapshot);
            if (customers.isEmpty()) {
                JOptionPane.showMessageDialog(owner, "Add a customer before creating an order.");
                return;
            }
            var inStock = inventory.stream().filter(item -> item.stock() > 0).toList();
            if (inStock.isEmpty()) {
                JOptionPane.showMessageDialog(owner, "Add stock to inventory before creating an order.");
                return;
            }
            OrderEntry entry = new OrderEntry(customers, inStock);
            OrderEntry.Draft order = entry.prompt(owner);
            if (order != null) {
                change(() -> {
                    operations.addOrder(order.customerId(), order.items(), order.expectedDelivery());
                    return null;
                });
            }
        });
    }

    private void paymentForm() {
        var order = selected(orderTable, orders);
        if (order == null) {
            return;
        }
        String[] values = form("Record payment · balance due " + order.balance(),
                new String[] {"Amount", "Method", "Reference (optional)"}, new String[] {"", "", ""});
        if (values != null) {
            change(() -> {
                operations.recordPayment(order.id(), amount(values[0]), values[1], values[2]);
                return null;
            });
        }
    }

    private void showHistory(long order, List<Operations.Event> events) {
        JTable table = OfficeTheme.table();
        model(table, new String[] {"Event", "Details", "Employee", "Time (UTC)"}, events.stream().map(event ->
                new Object[] {event.type(), event.detail(), event.employee(), event.timestamp()}).toList());
        JScrollPane pane = OfficeTheme.scroll(table);
        pane.setPreferredSize(new Dimension(680, 260));
        JOptionPane.showMessageDialog(owner, pane, "Order " + order + " history", JOptionPane.PLAIN_MESSAGE);
    }

    private void change(Callable<Void> action) {
        Login.runTask(owner, () -> {
            action.call();
            return load();
        }, this::show);
    }

    private String[] form(String title, String[] labels, String[] values) {
        JPanel fields = new JPanel(new GridLayout(0, 2, 8, 8));
        JTextField[] inputs = new JTextField[labels.length];
        for (int index = 0; index < labels.length; index++) {
            inputs[index] = new JTextField(values[index], 20);
            Login.addField(fields, labels[index], inputs[index]);
        }
        if (JOptionPane.showConfirmDialog(owner, fields, title, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return null;
        }
        return java.util.Arrays.stream(inputs).map(JTextField::getText).toArray(String[]::new);
    }

    private <T> T selected(JTable table, List<T> rows) {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(owner, "Select a row first.");
            return null;
        }
        return rows.get(table.convertRowIndexToModel(row));
    }

    private JPanel actions() {
        JPanel actions = OfficeTheme.transparent(new FlowLayout(FlowLayout.LEFT));
        actions.add(button("Refresh", this::refresh));
        return actions;
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static JPanel tab(JTable table, JPanel actions) {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel panel = OfficeTheme.card(new BorderLayout(0, 5), 13);
        panel.add(OfficeTheme.scroll(table), BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private static void model(JTable table, String[] columns, List<Object[]> rows) {
        table.setModel(new DefaultTableModel(rows.toArray(Object[][]::new), columns) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        });
        table.setAutoCreateRowSorter(true);
    }

    private static int wholeNumber(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Enter a whole number for quantity or stock.");
        }
    }

    private static BigDecimal amount(String value) {
        try {
            return new BigDecimal(value.strip());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Enter a valid amount, such as 250.00.");
        }
    }
}
