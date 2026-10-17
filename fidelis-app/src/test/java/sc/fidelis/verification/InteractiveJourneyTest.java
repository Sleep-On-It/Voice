package sc.fidelis.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.awt.TextField;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.ItemEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.Menus;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.ImageCanvas;
import ij.gui.Roi;
import ij.gui.Toolbar;
import ij.measure.ResultsTable;
import ij.plugin.frame.ThresholdAdjuster;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import sc.fidelis.engine.DefaultEngine;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.Enums.Provenance;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.testkit.FijiFixture;
import sc.fidelis.testkit.Fixtures;
import sc.fidelis.testkit.IjState;
import sc.fidelis.testkit.ProductEngine;

/**
 * The interactive path of J-12 (#5): the journey a person performs in the live demo, driven through Fiji's own
 * menu bar ({@code Menus.getMenuBar()} items fired into {@code ij.ImageJ.actionPerformed}, so every command runs
 * on IJ1's {@code Executer} thread with its real {@code GenericDialog}), the Threshold adjuster, and a native
 * rectangle drag on the hosted canvas. The engine only <em>observes</em> (provenance NATIVE). After every step the
 * engine's view (documents, Results, ROI list, globals) is compared with the pinned-Fiji GUI baseline exactly as
 * {@link EngineParityTest} compares the typed path, and at the end the exported macro is replayed in the reference
 * Fiji (E7) and must reproduce the final state.
 *
 * <p>Dialogs are answered by writing the documented parameters (docs/data/commands-core-parameters.csv) into the
 * dialog's fields, matched by {@code Macro.trimKey(label)} exactly as IJ1 derives option keys, then pressing OK.
 */
class InteractiveJourneyTest {
    static DefaultEngine engine;
    final List<String> notes = new ArrayList<>();

    @BeforeAll
    static void context() {
        FijiFixture.imageJ();
        engine = (DefaultEngine) ProductEngine.get();
    }

    @BeforeEach
    void clean() {
        IjState.reset();
    }

    @AfterEach
    void close() throws Exception {
        if (adjuster() != null) SwingUtilities.invokeAndWait(adjuster()::close);
        for (var d : new ArrayList<>(engine.documents())) {
            engine.submit(ActionRequest.session(Origin.PANEL, Operations.CLOSE, Map.of("discard", true), TargetScope.of(d.id())))
                    .get(30, TimeUnit.SECONDS);
        }
        IjState.reset();
    }

    // ---- driving Fiji's own menu bar -------------------------------------------------------------------

    /** The AWT menu item of the live Fiji menu bar with this label (the label is also the command name). */
    static MenuItem menuItem(String label) {
        MenuBar bar = Menus.getMenuBar();
        assertNotNull(bar, "Fiji's menu bar");
        for (int i = 0; i < bar.getMenuCount(); i++) {
            MenuItem found = find(bar.getMenu(i), label);
            if (found != null) return found;
        }
        throw new AssertionError("no Fiji menu item labelled " + label);
    }

