package sc.fidelis.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.testkit.IjState;
import sc.fidelis.testkit.ProductEngine;

/**
 * Scenario bodies for {@link WorkspaceLayoutTest}, on the product engine. The J-12 journey is the one the
 * {@code --scenario j12} screenshot run performs ({@link ScreenshotJ12#j12}): every step goes through the UI's own
 * paths and the numbers are asserted against the pinned-Fiji baseline there. This scenario then asserts the
 * geometry of the workspace, read from the live components.
 */
final class LayoutScenario {
    private static Workspace ws;
    private static Engine engine;
    private static boolean blackBefore;
    private static int measurementsBefore;
    private static int precisionBefore;

    private LayoutScenario() {}

    static void start() throws Exception {
        engine = ProductEngine.get();
        blackBefore = ij.Prefs.blackBackground;
        measurementsBefore = ij.plugin.filter.Analyzer.getMeasurements();
        precisionBefore = ij.plugin.filter.Analyzer.getPrecision();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            ws = Workspace.create(engine, null, new Dimension(1500, 940));
            // The OS clamps a decorated frame to this desktop (1920x1080 is larger than many developer screens); the
            // geometry under test is the root pane, which an undecorated frame renders at the exact requested size.
            ws.frame().setUndecorated(true);
            ws.frame().setVisible(true);
        });
        waitFor("engine ready", () -> engine.state() == EngineState.READY, 60);
        edt(() -> {
            ws.frame().validate();
            ws.docks().applyDefaultDividers();
        });
    }

    /** Leave the shared JVM as found: no documents, the original globals, no Results rows, no ROI Manager entries. */
    static void stop() throws Exception {
        edt(() -> {
            if (ws != null) ws.frame().dispose();
        });
        var open = new ArrayList<>(engine.documents());
        if (!open.isEmpty()) // clear the Results through the engine so that its mirror of the IJ1 table is rebuilt, not just the table
            engine.submit(ActionRequest.command(Origin.MENU, "ij1:Analyze>Clear Results", Map.of(), TargetScope.of(open.get(0).id()))).get(30, TimeUnit.SECONDS);
        for (var d : open) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("discard", true);
            engine.submit(ActionRequest.session(Origin.MENU, Operations.CLOSE, p, TargetScope.of(d.id()))).get(30, TimeUnit.SECONDS);
        }
        IjState.reset();
        ij.Prefs.blackBackground = blackBefore;
        System.out.println("[layout] after cleanup: documents=" + engine.documents().stream().map(d -> d.title()).toList()
                + " results=" + engine.results().size() + " blackBackground=" + ij.Prefs.blackBackground);
        ij.plugin.filter.Analyzer.setMeasurements(measurementsBefore);
        ij.plugin.filter.Analyzer.setPrecision(precisionBefore);
    }

    private static void waitFor(String what, BooleanSupplier c, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(40);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static Rectangle onFrame(Component c) {
        return SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), ws.frame().getRootPane());
    }

    // ---- the demo state at both resolutions ---------------------------------------------------------

    static void demoPanelsVisibleTogetherAtBothResolutions() throws Exception {
        Path repo = Path.of(System.getProperty("fidelis.repo.dir", "."));
        Path blobs = repo.resolve("fixtures").resolve("cache").resolve("FX-003").resolve("blobs.gif");
        assertTrue(Files.isRegularFile(blobs), "fixture not cached (run verification/fetch_fixtures.py): " + blobs);

        ScreenshotJ12.j12(ws, ws.context(), "j12", repo); // asserts Measure 65024 / 155.9435746801181 and 71 ROIs, 71 rows
        assertLayout(1500, 940);

        edt(() -> {
            ws.frame().setSize(1920, 1080);
            ws.frame().validate();
            ws.docks().applyDefaultDividers();
            ws.frame().validate();
        });
        assertLayout(1920, 1080);
    }

    private static void assertLayout(int width, int height) throws Exception {
        waitFor("frame " + width + "x" + height, () -> ws.frame().getWidth() == width && ws.frame().getHeight() == height, 10);
        waitFor("results, ROI list and history showing", () -> ws.results().isShowing() && ws.rois().isShowing() && ws.history().isShowing(), 10);
        Thread.sleep(300); // let the pending layout passes settle before reading sizes
        String where = " at " + width + "x" + height + " (" + ws.docks().describe() + ")";
        System.out.println("[layout]" + where);

        DocumentView v = ws.documents().focusedView();
        assertNotNull(v, "a focused document view" + where);
        waitFor("canvas laid out", () -> v.canvas().isShowing() && v.canvas().getWidth() > 0 && v.canvas().getHeight() > 0, 10);
        assertEquals(71, engine.rois(v.id()).size(), "the focused document carries the 71 particle ROIs" + where);

        ResultsPanel results = ws.results();
        assertTrue(results.getWidth() > 0 && results.getHeight() > 0, "Results has a size" + where);
        assertTrue(results.headerText().startsWith("Results — 71 row(s)"), "Results header shows the count, was: " + results.headerText() + where);
        assertTrue(results.visibleColumnCount() >= 6, "at least six Results columns visible without scrolling, was "
                + results.visibleColumnCount() + " of " + results.columnCount() + where);

        RoiPanel rois = ws.rois();
        assertTrue(rois.getWidth() > 0 && rois.getHeight() > 0, "ROI list has a size" + where);
        assertEquals(71, rois.rowCount(), "ROI list rows" + where);

        HistoryPanel history = ws.history();
        assertTrue(history.getWidth() > 0 && history.getHeight() > 0, "History has a size" + where);
        assertTrue(history.rowCount() >= 9, "History lists the journey steps, rows=" + history.rowCount() + where);
        assertTrue(ws.docks().tools.isShowing(), "the tool panels (Parameters/B&C/Threshold) stay visible" + where);

        // the four regions are distinct areas of the frame: no panel is painted over another
        Rectangle canvas = onFrame(v.canvas());
        Rectangle r = onFrame(results);
        Rectangle o = onFrame(rois);
        Rectangle h = onFrame(history);
        Rectangle[] all = {canvas, r, o, h};
        String[] names = {"canvas", "results", "roi list", "history"};
        for (int i = 0; i < all.length; i++)
            for (int j = i + 1; j < all.length; j++)
                assertFalse(all[i].intersects(all[j]), names[i] + " " + all[i] + " overlaps " + names[j] + " " + all[j] + where);
        Rectangle frame = new Rectangle(0, 0, ws.frame().getRootPane().getWidth(), ws.frame().getRootPane().getHeight());
        for (int i = 0; i < all.length; i++) assertTrue(frame.contains(all[i]), names[i] + " " + all[i] + " lies inside the frame" + where);
    }

    // ---- dark theme (issue #20) ----------------------------------------------------------------------------

    /** Switching to the dark theme keeps every demo panel laid out and the editor follows (RSyntaxTextArea dark theme). */
    static void darkThemeKeepsTheDemoLayout() throws Exception {
        try {
            edt(() -> {
                Theme.setDark(true);
                ws.frame().setSize(1500, 940);
                ws.frame().validate();
                ws.docks().applyDefaultDividers();
                ws.frame().validate();
            });
            edt(() -> { }); // the editor re-applies its colours after the tree update
            assertTrue(Theme.isDark(), "dark theme installed");
            assertLayout(1500, 940);
            java.awt.Color bg = ws.editor().editorBackground();
            assertTrue(bg.getRed() + bg.getGreen() + bg.getBlue() < 3 * 110, "the macro editor follows the dark theme, background was " + bg);
            java.awt.Color panel = ws.results().getBackground();
            assertTrue(panel.getRed() + panel.getGreen() + panel.getBlue() < 3 * 110, "panels are dark, Results background was " + panel);
        } finally {
            edt(() -> Theme.setDark(false));
            edt(() -> { });
        }
    }

    // ---- dividers and reset ---------------------------------------------------------------------------

    private static void move(JSplitPane sp, int delta) throws Exception {
        edt(() -> {
            sp.setDividerLocation(sp.getDividerLocation() + delta);
            ws.frame().validate();
        });
    }


    static void dividersResizeAndResetRestoresDefaults() throws Exception {
        Docks d = ws.docks();
        JSplitPane h = d.horizontalSplit();
        JSplitPane v = d.verticalSplit();
        JSplitPane rc = d.rightColumnSplit();
        JSplitPane br = d.bottomRowSplit();
        edt(() -> {
            ws.frame().setSize(1500, 940);
            ws.frame().validate();
            d.applyDefaultDividers();
            ws.frame().validate();
        });
        int[] defaults = {h.getDividerLocation(), v.getDividerLocation(), rc.getDividerLocation(), br.getDividerLocation()};
        for (JSplitPane sp : new JSplitPane[] {h, v, rc, br}) {
            assertTrue(sp.getDividerSize() > 0 && sp.isEnabled(), "divider is draggable: " + sp.getOrientation());
        }
        // each divider, moved on its own, resizes the panel on its far side (one layout pass per move, as a drag does)
        int roiWidth = ws.rois().getWidth();
        move(h, -120);
        assertTrue(ws.rois().getWidth() >= roiWidth + 100, "right column widened with its divider: " + roiWidth + " -> " + ws.rois().getWidth());
        int resultsHeight = ws.results().getHeight();
        move(v, -80);
        assertTrue(ws.results().getHeight() >= resultsHeight + 60, "bottom row grew with its divider: " + resultsHeight + " -> " + ws.results().getHeight());
        int roiHeight = ws.rois().getHeight();
        move(rc, -60);
        assertTrue(ws.rois().getHeight() >= roiHeight + 40, "ROI list grew with its divider: " + roiHeight + " -> " + ws.rois().getHeight());
        int resultsWidth = ws.results().getWidth();
        move(br, 100);
        assertTrue(ws.results().getWidth() >= resultsWidth + 80, "Results widened with its divider: " + resultsWidth + " -> " + ws.results().getWidth());
        assertNotEquals(defaults[0], h.getDividerLocation(), "right dock divider moved");
        assertNotEquals(defaults[1], v.getDividerLocation(), "bottom dock divider moved");

        edt(ws::resetLayout);
        waitFor("defaults restored", () -> h.getDividerLocation() == defaults[0] && v.getDividerLocation() == defaults[1]
                && rc.getDividerLocation() == defaults[2] && br.getDividerLocation() == defaults[3], 10);

        // collapsing the bottom dock hides Results and History; selecting a tab (or showResults) brings them back
        edt(() -> d.setBottomCollapsed(true));
        waitFor("bottom collapsed", () -> d.isBottomCollapsed() && !ws.results().isShowing() && !ws.history().isShowing(), 10);
        edt(d::showResults);
        waitFor("bottom restored", () -> !d.isBottomCollapsed() && ws.results().isShowing() && ws.history().isShowing()
                && v.getDividerLocation() == defaults[1], 10);
        edt(() -> d.setRightVisible(false));
        waitFor("right collapsed", () -> !d.isRightVisible() && !ws.rois().isShowing(), 10);
        edt(d::showRoi);
        waitFor("right restored", () -> d.isRightVisible() && ws.rois().isShowing(), 10);
    }
}
