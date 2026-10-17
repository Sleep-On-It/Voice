package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.Roi;
import ij.gui.RoiListener;
import ij.process.ImageProcessor;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.Enums.ParameterType;
import sc.fidelis.engine.api.Enums.Provenance;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;

/** E3 to E7: measurements, particles, ROIs, undo, code, history and macro export on real Fiji. */
class EngineScienceTest {
    DefaultEngine engine;
    DocumentId doc;
    /** History size before this test's first step: the macro this test produced is {@code exportMacroSince(historyStart)}. */
    int historyStart;

    @BeforeEach
    void open() throws Exception {
        engine = EngineFixture.engine();
        historyStart = engine.history().size();
        ActionResult r = engine.open(EngineFixture.repoFile("fixtures/two-squares.png")).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        // the document this test opened (not whichever document happens to be last), focused explicitly
        assertEquals(1, r.digest().added().size(), String.valueOf(r.digest()));
        doc = r.digest().added().get(0);
        engine.focus(doc);
        assertEquals(doc, engine.focused().orElseThrow());
    }

    @AfterEach
    void close() throws Exception {
        for (var d : engine.documents()) {
            engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.CLOSE, Map.of("discard", true),
                    TargetScope.of(d.id()))).get(30, TimeUnit.SECONDS);
        }
    }

    ActionResult cmd(String id, Map<String, Object> params) throws Exception {
        return cmd(doc, id, params);
    }

    ActionResult cmd(DocumentId d, String id, Map<String, Object> params) throws Exception {
        return engine.submit(ActionRequest.command(Origin.PANEL, id, params, TargetScope.of(d))).get(120, TimeUnit.SECONDS);
    }

    ActionResult direct(DocumentId d, String op, Map<String, Object> params) throws Exception {
        return engine.submit(ActionRequest.direct(Origin.PANEL, op, params, TargetScope.of(d))).get(60, TimeUnit.SECONDS);
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        return p;
    }

    String hash(DocumentId d) {
        return Pixels.hash(engine.imagePlus(d), false);
    }

    /** Complete Set Measurements... parameters: every boolean explicit, the named ones true. */
    Map<String, Object> setMeasurements(String... on) {
        CommandSchema s = engine.schema("ij1:Analyze>Set Measurements...").orElseThrow();
        List<String> wanted = List.of(on);
        Map<String, Object> p = new LinkedHashMap<>();
        for (CommandSchema.ParameterSchema ps : s.parameters()) {
            if (ps.type() == ParameterType.BOOLEAN) p.put(ps.key(), wanted.contains(ps.key()));
        }
        p.put("redirect", "None");
        p.put("decimal", 3);
        return p;
    }

    @Test
    void measureAfterSetMeasurementsGivesExactAreaAndMean() throws Exception {
        ActionResult sm = cmd("ij1:Analyze>Set Measurements...", setMeasurements("area", "mean"));
        assertEquals(OperationState.COMPLETED, sm.state(), String.valueOf(sm.error()));
        ActionResult r = cmd("ij1:Analyze>Measure", m());
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertEquals(1, r.digest().resultsRowsAdded());
        List<MeasurementRow> rows = engine.results();
        MeasurementRow row = rows.get(rows.size() - 1);
        ImagePlus imp = engine.imagePlus(doc);
        ImageProcessor ip = imp.getProcessor();
        double sum = 0;
        for (int y = 0; y < imp.getHeight(); y++) for (int x = 0; x < imp.getWidth(); x++) sum += ip.get(x, y);
        double mean = sum / (imp.getWidth() * imp.getHeight());
        assertEquals((double) imp.getWidth() * imp.getHeight(), row.values().get("Area"));
        assertEquals(mean, row.values().get("Mean"));
        assertEquals(doc, row.document());
        assertEquals(r.record().actionId(), row.actionId());
        assertEquals("area mean", engine.globals().get("measurements"));
    }

    @Test
    void twentySequentialMeasuresAccumulateWithTheResultsWindowHidden() throws Exception {
        cmd("ij1:Analyze>Set Measurements...", setMeasurements("area", "mean", "min"));
        int before = engine.results().size();
        for (int i = 0; i < 20; i++) {
            ActionResult r = cmd("ij1:Analyze>Measure", m());
            assertEquals(OperationState.COMPLETED, r.state(), "measure " + i + ": " + r.error());
        }
        assertEquals(before + 20, engine.results().size());
        assertEquals(ij.measure.ResultsTable.getResultsTable().size(), Results.tableSize());
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            if (w instanceof java.awt.Frame f && f.getTitle().equals("Results")) assertFalse(f.isShowing(), "Results window must stay hidden");
        }
    }

    /** Creates a 100x100 image with three 20x20 white squares and makes it the test document (no ij types in the signature). */
    void particleImage() throws Exception {
        ActionResult n = engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.NEW_IMAGE,
                m("title", "particles", "type", "8-bit", "width", 100, "height", 100, "slices", 1, "fill", "Black"), null))
                .get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, n.state(), String.valueOf(n.error()));
        DocumentId id = n.digest().added().get(0);
        ImagePlus imp = engine.imagePlus(id);
        ImageProcessor ip = imp.getProcessor();
        ip.setColor(255);
        ip.fillRect(10, 10, 20, 20);
        ip.fillRect(50, 15, 20, 20);
        ip.fillRect(30, 60, 20, 20);
        imp.updateAndDraw();
        doc = id;
    }

    /** The part of the exported macro written for the document this test opened last (history is session-wide). */
    String thisTestMacro() {
        String macro = engine.exportMacro();
        return macro.substring(macro.lastIndexOf("open(\""));
    }

    @Test
    void analyzeParticlesFillsTheRoiListAndResults() throws Exception {
        particleImage();
        assertEquals(OperationState.COMPLETED, direct(doc, ActionRequest.Operations.SET_THRESHOLD, m("lower", 128, "upper", 255)).state());
        cmd("ij1:Analyze>Set Measurements...", setMeasurements("area", "centroid"));
        int rowsBefore = engine.results().size();
        ActionResult r = cmd("ij1:Analyze>Analyze Particles...", m("size", "0-Infinity", "circularity", "0.00-1.00", "show", "Nothing",
                "display", true, "exclude", false, "clear", false, "include", false, "summarize", false, "overlay", false, "add", true,
                "composite", false, "record", false, "in_situ", false));
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertEquals(3, engine.rois(doc).size());
        assertEquals(3, engine.results().size() - rowsBefore);
        for (MeasurementRow row : engine.results().subList(rowsBefore, engine.results().size())) assertEquals(400.0, row.values().get("Area"));
        assertEquals("TRACED", engine.rois(doc).get(0).type());
        assertTrue(r.record().macroLines().contains("roiManager(\"reset\");"), r.record().macroLines().toString());
        // the singleton stays hidden but registered
        assertNotNull(ij.WindowManager.getFrame("ROI Manager"));
        assertFalse(ij.WindowManager.getFrame("ROI Manager").isShowing());
        // measure ROI 1 through the list: it becomes the active selection and the macro carries it inline
        ActionResult mr = engine.submit(ActionRequest.command(Origin.PANEL, "ij1:Analyze>Measure", m(), TargetScope.of(doc).withRoi(1)))
                .get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, mr.state(), String.valueOf(mr.error()));
        assertTrue(mr.record().macroLines().get(0).startsWith("makeSelection(\"traced\""), mr.record().macroLines().toString());
        assertEquals(400.0, engine.results().get(engine.results().size() - 1).values().get("Area"));
        assertEquals(ij.measure.ResultsTable.getResultsTable().size(), Results.tableSize());
    }

    @Test
    void analyzeParticlesNeedsAThresholdOrBinaryImage() throws Exception {
        ActionResult r = cmd("ij1:Analyze>Analyze Particles...", m("size", "0-Infinity", "circularity", "0.00-1.00", "show", "Nothing",
                "display", true, "exclude", false, "clear", false, "include", false, "summarize", false, "overlay", false, "add", false,
                "composite", false, "record", false, "in_situ", false));
        assertEquals(ErrorCode.PRECONDITION_FAILED, r.error().code());
    }

    @Test
    void macroExportContainsSelectImageAndTheExactRunLine() throws Exception {
        cmd("ij1:Process>Filters>Gaussian Blur...", m("sigma", 2, "scaled", false, "stack", false));
        // the macro of this test's steps: the session history also holds every earlier test's steps
        String macro = engine.exportMacroSince(historyStart);
        assertTrue(macro.contains("selectImage("), macro);
        assertTrue(macro.contains("run(\"Gaussian Blur...\", \"sigma=2\");"), macro);
        assertTrue(macro.contains("open(\""), macro);
        assertTrue(macro.contains("setOption(\"BlackBackground\""), macro);
        assertTrue(macro.indexOf("open(\"") < macro.indexOf("selectImage("), macro);
        assertTrue(macro.indexOf("selectImage(") < macro.indexOf("run(\"Gaussian Blur..."), macro);
    }

    @Test
    void undoRestoresThePixelsAndRedoReappliesThem() throws Exception {
        String h0 = hash(doc);
        ActionResult blur = cmd("ij1:Process>Filters>Gaussian Blur...", m("sigma", 3, "scaled", false, "stack", false));
        assertEquals(OperationState.COMPLETED, blur.state(), String.valueOf(blur.error()));
        String hBlur = hash(doc);
        assertNotEquals(h0, hBlur);
        assertTrue(engine.undoBudget()[0] > 0);
        ActionResult u = direct(doc, ActionRequest.Operations.UNDO, m());
        assertEquals(OperationState.COMPLETED, u.state(), String.valueOf(u.error()));
        assertEquals(h0, hash(doc));
        assertFalse(thisTestMacro().contains("run(\"Gaussian Blur"), thisTestMacro());
        assertTrue(thisTestMacro().contains("// step") && thisTestMacro().contains("undone"));
        ActionResult redo = direct(doc, ActionRequest.Operations.REDO, m());
        assertEquals(OperationState.COMPLETED, redo.state(), String.valueOf(redo.error()));
        assertEquals(hBlur, hash(doc));
        String macro = thisTestMacro();
        assertEquals(1, macro.split("run[(]\"Gaussian Blur", -1).length - 1, macro);
        assertEquals(ErrorCode.PRECONDITION_FAILED, direct(doc, ActionRequest.Operations.REDO, m()).error().code());
    }

    @Test
    void codeNeedsConfirmationThenRunsAndIsRecorded() throws Exception {
        String h0 = hash(doc);
        ActionResult first = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", "run(\"Invert\");", TargetScope.of(doc), null))
                .get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.AWAITING_CONFIRMATION, first.state());
        assertNotNull(first.confirmationToken());
        assertEquals(h0, hash(doc));
        ActionResult run = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", "run(\"Invert\");", TargetScope.of(doc), first.confirmationToken()))
                .get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, run.state(), String.valueOf(run.error()));
        assertNotEquals(h0, hash(doc));
        assertEquals(Provenance.TYPED, run.record().provenance());
        assertEquals(List.of("run(\"Invert\");"), run.record().macroLines());
        // the token is bound to the text
        ActionResult other = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", "run(\"Invert\"); run(\"Invert\");", TargetScope.of(doc),
                first.confirmationToken())).get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.AWAITING_CONFIRMATION, other.state());
    }

    @Test
    void codeCapabilitiesAreListedAndMacroErrorsAreCaptured() throws Exception {
        String text = "x = exec(\"cmd\"); File.delete(\"a\");";
        ActionResult first = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", text, TargetScope.of(doc), null)).get(30, TimeUnit.SECONDS);
        assertEquals(ErrorCode.CAPABILITY_CONFIRMATION_REQUIRED, first.error().code());
        String caps = first.error().context().get("capabilities");
        assertTrue(caps.contains("exec") && caps.contains("File.delete"), caps);

        String bad = "undefinedFunctionXyz(1);";
        ActionResult t = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", bad, TargetScope.of(doc), null)).get(30, TimeUnit.SECONDS);
        ActionResult r = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", bad, TargetScope.of(doc), t.confirmationToken()))
                .get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.FAILED, r.state(), String.valueOf(r.error()));
        assertEquals("MACRO_ERROR", r.error().subcode());
        assertEquals(Provenance.UNRECORDABLE, r.record().provenance());
        for (java.awt.Window w : java.awt.Window.getWindows()) {
            assertFalse(w instanceof java.awt.Dialog d && d.isShowing() && "Macro Error".equals(d.getTitle()), "Macro Error dialog must be disposed");
        }
    }

    @Test
    void roiOperationsKeepFloatGeometryAndEmitExactMacroLines() throws Exception {
        RoiGeometry rect = new RoiGeometry("RECTANGLE", List.of(12.5f, 32.5f), List.of(8.25f, 18.25f), new double[] {12.5, 8.25, 20, 10},
                null, null, null);
        ActionResult r = direct(doc, ActionRequest.Operations.SET_ROI, m("roi", rect));
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertEquals("makeRectangle(12.5, 8.25, 20, 10);", r.record().macroLines().get(0));
        RoiGeometry back = engine.activeRoi(doc).orElseThrow();
        assertEquals("RECTANGLE", back.type());
        assertEquals(12.5, back.bounds()[0]);
        assertEquals(8.25, back.bounds()[1]);
        assertEquals(20.0, back.bounds()[2]);

        RoiGeometry poly = new RoiGeometry("POLYGON", List.of(5.5f, 30.25f, 20f), List.of(5f, 8.5f, 40f), null, null, null, null);
        ActionResult p = direct(doc, ActionRequest.Operations.SET_ROI, m("roi", poly));
        assertEquals(OperationState.COMPLETED, p.state(), String.valueOf(p.error()));
        assertTrue(p.record().macroLines().get(0).startsWith("makeSelection(\"polygon\""), p.record().macroLines().toString());
        RoiGeometry pb = engine.activeRoi(doc).orElseThrow();
        assertEquals(List.of(5.5f, 30.25f, 20f), pb.xs());
        assertEquals(List.of(5f, 8.5f, 40f), pb.ys());

        assertEquals(OperationState.COMPLETED, direct(doc, ActionRequest.Operations.ROI_LIST_ADD, m()).state());
        assertEquals(1, engine.rois(doc).size());
        assertEquals(OperationState.COMPLETED, direct(doc, ActionRequest.Operations.CLEAR_ROI, m()).state());
        assertTrue(engine.activeRoi(doc).isEmpty());
        assertEquals(OperationState.COMPLETED, direct(doc, ActionRequest.Operations.ROI_LIST_SELECT, m("index", 0)).state());
        assertEquals("POLYGON", engine.activeRoi(doc).orElseThrow().type());
        assertEquals(ErrorCode.VALIDATION_FAILED, direct(doc, ActionRequest.Operations.ROI_LIST_SELECT, m("index", 5)).error().code());
    }

    @Test
    void thresholdDisplayAndPositionOperations() throws Exception {
        ImagePlus imp = engine.imagePlus(doc);
        assertEquals(OperationState.COMPLETED, direct(doc, ActionRequest.Operations.SET_AUTO_THRESHOLD, m("method", "Otsu", "dark", true)).state());
        assertNotEquals(ImageProcessor.NO_THRESHOLD, imp.getProcessor().getMinThreshold());
        assertEquals(OperationState.COMPLETED, direct(doc, ActionRequest.Operations.RESET_THRESHOLD, m()).state());
        assertEquals(ImageProcessor.NO_THRESHOLD, imp.getProcessor().getMinThreshold());
        ActionResult t = direct(doc, ActionRequest.Operations.SET_THRESHOLD, m("lower", 10, "upper", 200));
        assertEquals(10.0, imp.getProcessor().getMinThreshold());
        assertEquals("setThreshold(10, 200);", t.record().macroLines().get(0));
        assertEquals(OperationState.COMPLETED, direct(doc, ActionRequest.Operations.SET_DISPLAY_RANGE, m("min", 20, "max", 100)).state());
        assertEquals(20.0, imp.getDisplayRangeMin());
        assertEquals(100.0, imp.getDisplayRangeMax());
        assertEquals(ErrorCode.VALIDATION_FAILED, direct(doc, ActionRequest.Operations.SET_THRESHOLD, m("lower", 9, "upper", 1)).error().code());
    }

    @Test
    void typeConversionAndDuplicateRegisterNewDocuments() throws Exception {
        ActionResult dup = cmd("ij1:Image>Duplicate...", m("title", "copy", "ignore", false, "duplicate", false));
        assertEquals(OperationState.COMPLETED, dup.state(), String.valueOf(dup.error()));
        assertEquals(1, dup.digest().added().size());
        DocumentId copy = dup.digest().added().get(0);
        assertEquals("copy", engine.document(copy).orElseThrow().title());
        assertFalse(engine.hostWindow(copy).isVisible());
        ActionResult rgb = cmd(copy, "ij1:Image>Type>RGB Color", m());
        assertEquals(OperationState.COMPLETED, rgb.state(), String.valueOf(rgb.error()));
        assertEquals("COLOR_RGB", engine.document(copy).orElseThrow().type());
        String macro = engine.exportMacro();
        assertTrue(macro.contains("run(\"Duplicate...\", \"title=[copy]\");"), macro);
        assertTrue(macro.contains("id" + copy.value().replace("doc-", "") + " = getImageID();"), macro);
    }

    @Test
    void saveAsWritesAndRefusesToOverwriteTheInput() throws Exception {
        java.nio.file.Path out = java.nio.file.Files.createTempFile("fidelis-save", ".tif");
        java.nio.file.Files.delete(out);
        ActionResult r = engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.SAVE_AS,
                m("path", out.toString(), "format", "tiff"), TargetScope.of(doc))).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertTrue(java.nio.file.Files.size(out) > 0);
        assertNotNull(r.record().params().get("outputSha256"));
        java.nio.file.Files.delete(out);
        // the document now has the saved file as its title; the source file is still the fixture
        ActionResult refuse = engine.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.SAVE_AS,
                m("path", EngineFixture.repoFile("fixtures/two-squares.png").toString(), "format", "png"), TargetScope.of(doc)))
                .get(60, TimeUnit.SECONDS);
        assertEquals(ErrorCode.CONFIRMATION_REQUIRED, refuse.error().code());
        assertEquals("OVERWRITE_INPUT", refuse.error().subcode());
    }

    @Test
    void nativeCommandsAndSelectionsAreObservedNotEngineActions() throws Exception {
        int before = engine.history().size();
        ImagePlus imp = engine.imagePlus(doc);
        IJ.run(imp, "Invert", ""); // runs on the test thread, like a menu command
        Roi roi = new Roi(5, 6, 10, 12);
        imp.setRoi(roi);
        roi.notifyListeners(RoiListener.COMPLETED);
        Thread.sleep(900);
        List<HistoryRecord> h = engine.history().subList(before, engine.history().size());
        HistoryRecord cmd = h.stream().filter(x -> "Invert".equals(x.label()) && x.provenance() == Provenance.NATIVE).findFirst().orElseThrow();
        assertEquals(Origin.OBSERVED, cmd.origin());
        assertEquals(doc, cmd.target().document());
        HistoryRecord sel = h.stream().filter(x -> x.provenance() == Provenance.NATIVE && "SET_ROI".equals(x.commandId())).findFirst().orElseThrow();
        assertEquals(List.of("makeRectangle(5, 6, 10, 12);"), sel.macroLines());
        assertTrue(sel.macroExact());
        assertEquals(1, h.stream().filter(x -> "Invert".equals(x.label())).count(), "no double recording");
        List<String> seen = new ArrayList<>();
        engine.history().forEach(x -> seen.add(x.label()));
        assertTrue(seen.contains("Invert"));
    }

    @Test
    void reservedMetadataKeysAreAcceptedAndRecorded() throws Exception {
        ActionRequest base = ActionRequest.command(Origin.NL_SPOKEN, "ij1:Process>Filters>Gaussian Blur...",
                m("sigma", 2, "transcriptRef", "tr-7", "parentActionId", "act-0"), TargetScope.of(doc));
        ActionResult r = engine.submit(base).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertEquals("tr-7", r.record().transcriptRef());
        assertEquals("act-0", r.record().params().get("parentActionId"));
        assertEquals("run(\"Gaussian Blur...\", \"sigma=2\");", r.record().macroLines().get(0));
    }

    @Test
    void blackOnSingleImageConvertToMaskIsAnExplicitGlobalChange() throws Exception {
        particleImage();
        boolean old = Prefs.blackBackground;
        Prefs.blackBackground = false;
        try {
            direct(doc, ActionRequest.Operations.SET_THRESHOLD, m("lower", 128, "upper", 255));
            ActionResult r = cmd("ij1:Process>Binary>Convert to Mask", m("black", true));
            assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
            assertTrue(Prefs.blackBackground);
            assertEquals("true=false".equals(r.record().globalsDelta().get("blackBackground")) ? "" : "false=true",
                    r.record().globalsDelta().get("blackBackground"));
            assertEquals(List.of("setOption(\"BlackBackground\", true);", "run(\"Convert to Mask\");"), r.record().macroLines());
            assertTrue(thisTestMacro().contains("setOption(\"BlackBackground\", true);\nrun(\"Convert to Mask\");"), thisTestMacro());
        } finally {
            Prefs.blackBackground = old;
        }
    }
}
