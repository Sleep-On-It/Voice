package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.MeasurementRow;

/** Measurements at full double precision (docs D12 UI-08, FR-MEAS-003); copy as tab-separated text. */
public final class ResultsPanel extends JPanel {
    private final UiContext ctx;
    private final JLabel header = new JLabel();
    private final DefaultTableModel model = new DefaultTableModel() {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JButton copy = new JButton("Copy as TSV");
    private List<String> headings = List.of();

    ResultsPanel(UiContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        add(header, BorderLayout.NORTH);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setShowGrid(false);
        table.setShowHorizontalLines(true);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(DefaultTableCellRenderer.RIGHT);
        table.setDefaultRenderer(Object.class, right);
        JScrollPane sp = new JScrollPane(table);
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.panelBorder()));
        add(sp, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        copy.setToolTipText("Copy all rows, full precision, tab-separated");
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(tsv()), null);
            ctx.notice("Copied " + model.getRowCount() + " result rows", false);
        });
        buttons.add(copy);
        add(buttons, BorderLayout.SOUTH);
        refresh();
    }

    /** Full-precision text of a double: the shortest string that round-trips, never scientific. */
    public static String full(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return new BigDecimal(Double.toString(d)).toPlainString();
    }

    void refresh() {
        MeasurementRow.Columns cols = ctx.engine.resultColumns();
        List<MeasurementRow> rows = ctx.engine.results();
        headings = cols.headings();
        List<String> names = new ArrayList<>();
        names.add("#");
        names.add("Label");
        names.addAll(headings);
        model.setDataVector(new Object[0][0], names.toArray());
        for (MeasurementRow r : rows) {
            Object[] cells = new Object[names.size()];
            cells[0] = r.rowIndex() + 1;
            cells[1] = r.label() == null ? "" : r.label();
            for (int i = 0; i < headings.size(); i++) {
                Double v = r.values().get(headings.get(i));
                cells[2 + i] = v == null ? "" : full(v);
            }
            model.addRow(cells);
        }
        boolean anyLabel = rows.stream().anyMatch(r -> r.label() != null && !r.label().isEmpty());
        for (int i = 0; i < table.getColumnCount(); i++) {
            var col = table.getColumnModel().getColumn(i);
            if (i == 1 && !anyLabel) { // an empty Label column only costs width; the data stays in the model and the TSV
                col.setMinWidth(0);
                col.setMaxWidth(0);
                col.setPreferredWidth(0);
            } else {
                col.setMinWidth(15);
                col.setMaxWidth(Integer.MAX_VALUE);
                col.setPreferredWidth(i == 0 ? 36 : fit(i, 60));
            }
        }
        header.setText("Results — " + rows.size() + " row(s), full precision");
        copy.setEnabled(!rows.isEmpty());
    }

    /** Width that shows every value of column {@code c} in full (full precision must never be clipped). */
    private int fit(int c, int minimum) {
        java.awt.FontMetrics fm = table.getFontMetrics(table.getFont());
        int w = fm.stringWidth(String.valueOf(model.getColumnName(c))) + 16;
        for (int r = 0; r < model.getRowCount(); r++) w = Math.max(w, fm.stringWidth(String.valueOf(model.getValueAt(r, c))) + 16);
        return Math.max(minimum, w);
    }

    /**
     * Number of measurement columns (excluding the row index and the Label) whose full width lies inside the
     * table's viewport without horizontal scrolling: the visibility criterion of issue #15 (at least six).
     */
    int visibleColumnCount() {
        java.awt.Container vp = table.getParent();
        int available = vp instanceof javax.swing.JViewport v ? v.getExtentSize().width : table.getWidth();
        int x = 0;
        int n = 0;
        for (int i = 0; i < table.getColumnCount(); i++) {
            int w = table.getColumnModel().getColumn(i).getWidth();
            if (x + w > available) break;
            x += w;
            if (i >= 2) n++;
        }
        return n;
    }

    /** Number of measurement columns in the model (the headings of the engine's Results table). */
    int columnCount() {
        return headings.size();
    }

    String headerText() {
        return header.getText();
    }

    public String tsv() {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < model.getColumnCount(); c++) sb.append(c == 0 ? "" : "\t").append(model.getColumnName(c));
        sb.append('\n');
        for (int r = 0; r < model.getRowCount(); r++) {
            for (int c = 0; c < model.getColumnCount(); c++) sb.append(c == 0 ? "" : "\t").append(model.getValueAt(r, c));
            sb.append('\n');
        }
        return sb.toString();
    }

    void handle(EngineEvent e) {
        if (e instanceof EngineEvent.ResultsChanged) refresh();
    }
}
