package officemgtapp;

import com.toedter.calendar.JDateChooser;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.plaf.metal.DefaultMetalTheme;

public final class OfficeTheme {
    private static final Color CANVAS = new Color(0x181B20);
    private static final Color SURFACE = new Color(0x24282F);
    private static final Color STRIPE = new Color(0x20242A);
    private static final Color RECESSED = new Color(0x1B1F24);
    private static final Color EDGE = new Color(0x3A424A);
    private static final Color SHADOW = new Color(0x0E1013);
    private static final Color INK = new Color(0xEDE9E1);
    private static final Color MUTED = new Color(0xB8BFC2);
    private static final Color ACCENT = new Color(0xC9A45C);
    private static final Color GOLD_HOVER = new Color(0xDBB977);
    private static final Color SELECTED = new Color(0x286965);
    private static final Color COMPLETED = new Color(0x213D3A);
    private static final Color TEAL_TEXT = new Color(0xA7D7CC);

    private OfficeTheme() {}

    static void install() {
        try {
            MetalLookAndFeel.setCurrentTheme(new CharcoalMetal());
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (UnsupportedLookAndFeelException ex) {
            throw new IllegalStateException("Could not initialize the application theme.", ex);
        }
        boolean inter = java.util.Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()).contains("Inter");
        Font font = new Font(inter ? "Inter" : Font.SANS_SERIF, Font.PLAIN, 14);
        for (String component : new String[] {"Label", "Button", "TextField", "PasswordField", "ComboBox",
                "Table", "TableHeader", "TabbedPane", "OptionPane", "List", "ToolTip"}) {
            UIManager.put(component + ".font", font);
            UIManager.put(component + ".foreground", INK);
        }
        for (String component : new String[] {"Panel", "TabbedPane", "OptionPane", "Viewport", "ScrollPane",
                "PopupMenu", "ToolTip"}) {
            UIManager.put(component + ".background", CANVAS);
        }
        UIManager.put("ButtonUI", AnimatedButton.class.getName());
        UIManager.put("ComboBoxUI", RoundedCombo.class.getName());
        UIManager.put("TabbedPaneUI", RoundedTabs.class.getName());
        UIManager.put("Button.disabledText", MUTED);
        UIManager.put("Button.background", SURFACE);
        UIManager.put("Button.font", font.deriveFont(Font.BOLD, 13f));
        UIManager.put("TabbedPane.font", font.deriveFont(Font.BOLD, 14f));
        UIManager.put("TabbedPane.tabInsets", new Insets(11, 18, 11, 18));
        UIManager.put("TabbedPane.tabAreaInsets", new Insets(1, 2, 9, 2));
        UIManager.put("TabbedPane.contentBorderInsets", new Insets(0, 0, 0, 0));
        UIManager.put("TabbedPane.selectedTabPadInsets", new Insets(0, 0, 0, 0));
        UIManager.put("TabbedPane.selectionFollowsFocus", Boolean.TRUE);
        UIManager.put("Table.background", SURFACE);
        UIManager.put("Table.selectionBackground", SELECTED);
        UIManager.put("Table.selectionForeground", INK);
        UIManager.put("TableHeader.background", RECESSED);
        UIManager.put("TableHeader.font", font.deriveFont(Font.BOLD, 13f));
        UIManager.put("TableHeader.cellBorder", BorderFactory.createEmptyBorder(0, 12, 0, 8));
        UIManager.put("Table.focusCellHighlightBorder", BorderFactory.createLineBorder(ACCENT));
        UIManager.put("OptionPane.messageForeground", INK);
        UIManager.put("ComboBox.background", RECESSED);
        UIManager.put("ComboBox.border", new InsetBorder());
        UIManager.put("ComboBox.selectionBackground", SELECTED);
        UIManager.put("ComboBox.selectionForeground", INK);
        UIManager.put("List.selectionBackground", SELECTED);
        UIManager.put("List.selectionForeground", INK);
        UIManager.put("List.background", SURFACE);
        UIManager.put("ScrollBar.track", CANVAS);
        UIManager.put("ScrollBar.thumb", EDGE);
        UIManager.put("ScrollBar.thumbDarkShadow", SHADOW);
        UIManager.put("ScrollBar.thumbHighlight", EDGE);
        UIManager.put("ScrollBar.thumbShadow", SHADOW);
        for (String component : new String[] {"TextField", "FormattedTextField", "PasswordField"}) {
            UIManager.put(component + ".background", RECESSED);
            UIManager.put(component + ".foreground", INK);
            UIManager.put(component + ".inactiveForeground", MUTED);
            UIManager.put(component + ".border", new InsetBorder());
            UIManager.put(component + ".caretForeground", ACCENT);
            UIManager.put(component + ".selectionBackground", SELECTED);
            UIManager.put(component + ".selectionForeground", INK);
        }
    }

