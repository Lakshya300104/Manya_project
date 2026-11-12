package officemgtapp;

import com.toedter.calendar.JDateChooser;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import javax.swing.*;

final class OrderEntry extends JPanel {
    record Draft(long customerId, List<Operations.Item> items, LocalDate expectedDelivery) {}

    private final JComboBox<Operations.Customer> customer;
    private final JDateChooser expected = new JDateChooser();
    private final List<Operations.InventoryItem> inventory;
    private final List<ItemRow> rows = new ArrayList<>();
    private final JPanel items = new JPanel();

    OrderEntry(List<Operations.Customer> customers, List<Operations.InventoryItem> inventory) {
        super(new BorderLayout(0, 10));
        this.inventory = inventory;
        customer = new JComboBox<>(customers.toArray(Operations.Customer[]::new));
        expected.setDateFormatString("yyyy-MM-dd");
        OfficeTheme.calendar(expected);
        ((JTextField) expected.getDateEditor().getUiComponent()).setEditable(false);
        JButton clear = new JButton("Clear date");
        clear.addActionListener(event -> expected.setDate(null));
        JPanel dateControls = OfficeTheme.transparent(new BorderLayout(6, 0));
        dateControls.add(expected, BorderLayout.CENTER);
        dateControls.add(clear, BorderLayout.EAST);
        JPanel fields = OfficeTheme.transparent(new GridLayout(0, 2, 8, 8));
        Login.addField(fields, "Customer", customer);
        Login.addField(fields, "Expected delivery (optional)", dateControls);
        add(fields, BorderLayout.NORTH);

        items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
        JScrollPane scroll = OfficeTheme.scroll(items);
        scroll.setPreferredSize(new Dimension(760, 180));
        add(scroll, BorderLayout.CENTER);
        JButton add = new JButton("Add item");
        add.addActionListener(event -> addRow());
        JPanel actions = OfficeTheme.transparent(new FlowLayout(FlowLayout.LEFT));
        actions.add(add);
        add(actions, BorderLayout.SOUTH);
        addRow();
    }

    Draft prompt(Component owner) {
        while (JOptionPane.showConfirmDialog(owner, this, "New order", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                return draft();
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(owner, ex.getMessage(), "New order", JOptionPane.ERROR_MESSAGE);
            }
        }
        return null;
    }

    private Draft draft() {
        List<Operations.Item> lines = new ArrayList<>();
        for (ItemRow row : rows) {
            Operations.InventoryItem item = row.selectedItem();
            if (item == null || row.quantity < 1) {
                throw new IllegalArgumentException("Select an in-stock item and a quantity for each row.");
            }
            BigDecimal price;
            try {
                price = new BigDecimal(row.price.getText().strip());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Enter a unit price for each item.");
            }
            if (price.signum() <= 0 || price.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("Enter a positive unit price with up to two decimal places.");
            }
            lines.add(new Operations.Item(item.id(), row.quantity, price));
        }
        Calendar date = expected.getCalendar();
        LocalDate delivery = date == null ? null : LocalDate.of(date.get(Calendar.YEAR),
                date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH));
        return new Draft(((Operations.Customer) customer.getSelectedItem()).id(), lines, delivery);
    }

    private void addRow() {
        ItemRow row = new ItemRow();
        rows.add(row);
        items.add(row);
        refreshRows();
    }

    private void refreshRows() {
        for (ItemRow row : rows) {
            row.count.setText(Integer.toString(row.quantity));
            row.minus.setEnabled(row.quantity > 1);
            row.plus.setEnabled(row.quantity < availableFor(row));
            row.remove.setEnabled(rows.size() > 1);
        }
        items.revalidate();
        items.repaint();
    }

    private int availableFor(ItemRow row) {
        Operations.InventoryItem item = row.selectedItem();
        if (item == null) {
            return 0;
        }
        int used = 0;
        for (ItemRow other : rows) {
            Operations.InventoryItem selected = other.selectedItem();
            if (other != row && selected != null && selected.id() == item.id()) {
                used += other.quantity;
            }
        }
        return item.stock() - used;
    }

    private final class ItemRow extends JPanel {
        private final JComboBox<Operations.InventoryItem> product = new JComboBox<>(
                inventory.toArray(Operations.InventoryItem[]::new));
        private final JLabel count = new JLabel("0", SwingConstants.CENTER);
        private final JButton plus = new JButton("+");
        private final JButton minus = new JButton("−");
        private final JButton remove = new JButton("Remove");
        private final JTextField price = new JTextField(7);
        private int quantity;

        ItemRow() {
            super(new FlowLayout(FlowLayout.LEFT, 6, 5));
            product.setSelectedIndex(-1);
            product.setPreferredSize(new Dimension(360, product.getPreferredSize().height));
            product.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                        boolean selected, boolean focused) {
                    String label = "Select inventory item";
                    if (value instanceof Operations.InventoryItem item) {
                        var parts = new ArrayList<String>();
                        for (String part : new String[] {item.product(), item.manufacturer(), item.grade(), item.thickness()}) {
                            if (part != null && !part.isBlank()) {
                                parts.add(part);
                            }
                        }
                        label = String.join(" · ", parts) + " (" + item.stock() + " in stock)";
                    }
                    return super.getListCellRendererComponent(list, label, index, selected, focused);
                }
            });
            count.setPreferredSize(new Dimension(70, count.getPreferredSize().height));
            minus.setToolTipText("Decrease quantity");
            plus.setToolTipText("Increase quantity");
            product.addActionListener(event -> {
                quantity = availableFor(this) > 0 ? 1 : 0;
                refreshRows();
            });
            minus.addActionListener(event -> {
                quantity--;
                refreshRows();
            });
            plus.addActionListener(event -> {
                quantity++;
                refreshRows();
            });
            remove.addActionListener(event -> {
                rows.remove(this);
                items.remove(this);
                refreshRows();
            });
            add(product);
            add(minus);
            add(count);
            add(plus);
            JLabel priceLabel = new JLabel("Unit price");
            priceLabel.setLabelFor(price);
            add(priceLabel);
            add(price);
            add(remove);
        }

        private Operations.InventoryItem selectedItem() {
            return (Operations.InventoryItem) product.getSelectedItem();
        }
    }
}
