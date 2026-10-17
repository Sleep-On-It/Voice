package sc.fidelis.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import ij.plugin.frame.RoiManager;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.testkit.IjState;
import sc.fidelis.testkit.ProductEngine;

/**
 * Macro editor (issue #19) on the product engine: the macro Fidelis generated from a real J-12 run is put
 * in the editor and run as CODE. Nothing runs before the capability confirmation; declining runs nothing;
 * confirming reproduces the J-12 numbers (71 Results rows, 71 ROIs in Fiji's ROI Manager) on a Blobs the
 * macro opens itself; the history carries the CODE step and the steps the engine observed under it. A
 * second run shows Log lines in the console and is cancelled from the editor.
 */
final class MacroEditorScenario {
    private static Workspace ws;
    private static Engine engine;
    private static boolean blackBefore;
    private static int measurementsBefore;
    private static int precisionBefore;

    private MacroEditorScenario() {}

    static void start() throws Exception {
        engine = ProductEngine.get();
        blackBefore = ij.Prefs.blackBackground;
        measurementsBefore = ij.plugin.filter.Analyzer.getMeasurements();
        precisionBefore = ij.plugin.filter.Analyzer.getPrecision();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            ws = Workspace.create(engine, null, new Dimension(1500, 940));
            ws.frame().setUndecorated(true);
            ws.frame().setVisible(true);
        });
        waitFor("engine ready", () -> engine.state() == EngineState.READY, 60);
        edt(() -> {
            ws.frame().validate();
            ws.docks().applyDefaultDividers();
        });
    }

    static void stop() throws Exception {
        if (ws != null && ws.editor().isRunning()) { // never leave a macro running in the shared JVM
            edt(() -> ws.editor().cancel());
            waitFor("running macro cancelled before cleanup", () -> !ws.editor().isRunning(), 90);
        }
        edt(() -> {
            if (ws != null) ws.frame().dispose();
        });
        closeAllAndClear();
        ij.Prefs.blackBackground = blackBefore;
        ij.plugin.filter.Analyzer.setMeasurements(measurementsBefore);
        ij.plugin.filter.Analyzer.setPrecision(precisionBefore);
        System.out.println("[editor] after cleanup: documents=" + engine.documents().size() + " results=" + engine.results().size());
    }

    /** Clear Results through the engine (so its mirror follows), close every document, reset IJ1's singletons. */
    private static void closeAllAndClear() throws Exception {
        var open = new ArrayList<>(engine.documents());
        if (!open.isEmpty())
            engine.submit(ActionRequest.command(Origin.MENU, "ij1:Analyze>Clear Results", Map.of(), TargetScope.of(open.get(0).id()))).get(30, TimeUnit.SECONDS);
        for (var d : open) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("discard", true);
            engine.submit(ActionRequest.session(Origin.MENU, Operations.CLOSE, p, TargetScope.of(d.id()))).get(30, TimeUnit.SECONDS);
        }
        IjState.reset();
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

    private static int managerCount() {
        RoiManager rm = RoiManager.getInstance2();
        return rm == null ? 0 : rm.getCount();
    }

    private static Path blobs() {
        Path repo = Path.of(System.getProperty("fidelis.repo.dir", "."));
        Path blobs = repo.resolve("fixtures").resolve("cache").resolve("FX-003").resolve("blobs.gif");
        assertTrue(Files.isRegularFile(blobs), "fixture not cached (run verification/fetch_fixtures.py): " + blobs);
        return blobs;
    }

    private static void openBlobs() throws Exception {
        int docs = engine.documents().size();
        ws.context().openPath(blobs()).get(30, TimeUnit.SECONDS);
        waitFor("blobs open and focused", () -> engine.documents().size() > docs && engine.focused().isPresent()
                && ws.documents().focusedView() != null, 30);
    }

    /**
     * The J-12 part of the exported macro: the settings header plus the steps from the last {@code open(...blobs.gif)}
     * on. In a fresh application session that is the whole export; the shared test JVM has earlier steps in front.
     */
    static String j12Segment(String exported) {
        String marker = "// ---- Steps ----";
        int steps = exported.indexOf(marker);
        int lastOpen = exported.lastIndexOf("open(\"");
        assertTrue(steps >= 0 && lastOpen > steps, "exported macro has a steps section and an open(): " + exported);
        String tail = exported.substring(lastOpen);
        assertTrue(tail.lines().findFirst().orElse("").contains("blobs.gif"), "the last open() is Blobs: " + tail.lines().findFirst().orElse(""));
        return exported.substring(0, steps + marker.length()) + "\n" + tail;
    }

    // ---- the exported J-12 macro, run with confirmation ----------------------------------------------

    static void exportedJ12MacroRunsInTheEditorAfterConfirmation() throws Exception {
        Path repo = Path.of(System.getProperty("fidelis.repo.dir", "."));
        blobs();
        ScreenshotJ12.j12(ws, ws.context(), "j12", repo); // the real journey through the UI; asserts 65024 / 155.94…, 71 ROIs, 71 rows
        String macro = j12Segment(engine.exportMacro());
        System.out.println("[editor] J-12 macro (" + macro.lines().count() + " lines):\n" + macro);

        // back to a clean slate, then one fresh Blobs as the focused document (the macro opens its own copy too)
        closeAllAndClear();
        assertEquals(0, engine.results().size(), "Results cleared through the engine");
        assertEquals(0, managerCount(), "ROI Manager empty");
        openBlobs();
        int docsBefore = engine.documents().size();
        int histBefore = engine.history().size();
        MacroEditorPanel editor = ws.editor();

        edt(() -> {
            ws.docks().showEditor();
            editor.setText(macro);
            editor.run();
        });
        waitFor("capability confirmation requested", editor::isAwaitingConfirmation, 30);
        assertTrue(editor.isShowing(), "the editor tab is showing");
        assertEquals(OperationState.AWAITING_CONFIRMATION, editor.lastResult().state());
        assertEquals(0, engine.results().size(), "nothing ran before confirmation (Results)");
        assertEquals(docsBefore, engine.documents().size(), "nothing ran before confirmation (documents)");
        System.out.println("[editor] awaiting confirmation; capabilities='" + editor.capabilitiesText() + "'; status=" + editor.statusText());

        // declining runs nothing
        edt(editor::declineRun);
        assertFalse(editor.isAwaitingConfirmation());
        assertFalse(editor.isRunning());
        Thread.sleep(300);
        assertEquals(0, engine.results().size(), "nothing ran after declining");
        assertEquals(docsBefore, engine.documents().size(), "nothing ran after declining");
        assertTrue(editor.consoleText().contains("Not run"), editor.consoleText());

        // the deliberate act
        edt(editor::run);
        waitFor("confirmation requested again", editor::isAwaitingConfirmation, 30);
        edt(editor::confirmRun);
        waitFor("macro run finished", () -> !editor.isRunning() && editor.lastResult() != null
                && editor.lastResult().state() != OperationState.AWAITING_CONFIRMATION, 240);
        ActionResult r = editor.lastResult();
        assertEquals(OperationState.COMPLETED, r.state(), "macro run: " + (r.error() == null ? "" : r.error().message()) + "\n" + editor.consoleText());

        // the numbers of the pinned-Fiji J-12 baseline, reproduced by the generated macro
        waitFor("71 Results rows mirrored", () -> engine.results().size() == 71, 20);
        assertEquals(71, managerCount(), "ROIs in Fiji's ROI Manager after the macro");
        assertTrue(engine.documents().size() >= docsBefore + 1, "the macro opened its own Blobs (and the particle mask): "
                + engine.documents().stream().map(d -> d.title()).toList());
        int mirrored = 0;
        for (var d : engine.documents()) mirrored = Math.max(mirrored, engine.rois(d.id()).size());
        System.out.println("[editor] after run: results=" + engine.results().size() + " roiManager=" + managerCount()
                + " engine.rois(max over documents)=" + mirrored + " documents=" + engine.documents().size() + " in " + r.durationMs() + " ms");

        // history: the CODE step and the observed children under it, both in the engine and in the History panel
        HistoryRecord code = r.record();
        assertNotNull(code, "the CODE step is recorded");
        assertTrue(engine.history().stream().anyMatch(h -> h.actionId().equals(code.actionId())), "CODE record in the history");
        long children = engine.history().stream().skip(histBefore)
                .filter(h -> h.params() != null && code.actionId().equals(String.valueOf(h.params().get("parentActionId")))).count();
        assertTrue(children >= 1, "observed child steps under the CODE step, got " + children);
        waitFor("History panel lists the run", () -> ws.history().rowCount() >= histBefore + 1 + children, 10);
        assertTrue(editor.consoleText().contains("Completed"), editor.consoleText());
        System.out.println("[editor] history: CODE " + code.actionId() + " (" + code.provenance() + ", " + code.macroLines().size()
                + " macro lines) + " + children + " observed child step(s); History panel rows=" + ws.history().rowCount());
    }

    // ---- console and cancel ------------------------------------------------------------------------------

    static void consoleShowsLogLinesAndCancelStopsTheMacro() throws Exception {
        if (engine.focused().isEmpty()) openBlobs();
        MacroEditorPanel editor = ws.editor();
        // (a) a macro that prints: its Log line reaches the console with the result (the engine reports a run's log
        //     lines in the digest; it emits no live LogLine events today) and the Log tab
        edt(() -> {
            editor.setText("print(\"editor console check\");\n");
            editor.run();
        });
        waitFor("confirmation requested", editor::isAwaitingConfirmation, 30);
        edt(editor::confirmRun);
        waitFor("print macro finished", () -> !editor.isRunning() && editor.lastResult() != null
                && editor.lastResult().state() != OperationState.AWAITING_CONFIRMATION, 60);
        assertEquals(OperationState.COMPLETED, editor.lastResult().state(), String.valueOf(editor.lastResult().error()));
        assertTrue(editor.consoleText().contains("editor console check"), "console shows the macro's Log line:\n" + editor.consoleText());
        // (b) a long macro is cancelled from the editor and ends CANCELLED
        String text = "for (i = 0; i < 1200; i++) { wait(50); }\nprint(\"never printed\");\n";
        int hist = engine.history().size();
        edt(() -> {
            editor.setText(text);
            editor.run();
        });
        waitFor("confirmation requested", editor::isAwaitingConfirmation, 30);
        edt(editor::confirmRun);
        waitFor("operation started", () -> editor.isRunning() && editor.consoleText().contains("confirmed, running"), 30);
        Thread.sleep(1500); // let the loop run for a while before cancelling
        assertTrue(editor.isRunning(), "the macro is still running (60 s loop)");
        edt(editor::cancel);
        waitFor("macro cancelled", () -> !editor.isRunning(), 60);
        ActionResult r = editor.lastResult();
        assertEquals(OperationState.CANCELLED, r.state(), "cancel ends the run as CANCELLED: " + (r.error() == null ? "" : r.error().message()));
        assertFalse(editor.consoleText().contains("never printed"), "the macro did not run to its end");
        waitFor("history records the cancelled run", () -> engine.history().size() > hist
                && engine.history().get(engine.history().size() - 1).outcome() == OperationState.CANCELLED, 10);
        System.out.println("[editor] Log tab content after the runs: " + ws.log().content().lines().count() + " line(s)");
        System.out.println("[editor] cancelled after console showed the print; console:\n" + editor.consoleText());
    }
}