    static JLabel heading(String text, float size) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, size));
        label.setForeground(ACCENT);
        return label;
    }

    static JLabel secondary(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        return label;
    }

    static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    static JPanel card(LayoutManager layout, int padding) {
        JPanel panel = new JPanel(layout) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D paint = smooth(graphics);
                softSurface(paint, 6, 5, getWidth() - 13, getHeight() - 12, 22, SURFACE, 1f);
                paint.dispose();
                super.paintComponent(graphics);
            }
        };
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding));
        return panel;
    }

    static void primary(JButton button) {
        button.putClientProperty("office.primary", Boolean.TRUE);
    }

    static void calendar(JDateChooser chooser) {
        var calendar = chooser.getJCalendar();
        calendar.setBackground(SURFACE);
        calendar.setForeground(INK);
        calendar.setDecorationBackgroundColor(RECESSED);
        calendar.setWeekdayForeground(INK);
        calendar.setSundayForeground(ACCENT);
    }

    static JTable table() {
        JTable table = new JTable() {
            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column) {
                Component cell = super.prepareRenderer(renderer, row, column);
                if (!isCellSelected(row, column)) {
                    Object value = getValueAt(row, column);
                    boolean completed = value instanceof String text && (text.startsWith("✓ ")
                            || ("Payment status".equals(getColumnName(column)) && "Paid".equals(text))
                            || ("Status".equals(getColumnName(column)) && "Delivered".equals(text)));
                    cell.setBackground(completed ? COMPLETED : row % 2 == 0 ? SURFACE : STRIPE);
                    cell.setForeground(completed ? TEAL_TEXT : INK);
                }
                if (cell instanceof JComponent component && !isCellSelected(row, column)) {
                    component.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
                }
                return cell;
            }
        };
        table.setRowHeight(38);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.getTableHeader().setPreferredSize(new Dimension(0, 40));
        table.getTableHeader().setReorderingAllowed(false);
        return table;
    }

    static JScrollPane scroll(Component view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createLineBorder(EDGE, 1, true));
        return scroll;
    }

    private static Graphics2D smooth(Graphics graphics) {
        Graphics2D paint = (Graphics2D) graphics.create();
        paint.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return paint;
    }

    private static Color mix(Color start, Color end, float amount) {
        return new Color(Math.round(start.getRed() + (end.getRed() - start.getRed()) * amount),
                Math.round(start.getGreen() + (end.getGreen() - start.getGreen()) * amount),
                Math.round(start.getBlue() + (end.getBlue() - start.getBlue()) * amount));
    }

    private static void softSurface(Graphics2D paint, int x, int y, int width, int height, int arc,
                                    Color fill, float depth) {
        for (int step = 5; step >= 1; step--) {
            paint.setColor(new Color(0, 0, 0, Math.round(32 * depth)));
            paint.fillRoundRect(x + step, y + step, width, height, arc, arc);
            paint.setColor(new Color(141, 148, 153, Math.round(13 * depth)));
            paint.fillRoundRect(x - step / 2, y - step / 2, width, height, arc, arc);
        }
        paint.setColor(fill);
        paint.fillRoundRect(x, y, width, height, arc, arc);
    }

    private static final class InsetBorder extends AbstractBorder {
        @Override
        public Insets getBorderInsets(Component component, Insets insets) {
            insets.set(9, 12, 9, 12);
            return insets;
        }

        @Override
        public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
            Graphics2D paint = smooth(graphics);
            paint.setPaint(new GradientPaint(x, y, SHADOW, x, y + height, EDGE));
            paint.drawRoundRect(x + 1, y + 1, width - 3, height - 3, 12, 12);
            if (component.hasFocus()) {
                paint.setColor(ACCENT);
                paint.drawRoundRect(x + 2, y + 2, width - 5, height - 5, 11, 11);
            }
            paint.dispose();
        }
    }

    public static final class AnimatedButton extends BasicButtonUI {
        private Timer animation;
        private javax.swing.event.ChangeListener listener;
        private float position;

        public static ComponentUI createUI(JComponent component) {
            return new AnimatedButton();
        }

        @Override
        public void installUI(JComponent component) {
            super.installUI(component);
            AbstractButton button = (AbstractButton) component;
            if (button.getText() == null || button.getText().isEmpty()) {
                return;
            }
            button.setOpaque(false);
            button.setContentAreaFilled(false);
            button.setRolloverEnabled(true);
            int padding = button.getText().length() <= 2 ? 10 : 16;
            button.setBorder(BorderFactory.createEmptyBorder(10, padding, 11, padding));
            animation = new Timer(15, event -> {
                ButtonModel model = button.getModel();
                float target = !button.isEnabled() ? 0f : model.isPressed() ? -1f : model.isRollover() ? 1f : 0f;
                position += (target - position) * 0.28f;
                if (Math.abs(target - position) < 0.015f) {
                    position = target;
                    animation.stop();
                }
                button.repaint();
            });
            listener = event -> animation.start();
            button.getModel().addChangeListener(listener);
        }

        @Override
        public void uninstallUI(JComponent component) {
            if (animation != null) {
                animation.stop();
                ((AbstractButton) component).getModel().removeChangeListener(listener);
            }
            super.uninstallUI(component);
        }

        @Override
        public void paint(Graphics graphics, JComponent component) {
            if (animation == null) {
                super.paint(graphics, component);
                return;
            }
            AbstractButton button = (AbstractButton) component;
            boolean primary = Boolean.TRUE.equals(button.getClientProperty("office.primary"))
                    || button instanceof JButton push && push.isDefaultButton();
            float hover = Math.max(0, position);
            float press = Math.max(0, -position);
            Graphics2D paint = smooth(graphics);
            paint.translate(0, press * 2f - hover);
            Color fill = !button.isEnabled() ? RECESSED : primary
                    ? mix(ACCENT, GOLD_HOVER, hover)
                    : mix(SURFACE, EDGE, hover);
            softSurface(paint, 4, 3, component.getWidth() - 9, component.getHeight() - 9, 15, fill,
                    button.isEnabled() ? 0.75f + hover * 0.25f - press * 0.55f : 0f);
            if (button.hasFocus()) {
                paint.setColor(primary ? CANVAS : ACCENT);
                paint.drawRoundRect(7, 6, component.getWidth() - 15, component.getHeight() - 15, 11, 11);
            }
            super.paint(paint, component);
            paint.dispose();
        }

        @Override
        protected void paintText(Graphics graphics, AbstractButton button, Rectangle textRect, String text) {
            if (animation == null) {
                super.paintText(graphics, button, textRect, text);
                return;
            }
            boolean primary = Boolean.TRUE.equals(button.getClientProperty("office.primary"))
                    || button instanceof JButton push && push.isDefaultButton();
            graphics.setColor(!button.isEnabled() ? MUTED : primary ? CANVAS : INK);
            graphics.drawString(text, textRect.x, textRect.y + graphics.getFontMetrics().getAscent());
        }

        @Override
        protected void paintFocus(Graphics graphics, AbstractButton button, Rectangle view, Rectangle text,
                                  Rectangle icon) {}
    }

    public static final class RoundedTabs extends BasicTabbedPaneUI {
        public static ComponentUI createUI(JComponent component) {
            return new RoundedTabs();
        }

        @Override
        protected void paintTabBackground(Graphics graphics, int placement, int index, int x, int y,
                                          int width, int height, boolean selected) {
            Graphics2D paint = smooth(graphics);
            if (selected) {
                softSurface(paint, x + 4, y + 3, width - 9, height - 7, 15, SELECTED, 0.65f);
            } else if (getRolloverTab() == index) {
                paint.setColor(SURFACE);
                paint.fill(new RoundRectangle2D.Float(x + 4, y + 3, width - 9, height - 7, 15, 15));
            }
            paint.dispose();
        }

        @Override
        protected void paintTabBorder(Graphics graphics, int placement, int index, int x, int y,
                                      int width, int height, boolean selected) {}

        @Override
        protected void paintContentBorder(Graphics graphics, int placement, int selected) {}

        @Override
        protected void paintFocusIndicator(Graphics graphics, int placement, Rectangle[] rects, int index,
                                           Rectangle icon, Rectangle text, boolean selected) {
            if (tabPane.hasFocus() && selected) {
                Rectangle bounds = rects[index];
                Graphics2D paint = smooth(graphics);
                paint.setColor(ACCENT);
                paint.drawRoundRect(bounds.x + 7, bounds.y + 6, bounds.width - 15, bounds.height - 13, 12, 12);
                paint.dispose();
            }
        }
    }

    public static final class RoundedCombo extends BasicComboBoxUI {
        public static ComponentUI createUI(JComponent component) {
            return new RoundedCombo();
        }

        @Override
        public void installUI(JComponent component) {
            super.installUI(component);
            component.setOpaque(false);
        }

        @Override
        public void paint(Graphics graphics, JComponent component) {
            Graphics2D paint = smooth(graphics);
            paint.setColor(RECESSED);
            paint.fillRoundRect(1, 1, component.getWidth() - 2, component.getHeight() - 2, 12, 12);
            paint.dispose();
            super.paint(graphics, component);
        }

        @Override
        public void paintCurrentValueBackground(Graphics graphics, Rectangle bounds, boolean focused) {}

        @Override
        protected JButton createArrowButton() {
            JButton arrow = new JButton() {
                @Override
                protected void paintComponent(Graphics graphics) {
                    Graphics2D paint = smooth(graphics);
                    int x = getWidth() / 2;
                    int y = getHeight() / 2;
                    paint.setColor(getModel().isRollover() ? ACCENT : MUTED);
                    paint.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    paint.drawPolyline(new int[] {x - 4, x, x + 4}, new int[] {y - 2, y + 2, y - 2}, 3);
                    paint.dispose();
                }
            };
            arrow.setOpaque(false);
            arrow.setContentAreaFilled(false);
            arrow.setBorder(BorderFactory.createEmptyBorder());
            arrow.setRolloverEnabled(true);
            return arrow;
        }
    }

    private static final class CharcoalMetal extends DefaultMetalTheme {
        @Override protected ColorUIResource getPrimary1() { return new ColorUIResource(COMPLETED); }
        @Override protected ColorUIResource getPrimary2() { return new ColorUIResource(SELECTED); }
        @Override protected ColorUIResource getPrimary3() { return new ColorUIResource(COMPLETED); }
        @Override protected ColorUIResource getSecondary1() { return new ColorUIResource(SHADOW); }
        @Override protected ColorUIResource getSecondary2() { return new ColorUIResource(EDGE); }
        @Override protected ColorUIResource getSecondary3() { return new ColorUIResource(SURFACE); }
        @Override protected ColorUIResource getBlack() { return new ColorUIResource(INK); }
        @Override protected ColorUIResource getWhite() { return new ColorUIResource(RECESSED); }
    }
}
