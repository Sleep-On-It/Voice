package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.Timer;

import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.Enums.OperationState;

/**
 * Operation lifecycle (docs D12 UI-12, D14 C-10). A bar is determinate only when the engine reports
 * real progress ({@code OperationProgress} with a total); otherwise it is indeterminate with real
 * elapsed time. Cancel is always beside a running operation.
 */
public final class ActivityPanel extends JPanel {
    private static final int KEEP = 60;

    private final UiContext ctx;
    private final JPanel rows = new JPanel();
    private final Map<String, Row> byOp = new LinkedHashMap<>();
    private final Timer clock;

    private final class Row extends JPanel {
        final String opId;
        final String label;
        final JLabel name = new JLabel();
        final JLabel state = new JLabel();
        final JProgressBar bar = new JProgressBar();
        final JButton cancel = new JButton("Cancel");
        OperationState current = OperationState.QUEUED;
        String statusText = "";
        long startNanos = System.nanoTime();
        long endNanos;
        boolean determinate;

        Row(String opId, String label) {
            super(new BorderLayout(8, 0));
            this.opId = opId;
            this.label = label;
            setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            name.setFont(name.getFont().deriveFont(Font.BOLD));
            name.setPreferredSize(new Dimension(190, 18));
            state.setPreferredSize(new Dimension(260, 18));
            bar.setPreferredSize(new Dimension(150, 10));
            cancel.setMargin(new java.awt.Insets(0, 8, 0, 8));
            cancel.addActionListener(e -> ctx.engine.cancel(opId));
            JPanel left = new JPanel(new BorderLayout(8, 0));
            left.setOpaque(false);
            left.add(name, BorderLayout.WEST);
            left.add(state, BorderLayout.CENTER);
            JPanel right = new JPanel(new BorderLayout(8, 0));
            right.setOpaque(false);
            right.add(bar, BorderLayout.CENTER);
            right.add(cancel, BorderLayout.EAST);
            add(left, BorderLayout.CENTER);
            add(right, BorderLayout.EAST);
            name.setText(label);
            update();
        }

        /** Queued or running: a parked operation (awaiting the user's confirmation) is not activity and cannot be cancelled. */
        boolean active() {
            return !UiContext.terminal(current) && current != OperationState.AWAITING_CONFIRMATION;
        }

        void update() {
            double secs = ((active() ? System.nanoTime() : endNanos) - startNanos) / 1e9;
            String text = switch (current) {
                case QUEUED -> "Queued";
                case VALIDATING -> "Checking";
                case AWAITING_CONFIRMATION -> "Awaiting confirmation";
                case RUNNING -> "Running" + (statusText.isEmpty() ? "" : " · " + statusText);
                case WAITING_FOR_DIALOG -> "Waiting for a Fiji dialog";
                case COMPLETED -> "Completed";
                case FAILED -> "Failed";
                case CANCELLED -> "Cancelled";
                case ROLLED_BACK -> "Rolled back";
                case PARTIAL -> "Partial result";
            };
            state.setText(String.format(Locale.ROOT, "%s  (%.1f s)", text, secs));
            state.setForeground(current == OperationState.FAILED ? Theme.error() : active() ? ProposalColors.fg() : Theme.muted());
            bar.setVisible(active());
            if (active()) {
                if (!determinate) bar.setIndeterminate(true);
            }
            cancel.setVisible(active());
        }
    }

    ActivityPanel(UiContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(rows, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrap);
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.panelBorder()));
        add(sp, BorderLayout.CENTER);
        JLabel hint = new JLabel("Progress bars fill only when the engine reports real progress; otherwise they are indeterminate.");
        hint.setForeground(Theme.muted());
        hint.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        add(hint, BorderLayout.SOUTH);
        clock = new Timer(300, e -> tick());
        clock.start();
    }

    private void tick() {
        for (Row r : byOp.values()) if (r.active()) r.update();
        publishStatus();
    }

    void handle(EngineEvent e) {
        if (e instanceof EngineEvent.OperationStateChanged s) {
            Row r = byOp.get(s.operationId());
            if (r == null) {
                String label = s.requestId() != null ? ctx.labelFor(s.requestId()) : null;
                r = new Row(s.operationId(), label != null ? label : s.operationId());
                byOp.put(s.operationId(), r);
                rows.add(r, 0);
                while (byOp.size() > KEEP) {
                    String oldest = byOp.keySet().iterator().next();
                    rows.remove(byOp.remove(oldest));
                }
            }
            r.current = s.state();
            if (!r.active()) r.endNanos = System.nanoTime();
            r.update();
        } else if (e instanceof EngineEvent.OperationProgress p) {
            Row r = byOp.get(p.operationId());
            if (r != null) {
                r.statusText = p.statusText() == null ? "" : p.statusText();
                if (p.total() > 0) {
                    r.determinate = true;
                    r.bar.setIndeterminate(false);
                    r.bar.setMaximum((int) Math.min(Integer.MAX_VALUE, p.total()));
                    r.bar.setValue((int) Math.min(Integer.MAX_VALUE, p.current()));
                } else {
                    r.determinate = false;
                }
                r.update();
            }
        } else {
            return;
        }
        rows.revalidate();
        rows.repaint();
        publishStatus();
    }

    /** Status-bar summary: the first active operation, with its real progress if any. */
    private void publishStatus() {
        for (Row r : byOp.values()) {
            if (r.active()) {
                double secs = (System.nanoTime() - r.startNanos) / 1e9;
                int pct = r.determinate && r.bar.getMaximum() > 0 ? (int) (100L * r.bar.getValue() / r.bar.getMaximum()) : -1;
                ctx.status().setActivity(String.format(Locale.ROOT, "%s: %s (%.1f s)",
                        r.current == OperationState.WAITING_FOR_DIALOG ? "Waiting for dialog" : "Running", r.label, secs), pct, true);
                return;
            }
        }
        ctx.status().setActivity("", -1, false);
    }

    List<String> activeLabels() {
        List<String> out = new ArrayList<>();
        for (Row r : byOp.values()) if (r.active()) out.add(r.label);
        return out;
    }

    int rowCount() {
        return byOp.size();
    }

    /** Foreground colour lookup kept in one place. */
    private static final class ProposalColors {
        static java.awt.Color fg() {
            java.awt.Color c = javax.swing.UIManager.getColor("Label.foreground");
            return c != null ? c : java.awt.Color.BLACK;
        }
    }
}