    private static MenuItem find(Menu menu, String label) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            MenuItem it = menu.getItem(i);
            if (it instanceof Menu sub) {
                MenuItem found = find(sub, label);
                if (found != null) return found;
            } else if (label.equals(it.getLabel())) {
                return it;
            }
        }
        return null;
    }

    /** Fire the menu item exactly as a click would: into ij.ImageJ.actionPerformed, which starts an Executer thread. */
    static void click(String label) throws Exception {
        MenuItem item = menuItem(label);
        assertTrue(item.isEnabled(), label + " is enabled");
        ij.ImageJ ij = IJ.getInstance();
        assertNotNull(ij, "the ij.ImageJ instance (hidden main window)");
        ActionEvent ev = new ActionEvent(item, ActionEvent.ACTION_PERFORMED, label);
        SwingUtilities.invokeAndWait(() -> ij.actionPerformed(ev));
    }

    /** True while an IJ1 command thread for {@code label} ("Run$_label") is alive. */
    static boolean commandRunning(String label) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().equals("Run$_" + label)) return true;
        }
        return false;
    }

    static void waitFor(String what, BooleanSupplier c, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(30);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    static GenericDialog showingDialog(String titleStart) {
        for (Window w : Window.getWindows()) {
            if (w instanceof GenericDialog gd && gd.isShowing() && gd.getTitle().startsWith(titleStart)) return gd;
        }
        return null;
    }

    /**
     * Run a menu command that opens a GenericDialog titled {@code title}, fill it from {@code params} (keys as in
     * the command schema; booleans tick checkboxes, numbers and strings go into fields, choices are selected) and
     * press OK. Keys that have no field in the dialog are reported, not ignored silently.
     */
    void menuWithDialog(String label, String title, Map<String, Object> params) throws Exception {
        click(label);
        waitFor("dialog '" + title + "' of " + label, () -> showingDialog(title) != null, 30);
        GenericDialog gd = showingDialog(title);
        List<String> unmatched = new ArrayList<>(params.keySet());
        SwingUtilities.invokeAndWait(() -> {
            // every GenericDialog field keeps its label as the component name's neighbour; IJ1 derives the macro
            // key with Macro.trimKey(label). Match fields the same way.
            Map<String, Component> byKey = fieldsByKey(gd);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                Component c = byKey.get(e.getKey());
                if (c == null) continue;
                Object v = e.getValue();
                if (c instanceof Checkbox cb && v instanceof Boolean b) cb.setState(b);
                else if (c instanceof TextField tf) tf.setText(String.valueOf(v));
                else if (c instanceof Choice ch) ch.select(String.valueOf(v));
                else continue;
                unmatched.remove(e.getKey());
            }
            Button ok = gd.getButtons()[0];
            gd.actionPerformed(new ActionEvent(ok, ActionEvent.ACTION_PERFORMED, ok.getLabel()));
        });
        assertEquals(List.of(), unmatched, "parameters without a field in '" + title + "'");
        waitFor(label + " to finish", () -> !commandRunning(label) && showingDialog(title) == null, 120);
    }

    /** Fields of a GenericDialog keyed like IJ1 keys its macro options (first word of the label, lower-case). */
    @SuppressWarnings("unchecked")
    static Map<String, Component> fieldsByKey(GenericDialog gd) {
        Map<String, Component> out = new java.util.LinkedHashMap<>();
        List<String> labels = labelsInOrder(gd);
        // numeric and string fields are preceded by their Label in the dialog's layout; checkboxes carry their own
        for (Checkbox cb : (Vector<Checkbox>) orEmpty(gd.getCheckboxes())) out.put(Macro.trimKey(cb.getLabel()), cb);
        for (Choice ch : (Vector<Choice>) orEmpty(gd.getChoices())) out.put(keyOfPreceding(gd, ch, labels), ch);
        for (TextField tf : (Vector<TextField>) orEmpty(gd.getNumericFields())) out.put(keyOfPreceding(gd, tf, labels), tf);
        for (TextField tf : (Vector<TextField>) orEmpty(gd.getStringFields())) out.put(keyOfPreceding(gd, tf, labels), tf);
        return out;
    }

    private static Vector<?> orEmpty(Vector<?> v) {
        return v == null ? new Vector<>() : v;
    }

    /** All components of the dialog in layout order (GenericDialog adds the Label right before each field). */
    private static List<String> labelsInOrder(GenericDialog gd) {
        List<String> out = new ArrayList<>();
        walk(gd, c -> out.add(c instanceof java.awt.Label l ? "L:" + l.getText() : "C:" + System.identityHashCode(c)));
        return out;
    }

    private static String keyOfPreceding(GenericDialog gd, Component field, List<String> order) {
        int at = order.indexOf("C:" + System.identityHashCode(field));
        for (int i = at - 1; i >= 0; i--) {
            if (order.get(i).startsWith("L:")) return Macro.trimKey(order.get(i).substring(2));
        }
        return "?";
    }

    private static void walk(Container c, java.util.function.Consumer<Component> f) {
        for (Component k : c.getComponents()) {
            f.accept(k);
            if (k instanceof Container kc) walk(kc, f);
        }
    }

    /** Run a menu command that shows no dialog and wait for its thread to end. */
    void menu(String label) throws Exception {
        click(label);
        waitFor(label + " to finish", () -> !commandRunning(label), 120);
        for (Window w : Window.getWindows()) {
            if (w instanceof Dialog d && d.isShowing()) throw new AssertionError(label + " left a dialog showing: " + d.getTitle());
        }
    }

    /** Wait until the engine's observers have settled (follow-up timers are 600 ms and 250 ms). */
    static void settle() throws InterruptedException {
        Thread.sleep(900);
        try {
            SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- native canvas events -------------------------------------------------------------------------

    private static void fire(ImageCanvas c, int id, int x, int y) throws Exception {
        int mods = id == MouseEvent.MOUSE_RELEASED || id == MouseEvent.MOUSE_MOVED ? 0 : InputEvent.BUTTON1_DOWN_MASK;
        MouseEvent ev = new MouseEvent(c, id, System.currentTimeMillis(), mods, x, y, x, y, 1, false,
                id == MouseEvent.MOUSE_MOVED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1);
        SwingUtilities.invokeAndWait(() -> c.dispatchEvent(ev));
    }

    /** A rectangle-tool drag on the hosted canvas, in screen (canvas) coordinates at magnification 1. */
    static void dragRectangle(ImageCanvas c, int x0, int y0, int x1, int y1) throws Exception {
        Toolbar.getInstance().setTool(Toolbar.RECTANGLE);
        fire(c, MouseEvent.MOUSE_MOVED, x0, y0);
        fire(c, MouseEvent.MOUSE_PRESSED, x0, y0);
        for (int i = 1; i <= 5; i++) fire(c, MouseEvent.MOUSE_DRAGGED, x0 + (x1 - x0) * i / 5, y0 + (y1 - y0) * i / 5);
        fire(c, MouseEvent.MOUSE_RELEASED, x1, y1);
    }

    // ---- the journey ----------------------------------------------------------------------------------

    private void step(String tag, Path baseline, DocumentId doc, List<DocumentId> before, List<String> failures) throws Exception {
        settle();
        J12Session.compare(tag, engine, baseline, doc, before, failures, true);
        System.out.println("[interactive] " + tag + ": " + (failures.isEmpty() ? "matches the GUI baseline" : failures.size() + " difference(s) so far"));
    }

    private HistoryRecord lastNative(String label) {
        List<HistoryRecord> h = engine.history();
        for (int i = h.size() - 1; i >= 0; i--) {
            if (h.get(i).provenance() == Provenance.NATIVE && label.equals(h.get(i).label())) return h.get(i);
        }
        throw new AssertionError("no NATIVE record labelled " + label);
    }

    @Test
    void j12ThroughFijiMenusAndTheCanvasMatchesTheBaselineAndReplays(@TempDir Path tmp) throws Exception {
        String fixtureId = "FX-003";
        J12Reference.Fixture fixture = J12Reference.fixture(fixtureId);
        Path baseline = Fixtures.requireBaseline(fixtureId, "gui");
        List<String> failures = new ArrayList<>();
        Prefs.blackBackground = true; // explicit global, as in the reference macro
        List<DocumentId> before = engine.documents().stream().map(d -> d.id()).toList();
        int since = engine.history().size();
        Path tiff = tmp.resolve("blobs-mask.tif");

        // File>Open... shows a file chooser that cannot be answered through parameters: the document is opened
        // through the engine (the workspace's Open does the same), everything after is native.
        ActionResult opened = engine.open(fixture.openFile()).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, opened.state(), String.valueOf(opened.error()));
        DocumentId doc = opened.digest().added().get(0);
        engine.focus(doc);
        ImagePlus imp = engine.imagePlus(doc);
        assertEquals(imp, ij.WindowManager.getCurrentImage(), "the hosted document is IJ1's current image");
        step("s01_open", baseline, doc, before, failures);

        menuWithDialog("Subtract Background...", "Subtract Background", J12Session.subtractBackground());
        assertEquals(Provenance.NATIVE, lastNative("Subtract Background...").provenance());
        step("s02_subtract_background", baseline, doc, before, failures);

        menuWithDialog("Gaussian Blur...", "Gaussian Blur", Map.of("sigma", 2, "scaled", false));
        step("s03_gaussian_blur", baseline, doc, before, failures);

        // Image>Adjust>Threshold...: the adjuster frame, method Otsu, dark background, then closed
        String thresholdPath = thresholdWithTheAdjuster(imp);
        notes.add("threshold set through " + thresholdPath);
        step("s04_threshold", baseline, doc, before, failures);
        HistoryRecord thr = lastNative("Threshold");
        assertEquals(Operations.SET_THRESHOLD, thr.commandId());
        assertTrue(thr.macroLines().get(0).startsWith("setThreshold("), thr.macroLines().toString());

        menu("Convert to Mask");
        step("s05_convert_to_mask", baseline, doc, before, failures);
        menu("Watershed");
        step("s06_watershed", baseline, doc, before, failures);

        menuWithDialog("Set Measurements...", "Set Measurements", J12Session.setMeasurements());
        step("s07_set_measurements", baseline, doc, before, failures);

        // a native selection drawn with the rectangle tool, measured, then removed: the engine must observe the
        // ROI with exact geometry, attribute the row, and the numbers must equal IJ1's own statistics
        ImageCanvas canvas = imp.getCanvas();
        assertNotNull(canvas, "hosted canvas");
        assertEquals(1.0, canvas.getMagnification(), "drag coordinates assume magnification 1");
        dragRectangle(canvas, 20, 30, 80, 90);
        settle();
        Roi roi = imp.getRoi();
        assertNotNull(roi, "the drag created a selection");
        assertEquals(Roi.RECTANGLE, roi.getType());
        HistoryRecord sel = lastNative("ROI");
        assertEquals(Operations.SET_ROI, sel.commandId());
        assertEquals(List.of("makeRectangle(20, 30, 60, 60);"), sel.macroLines(), "exact native selection");
        int rowsBefore = ResultsTable.getResultsTable().size();
        menu("Measure");
        settle();
        ImageStatistics st = imp.getStatistics(ij.measure.Measurements.AREA | ij.measure.Measurements.MEAN);
        assertEquals(rowsBefore + 1, engine.results().size(), "the selection measure row reached the engine");
        var row = engine.results().get(engine.results().size() - 1);
        assertEquals(3600.0, row.values().get("Area"));
        assertEquals(st.mean, row.values().get("Mean"), "Mean of the drawn rectangle equals IJ1's own statistics");
        assertEquals(lastNative("Measure").actionId(), row.actionId());
        menu("Select None");
        settle();
        assertTrue(imp.getRoi() == null, "Select None removed the selection");
        assertEquals(Operations.CLEAR_ROI, lastNative("ROI").commandId());
        IJ.run("Clear Results"); // the reference journey has no selection row: clear natively before the J-12 Measure
        settle();
        assertEquals(0, engine.results().size(), "mirror follows the native clear");

        menu("Measure");
        step("s08_measure", baseline, doc, before, failures);

        menuWithDialog("Analyze Particles...", "Analyze Particles", J12Session.analyzeParticles());
        settle();
        step("s09_analyze_particles", baseline, doc, before, failures);
        assertEquals(71, engine.rois(doc).size(), "native Analyze Particles 'add' fills the document's ROI list");

        // File>Save As>Tiff... opens a native save dialog: saved through the engine, as the workspace does
        ActionResult saved = engine.submit(ActionRequest.session(Origin.PANEL, Operations.SAVE_AS,
                Map.of("path", tiff.toString(), "format", "tiff"), TargetScope.of(doc))).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, saved.state(), String.valueOf(saved.error()));
        step("s10_save_tiff", baseline, doc, before, failures);
        assertTrue(Files.isRegularFile(tiff));

        for (HistoryRecord r : engine.history().subList(since, engine.history().size())) {
            if (r.provenance() == Provenance.LEGACY_DIALOG) notes.add("LEGACY_DIALOG step: " + r.label());
        }
        System.out.println("[interactive] notes: " + notes);
        assertEquals(List.of(), failures, () -> "interactive J-12 differs from the GUI baseline:\n" + String.join("\n", failures));

        // E7: the macro exported from the observed session replays to the same final state in the reference Fiji
        String exported = engine.exportMacroSince(since);
        Snapshot live = new Snapshot(J12Session.snapshot(engine, doc, List.of(), true).images(), J12Session.table(engine),
                J12Session.snapshot(engine, doc, List.of(), true).rois(), Snapshot.capture().globals());
        MacroReplay.Replayed replay = MacroReplay.replay(exported, fixture.openFile(), tiff, tmp);
        List<String> replayFailures = new ArrayList<>(BaselineCompare.diff(replay.finalState(), live, false, false));
        replayFailures.addAll(BaselineCompare.diffGlobals(replay.finalState(), live.globals(), List.of("blackBackground", "measurements", "precision")));
        assertEquals(List.of(), replayFailures, () -> "replay of the interactive session's macro differs:\n"
                + String.join("\n", replayFailures) + "\n--- exported macro ---\n" + exported);
        System.out.println("[interactive] replay of the exported macro equals the live interactive session");
    }

    /**
     * Image>Adjust>Threshold... as a person uses it: the adjuster frame opens on the current image, Otsu is chosen
     * in the method list and "Dark background" ticked (ItemEvents into the adjuster, which recomputes on its own
     * thread), then the frame is closed. Returns a description of what was driven.
     */
    private String thresholdWithTheAdjuster(ImagePlus imp) throws Exception {
        click("Threshold...");
        waitFor("Threshold adjuster", () -> adjuster() != null && adjuster().isShowing(), 30);
        ThresholdAdjuster ta = adjuster();
        Choice[] method = new Choice[1];
        Checkbox[] dark = new Checkbox[1];
        SwingUtilities.invokeAndWait(() -> walk(ta, c -> {
            if (c instanceof Choice ch && indexOf(ch, "Otsu") >= 0 && method[0] == null) method[0] = ch;
            if (c instanceof Checkbox cb && "Dark background".equals(cb.getLabel())) dark[0] = cb;
        }));
        assertNotNull(method[0], "method list of the adjuster");
        assertNotNull(dark[0], "Dark background checkbox");
        SwingUtilities.invokeAndWait(() -> {
            if (!dark[0].getState()) {
                dark[0].setState(true);
                ta.itemStateChanged(new ItemEvent(dark[0], ItemEvent.ITEM_STATE_CHANGED, dark[0].getLabel(), ItemEvent.SELECTED));
            }
            method[0].select("Otsu");
            ta.itemStateChanged(new ItemEvent(method[0], ItemEvent.ITEM_STATE_CHANGED, "Otsu", ItemEvent.SELECTED));
        });
        // the adjuster's thread applies the method; wait for IJ1's own answer for "Otsu dark" on this image
        ImagePlus probe = imp.duplicate();
        IJ.setAutoThreshold(probe, "Otsu dark");
        double lo = probe.getProcessor().getMinThreshold();
        double hi = probe.getProcessor().getMaxThreshold();
        probe.close();
        waitFor("Otsu dark threshold " + lo + "-" + hi + " on the image", () -> imp.getProcessor().getMinThreshold() == lo
                && imp.getProcessor().getMaxThreshold() == hi, 30);
        SwingUtilities.invokeAndWait(ta::close);
        waitFor("adjuster closed", () -> adjuster() == null, 10);
        assertTrue(imp.getProcessor().getMinThreshold() != ImageProcessor.NO_THRESHOLD, "closing the adjuster keeps the threshold");
        return "Image>Adjust>Threshold... adjuster (Otsu, dark background): " + lo + "-" + hi;
    }

    /** The Threshold adjuster (a PlugInDialog in 1.54p), registered with IJ1's WindowManager under its title, or null. */
    static ThresholdAdjuster adjuster() {
        Window w = ij.WindowManager.getWindow("Threshold");
        return w instanceof ThresholdAdjuster ta ? ta : null;
    }

    private static int indexOf(Choice ch, String item) {
        for (int i = 0; i < ch.getItemCount(); i++) if (item.equals(ch.getItem(i))) return i;
        return -1;
    }
}
