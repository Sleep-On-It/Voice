package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.FlatClientProperties;

/**
 * Right and bottom docks (docs D12 section 2, DEC-029): fixed regions built from {@link JSplitPane} and
 * {@link JTabbedPane} only, no third-party docking library. Each dock holds <em>two</em> independent tab
 * groups so that the demo state is visible at once (issue #15): the right column stacks the contextual tool
 * panels (Parameters, B&amp;C, Threshold) above the ROI list; the bottom row puts Results beside
 * History &amp; macro / Log / Activity. Every divider is draggable; {@link #applyDefaultDividers()} restores the
 * proportions below for the current frame size. Collapsing the right dock leaves a thin restore strip;
 * collapsing the bottom dock leaves its two tab strips.
 */
final class Docks {
    /** Right column width; a wide frame (1920 px class) gets a little more for the ROI list. */
    static final int RIGHT_WIDTH = 380;
    static final int RIGHT_WIDTH_WIDE = 420;
    static final int WIDE_FRAME = 1800;
    /** Bottom row height: a share of the dock area, bounded so Results keep a useful number of rows. */
    static final double BOTTOM_FRACTION = 0.30;
    static final int BOTTOM_MIN = 220;
    static final int BOTTOM_MAX = 320;
    /** Share of the bottom row given to Results (left) and of the right column given to the tool panels (top). */
    static final double RESULTS_FRACTION = 0.46;
    static final double TOOLS_FRACTION = 0.52;
    private static final int STRIP = 24;
    private static final int COLLAPSED_BOTTOM = 30;

    /** Upper right group: Parameters, B&amp;C, Threshold. */
    final JTabbedPane tools = new JTabbedPane();
    /** Lower right group: ROI list. */
    final JTabbedPane roiGroup = new JTabbedPane();
    /** Bottom-left group: Results. */
    final JTabbedPane resultsGroup = new JTabbedPane();
    /** Bottom-right group: History &amp; macro, Log, Activity. */
    final JTabbedPane bottom = new JTabbedPane();

    private final JSplitPane horizontal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
    private final JSplitPane rightColumn = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
    private final JSplitPane bottomRow = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
    private final JPanel rightStrip = new JPanel(new BorderLayout());
    private boolean rightVisible = true;
    private boolean bottomCollapsed;
    private int lastBottomHeight = BOTTOM_MIN;
    private int lastBottomTab = 0;
    private int lastRightWidth = RIGHT_WIDTH;

    private JComponent documentsPane;
    private JComponent parametersTab;
    private JComponent displayTab;
    private JComponent thresholdTab;
    private JComponent roiTab;
    private JComponent resultsTab;
    private JComponent historyTab;
    private JComponent editorTab;
    private JComponent logTab;
    private JComponent activityTab;

    JComponent build(JComponent documents, JComponent parameters, JComponent display, JComponent threshold,
                     JComponent roi, JComponent results, JComponent history, JComponent editor, JComponent log, JComponent activity) {
        documentsPane = documents;
        parametersTab = parameters;
        displayTab = display;
        thresholdTab = threshold;
        roiTab = roi;
        resultsTab = results;
        historyTab = history;
        editorTab = editor;
        logTab = log;
        activityTab = activity;
        for (JTabbedPane p : new JTabbedPane[] {tools, roiGroup, resultsGroup, bottom})
            p.putClientProperty(FlatClientProperties.STYLE, "tabInsets: 4,8,4,8");
        tools.addTab("Parameters", parameters);
        tools.addTab("B&C", display);
        tools.addTab("Threshold", threshold);
        roiGroup.addTab("ROI list", roi);
        resultsGroup.addTab("Results", results);
        bottom.addTab("History & macro", history);
        bottom.addTab("Macro editor", editor);
        bottom.addTab("Log", log);
        bottom.addTab("Activity", activity);

        tools.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT,
                collapseButton(UiIcons.Kind.RIGHT, "Collapse the right dock", () -> setRightVisible(false)));
        bottom.putClientProperty(FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT,
                collapseButton(UiIcons.Kind.DOWN, "Collapse the bottom dock", () -> setBottomCollapsed(true)));
        // Selecting any tab of a collapsed bottom row expands it again
        resultsGroup.addChangeListener(e -> {
            if (bottomCollapsed && resultsGroup.getSelectedIndex() >= 0) setBottomCollapsed(false);
        });
        bottom.addChangeListener(e -> {
            if (bottomCollapsed && bottom.getSelectedIndex() >= 0) setBottomCollapsed(false);
            else if (bottom.getSelectedIndex() >= 0) lastBottomTab = bottom.getSelectedIndex();
        });

