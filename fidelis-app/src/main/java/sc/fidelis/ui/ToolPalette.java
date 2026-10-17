package sc.fidelis.ui;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.Timer;

import ij.gui.Toolbar;

/**
 * The tool palette (docs D12 UI-03, D14 C-14): IJ1's own tools as toggle buttons. A click calls
 * {@code Toolbar.setTool}; the selection is read back from IJ1 (keyboard shortcuts and native
 * tool changes keep the palette in sync). Tool behaviour on the canvas is IJ1's own.
 */
public final class ToolPalette extends JPanel {
    private final UiContext ctx;
    private final List<JToggleButton> buttons = new ArrayList<>();
    private final ButtonGroup group = new ButtonGroup();
    private final Timer sync;
    private int lastId = -1;

    ToolPalette(UiContext ctx) {
        this.ctx = ctx;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.panelBorder()), BorderFactory.createEmptyBorder(6, 4, 6, 4)));
        for (ToolIcons.Tool t : ToolIcons.TOOLS) {
            JToggleButton b = new JToggleButton(ToolIcons.of(t.id()));
            b.setToolTipText(t.name() + " — " + t.hint());
            b.getAccessibleContext().setAccessibleName(t.name() + " tool");
            b.setFocusable(false);
            b.setMargin(new java.awt.Insets(3, 3, 3, 3));
            Dimension d = new Dimension(34, 30);
            b.setPreferredSize(d);
            b.setMaximumSize(d);
            b.setMinimumSize(d);
            b.addActionListener(e -> choose(t));
            b.putClientProperty("toolId", t.id());
            b.putClientProperty("toolName", t.name());
            group.add(b);
            buttons.add(b);
            add(b);
            add(Box.createVerticalStrut(2));
        }
        add(Box.createVerticalGlue());
        sync = new Timer(250, e -> readBack());
        sync.start();
        readBack();
    }

    private boolean labelled;

    /** Show each tool's name under its icon (issue #20); icon-only by default so the palette stays 34 px wide. */
    void setLabelled(boolean on) {
        labelled = on;
        for (JToggleButton b : buttons) {
            b.setText(on ? String.valueOf(b.getClientProperty("toolName")) : null);
            b.setVerticalTextPosition(javax.swing.SwingConstants.BOTTOM);
            b.setHorizontalTextPosition(javax.swing.SwingConstants.CENTER);
            b.setIconTextGap(1);
            b.setFont(b.getFont().deriveFont(on ? 10f : (float) Theme.UI_FONT_SIZE));
            Dimension d = on ? new Dimension(66, 46) : new Dimension(34, 30);
            b.setPreferredSize(d);
            b.setMaximumSize(d);
            b.setMinimumSize(d);
        }
        revalidate();
        repaint();
    }

    boolean isLabelled() {
        return labelled;
    }

    private void choose(ToolIcons.Tool t) {
        Toolbar tb = Toolbar.getInstance();
        if (tb == null) {
            ctx.notice("The Fiji toolbar is not available, so tools cannot be selected", true);
            return;
        }
        tb.setTool(t.id());
        readBack();
    }

    /** Select the button of IJ1's current tool and report its name in the status bar. */
    void readBack() {
        int id = Toolbar.getToolId();
        if (id == lastId) return;
        lastId = id;
        boolean matched = false;
        for (JToggleButton b : buttons) {
            boolean on = (int) b.getClientProperty("toolId") == id;
            if (on) {
                b.setSelected(true);
                matched = true;
            }
        }
        if (!matched) group.clearSelection();
        String name = null;
        for (ToolIcons.Tool t : ToolIcons.TOOLS) if (t.id() == id) name = t.name();
        if (name == null) name = Toolbar.getToolName();
        if (ctx.status() != null) ctx.status().setTool(name == null ? "-" : name);
    }

    int currentToolId() {
        return Toolbar.getToolId();
    }

    void setEnabledTools(boolean enabled) {
        for (JToggleButton b : buttons) b.setEnabled(enabled);
    }

    List<JToggleButton> buttons() {
        return buttons;
    }
}