        JButton restore = new JButton(UiIcons.of(UiIcons.Kind.LEFT));
        restore.setToolTipText("Show the right dock");
        restore.setMargin(new Insets(2, 2, 2, 2));
        restore.setFocusable(false);
        restore.addActionListener(e -> setRightVisible(true));
        rightStrip.add(restore, BorderLayout.NORTH);
        rightStrip.setPreferredSize(new Dimension(STRIP, 100));
        rightStrip.setMinimumSize(new Dimension(STRIP, 0));

        tools.setMinimumSize(new Dimension(220, 100));
        roiGroup.setMinimumSize(new Dimension(220, 60));
        resultsGroup.setMinimumSize(new Dimension(160, 28));
        bottom.setMinimumSize(new Dimension(160, 28));

        rightColumn.setTopComponent(tools);
        rightColumn.setBottomComponent(roiGroup);
        rightColumn.setResizeWeight(TOOLS_FRACTION);
        rightColumn.setContinuousLayout(true);
        rightColumn.setBorder(null);
        rightColumn.setMinimumSize(new Dimension(220, 160));

        bottomRow.setLeftComponent(resultsGroup);
        bottomRow.setRightComponent(bottom);
        bottomRow.setResizeWeight(RESULTS_FRACTION);
        bottomRow.setContinuousLayout(true);
        bottomRow.setBorder(null);
        bottomRow.setMinimumSize(new Dimension(320, 28));

        horizontal.setLeftComponent(documents);
        horizontal.setRightComponent(rightColumn);
        horizontal.setResizeWeight(1.0);
        horizontal.setContinuousLayout(true);
        horizontal.setBorder(null);
        vertical.setTopComponent(horizontal);
        vertical.setBottomComponent(bottomRow);
        vertical.setResizeWeight(1.0);
        vertical.setContinuousLayout(true);
        vertical.setBorder(null);
        return vertical;
    }

    private static JComponent collapseButton(UiIcons.Kind glyph, String tip, Runnable r) {
        JButton b = new JButton(UiIcons.of(glyph));
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setMargin(new Insets(0, 6, 0, 6));
        b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        b.addActionListener(e -> r.run());
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
        p.add(b);
        return p;
    }

    // ---- default geometry -----------------------------------------------------------------------

    static int defaultRightWidth(int dockAreaWidth) {
        return dockAreaWidth >= WIDE_FRAME ? RIGHT_WIDTH_WIDE : RIGHT_WIDTH;
    }

    static int defaultBottomHeight(int dockAreaHeight) {
        return (int) Math.max(BOTTOM_MIN, Math.min(BOTTOM_MAX, Math.round(dockAreaHeight * BOTTOM_FRACTION)));
    }

    /** Default positions for the current size: right column, bottom row, and both inner dividers. Call after the frame has a size. */
    void resetLayout() {
        setRightVisible(true);
        setBottomCollapsed(false);
        SwingUtilities.invokeLater(this::applyDefaultDividers);
    }

    /** Apply the default divider positions immediately (the caller has validated the frame). */
    void applyDefaultDividers() {
        int w = horizontal.getWidth();
        int h = vertical.getHeight();
        if (w <= 0 || h <= 0) return;
        int rw = defaultRightWidth(w);
        int bh = defaultBottomHeight(h);
        int top = Math.max(200, h - bh - vertical.getDividerSize());
        horizontal.setDividerLocation(Math.max(200, w - rw - horizontal.getDividerSize()));
        vertical.setDividerLocation(top);
        // The nested splits are sized by the outer dividers: lay them out first, otherwise a divider placed beyond
        // the nested pane's current extent is clamped (JSplitPane is a validate root; this is synchronous).
        vertical.validate();
        horizontal.validate();
        rightColumn.setDividerLocation((int) Math.round(top * TOOLS_FRACTION));
        bottomRow.setDividerLocation((int) Math.round(w * RESULTS_FRACTION));
        lastRightWidth = rw;
        lastBottomHeight = bh;
    }

    // ---- visibility -----------------------------------------------------------------------------

    void setRightVisible(boolean visible) {
        if (visible == rightVisible) return;
        if (!visible) lastRightWidth = Math.max(220, horizontal.getWidth() - horizontal.getDividerLocation() - horizontal.getDividerSize());
        rightVisible = visible;
        horizontal.setRightComponent(visible ? rightColumn : rightStrip);
        int w = visible ? lastRightWidth : STRIP;
        SwingUtilities.invokeLater(() -> horizontal.setDividerLocation(horizontal.getWidth() - w - horizontal.getDividerSize()));
    }

    boolean isRightVisible() {
        return rightVisible;
    }

    void setBottomCollapsed(boolean collapsed) {
        if (collapsed == bottomCollapsed) return;
        bottomCollapsed = collapsed;
        if (collapsed) {
            lastBottomHeight = Math.max(120, vertical.getHeight() - vertical.getDividerLocation() - vertical.getDividerSize());
            resultsGroup.setSelectedIndex(-1);
            bottom.setSelectedIndex(-1);
            SwingUtilities.invokeLater(() -> vertical.setDividerLocation(vertical.getHeight() - COLLAPSED_BOTTOM - vertical.getDividerSize()));
        } else {
            if (resultsGroup.getSelectedIndex() < 0) resultsGroup.setSelectedIndex(0);
            if (bottom.getSelectedIndex() < 0) bottom.setSelectedIndex(Math.min(lastBottomTab, bottom.getTabCount() - 1));
            SwingUtilities.invokeLater(() -> vertical.setDividerLocation(vertical.getHeight() - lastBottomHeight - vertical.getDividerSize()));
        }
    }

    boolean isBottomCollapsed() {
        return bottomCollapsed;
    }

    void collapseAll() {
        setRightVisible(false);
        setBottomCollapsed(true);
    }

    // ---- show a panel ---------------------------------------------------------------------------

    private void showTools(JComponent tab) {
        setRightVisible(true);
        tools.setSelectedComponent(tab);
    }

    private void showBottom(JComponent tab) {
        if (bottomCollapsed) setBottomCollapsed(false);
        bottom.setSelectedComponent(tab);
    }

    void showParameters() {
        showTools(parametersTab);
    }

    void showDisplay() {
        showTools(displayTab);
    }

    void showThreshold() {
        showTools(thresholdTab);
    }

    void showRoi() {
        setRightVisible(true);
        roiGroup.setSelectedComponent(roiTab);
    }

    void showResults() {
        if (bottomCollapsed) setBottomCollapsed(false);
        resultsGroup.setSelectedComponent(resultsTab);
    }

    void showHistory() {
        showBottom(historyTab);
    }

    void showEditor() {
        showBottom(editorTab);
    }

    void showLog() {
        showBottom(logTab);
    }

    void showActivity() {
        showBottom(activityTab);
    }

    // ---- introspection (tests, screenshot runs) --------------------------------------------------

    JSplitPane horizontalSplit() {
        return horizontal;
    }

    JSplitPane verticalSplit() {
        return vertical;
    }

    JSplitPane rightColumnSplit() {
        return rightColumn;
    }

    JSplitPane bottomRowSplit() {
        return bottomRow;
    }

    /** One line with the on-screen size of every region; sizes are read from the components, not computed. */
    String describe() {
        return "documents=" + geometry(documentsPane) + " tools=" + geometry(tools) + " roi=" + geometry(roiTab)
                + " results=" + geometry(resultsTab) + " history=" + geometry(historyTab)
                + " rightVisible=" + rightVisible + " bottomCollapsed=" + bottomCollapsed;
    }

    private static String geometry(JComponent c) {
        return c == null ? "none" : (c.isShowing() ? "" : "hidden:") + c.getWidth() + "x" + c.getHeight();
    }
}
