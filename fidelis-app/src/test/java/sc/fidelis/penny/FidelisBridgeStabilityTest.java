package sc.fidelis.penny;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sc.fidelis.penny.BridgeHttp.args;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import sc.fidelis.engine.RoiSignature;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.testkit.Fixtures;
import sc.fidelis.testkit.IsolationState;
import sc.fidelis.testkit.PixelHash;
import sc.fidelis.testkit.ProductEngine;

/**
 * Regression tests for the final-phase stability fixes (18 Oct 2026), through the bridge's HTTP interface on the product
 * engine: a running plan stops on cancel_plan (BUG-01), Results rows a segmentation cleared come back on undo (BUG-02),
 * an undone segmentation is redone although its mask is closed (BUG-03), and a requested channel is the one measured
 * and reported for every ROI (BUG-04). Every number is read back from the engine's own mirrors.
 */
class FidelisBridgeStabilityTest {
    private static final String TASK = "task-stability";
    private Engine engine;
    private FidelisBridge bridge;
    private BridgeHttp http;
    private RecordingUi ui;

    @BeforeEach
    void setUp() {
        engine = ProductEngine.get();
        IsolationState.reset("FidelisBridgeStabilityTest");
        ui = new RecordingUi();
        PennyUi.register(ui);
        bridge = FidelisBridge.start(engine, BridgeHttp.TOKEN, 0, PennyOps.ConfirmMode.AUTO);
        http = new BridgeHttp(bridge.port(), BridgeHttp.TOKEN);
        http.taskId = TASK;
        http.spoken = true;
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) bridge.stop();
        PennyUi.register(null);
        IsolationState.reset("FidelisBridgeStabilityTest");
    }

    private DocumentId openBlobs() throws Exception {
        Path blobs = Fixtures.require("FX-003", "blobs.gif");
        return new DocumentId(http.change("open_path", args("path", blobs.toString())).result().getAsJsonObject("document").get("id").getAsString());
    }

    private static String stepAction(JsonObject plan, String label) {
        for (JsonElement e : plan.getAsJsonArray("steps")) {
            JsonObject s = e.getAsJsonObject();
            if (label.equals(s.get("label").getAsString())) return s.get("actionId").getAsString();
        }
        return null;
    }

    private static void assertSameRows(List<MeasurementRow> want, List<MeasurementRow> got, String what) {
        assertEquals(want.size(), got.size(), what + ": row count");
        for (int i = 0; i < want.size(); i++) {
            MeasurementRow a = want.get(i), b = got.get(i);
            assertEquals(a.label(), b.label(), what + ": label of row " + i);
            assertEquals(a.actionId(), b.actionId(), what + ": attribution of row " + i);
            assertEquals(a.values().keySet(), b.values().keySet(), what + ": columns of row " + i);
            for (String k : a.values().keySet())
                assertEquals(Double.doubleToLongBits(a.values().get(k)), Double.doubleToLongBits(b.values().get(k)), what + ": " + k + " of row " + i + " bit for bit");
        }
    }

    /** BUG-02: Measure, then segment_cells (Analyze Particles clears the table), then undo: the measurement is back, bit for bit. */
    @Test
    void measurementRowsClearedByASegmentationComeBackOnUndo() throws Exception {
        DocumentId doc = openBlobs();
        JsonObject measured = http.change("measure", args("scope", "all", "measurements", List.of("area", "mean"))).result();
        assertEquals(1, measured.get("rows").getAsInt(), measured.toString());
        List<MeasurementRow> before = engine.results();
        MeasurementRow row = before.get(before.size() - 1);
        assertEquals(256.0 * 254.0, row.values().get("Area"), 1e-9);

        JsonObject seg = http.change("segment_cells", args()).result();
        int count = seg.get("count").getAsInt();
        assertTrue(count > 0, seg.toString());
        assertEquals(count, engine.results().size(), "Analyze Particles (clear) wiped the earlier measurement; only the objects remain");
        assertTrue(engine.results().stream().noneMatch(r -> row.actionId().equals(r.actionId())));
        assertEquals(1, seg.get("undoSteps").getAsInt(), "the copy of the objects to the original is the one restorable step");

        JsonObject undone = http.change("undo", args("steps", 1)).result();
        assertEquals("COMPLETED", undone.getAsJsonObject("step").get("outcome").getAsString(), undone.toString());
        List<MeasurementRow> after = engine.results();
        System.out.println("[penny-stability] measure -> segment (" + count + " objects, table cleared) -> undo: rows " + before.size() + " -> " + count
                + " -> " + after.size() + "; restored " + undone.get("restored"));
        assertSameRows(before, after, "the rows the segmentation cleared are back and its own rows are gone");
        assertEquals(0, engine.rois(doc).size(), "the objects left the ROI list");
        JsonObject rows = undone.getAsJsonObject("restored").getAsJsonObject("resultsRows");
        assertEquals(count, rows.get("before").getAsInt());
        assertEquals(before.size(), rows.get("after").getAsInt());
        assertEquals(after.size(), ij.measure.ResultsTable.getResultsTable().size(), "the live IJ1 table equals the mirror");
    }

    /** BUG-03: segment_cells closes its mask; undo then redo must still bring the objects and their rows back (no 409). */
    @Test
    void anUndoneSegmentationIsRedoneWithoutItsClosedMask() throws Exception {
        DocumentId doc = openBlobs();
        http.change("measure", args("scope", "all", "measurements", List.of("area", "mean"))).result();
        List<MeasurementRow> measuredRows = engine.results();
        String pixels = PixelHash.sha256(engine.imagePlus(doc));

        JsonObject seg = http.change("segment_cells", args()).result();
        int count = seg.get("count").getAsInt();
        assertTrue(seg.getAsJsonObject("mask").get("closed").getAsBoolean(), "the mask is closed again by default");
        List<String> objects = engine.rois(doc).stream().map(RoiSignature::of).toList();
        assertEquals(count, objects.size());
        List<MeasurementRow> segRows = engine.results();
        assertEquals(count, segRows.size());
        String particles = stepAction(seg, "Analyze Particles...");
        assertNotNull(particles);
        assertTrue(segRows.stream().allMatch(r -> particles.equals(r.actionId())));

        JsonObject undone = http.change("undo", args("steps", seg.get("undoSteps").getAsInt())).result();
        assertEquals("COMPLETED", undone.getAsJsonObject("step").get("outcome").getAsString());
        assertEquals(0, engine.rois(doc).size());
        assertSameRows(measuredRows, engine.results(), "after undo");

        BridgeHttp.Resp redo = http.change("redo", args());
        assertEquals(200, redo.status(), "redo answered " + redo.body());
        JsonObject redone = redo.result();
        assertEquals("completed", redone.get("status").getAsString(), redone.toString());
        assertEquals("COMPLETED", redone.getAsJsonObject("step").get("outcome").getAsString(), redone.toString());
        assertEquals(objects, engine.rois(doc).stream().map(RoiSignature::of).toList(), "the objects are back, vertex for vertex");
        assertSameRows(segRows, engine.results(), "after redo: the objects' rows are back and the earlier measurement is cleared again");
        assertEquals(pixels, PixelHash.sha256(engine.imagePlus(doc)), "the original's pixels never changed");
        HistoryRecord last = engine.history().get(engine.history().size() - 1);
        assertEquals(Operations.ROI_LIST_ADD, last.commandId(), last.toString());
        assertEquals(Boolean.TRUE, last.params().get("redone"), last.params().toString());
        assertEquals((long) count, last.params().get("count"), last.params().toString());
        System.out.println("[penny-stability] segment (" + count + ") -> undo -> redo: " + engine.rois(doc).size() + " objects and "
                + engine.results().size() + " rows back; history " + last.label() + " " + last.params());

        // and the redo is itself undoable, back to the measured state
        JsonObject again = http.change("undo", args()).result();
        assertEquals("COMPLETED", again.getAsJsonObject("step").get("outcome").getAsString());
        assertEquals(0, engine.rois(doc).size());
        assertSameRows(measuredRows, engine.results(), "after undoing the redo");
        assertEquals(engine.results().size(), ij.measure.ResultsTable.getResultsTable().size(), "the live IJ1 table equals the mirror");
    }

    /** BUG-01: cancel_plan while a multi-step plan runs stops it; the steps after the stop never run. */
    @Test
    void aRunningPlanStopsOnCancelPlanAndItsLaterStepsNeverRun() throws Exception {
        DocumentId doc = openBlobs();
        JsonObject seg = http.change("segment_cells", args()).result();
        int count = seg.get("count").getAsInt();
        assertTrue(count >= 20, "Blobs gives dozens of objects: " + count);
        int rows0 = engine.results().size();
        AtomicBoolean fired = new AtomicBoolean();
        AtomicReference<BridgeHttp.Resp> cancel = new AtomicReference<>();
        CountDownLatch cancelled = new CountDownLatch(1);
        // as soon as the plan reports its 4th step, the "researcher" says stop (the gateway's fidelis_cancel_plan {})
        RecordingUi stopper = new RecordingUi() {
            @Override
            public void planProgress(String planId, int step, String label) {
                super.planProgress(planId, step, label);
                if (step == 3 && fired.compareAndSet(false, true)) {
                    Thread t = new Thread(() -> {
                        try {
                            cancel.set(http.exec("cancel_plan", args(), null, null));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            cancelled.countDown();
                        }
                    }, "test-stop");
                    t.setDaemon(true);
                    t.start();
                }
            }
        };
        PennyUi.register(stopper);
        BridgeHttp.Resp r = http.change("measure", args("scope", "rois", "measurements", List.of("area")));
        assertTrue(cancelled.await(10, TimeUnit.SECONDS), "cancel_plan answered");
        assertEquals(200, r.status(), r.body().toString());
        JsonObject res = r.result();
        assertEquals("cancelled", res.get("status").getAsString(), res.toString());
        assertTrue(res.get("stopped").getAsBoolean(), res.toString());
        int completed = res.getAsJsonArray("completedSteps").size();
        int measuredSteps = 0;
        for (JsonElement e : res.getAsJsonArray("completedSteps"))
            if (e.getAsJsonObject().get("label").getAsString().startsWith("Measure ROI")) measuredSteps++;
        if (res.has("step") && res.getAsJsonObject("step").get("label").getAsString().startsWith("Measure ROI")
                && "COMPLETED".equals(res.getAsJsonObject("step").get("outcome").getAsString())) measuredSteps++;
        int measuredRows = engine.results().size() - rows0;
        System.out.println("[penny-stability] measure rois (" + count + " objects) stopped at step 4: " + completed + " completed step(s), "
                + measuredRows + " row(s) measured; cancel_plan answered " + cancel.get().body() + "; note: " + res.get("note"));
        assertEquals(200, cancel.get().status(), cancel.get().body().toString());
        assertTrue(cancel.get().result().get("cancelled").getAsBoolean());
        assertTrue(cancel.get().result().get("stopped").getAsBoolean());
        assertTrue(measuredRows >= 3, "the steps before the stop ran: " + measuredRows);
        assertTrue(measuredRows < count, "the steps after the stop did not: " + measuredRows + " of " + count);
        assertEquals(measuredSteps, measuredRows, "every row in the table belongs to a step the result reports");
        assertTrue(res.get("note").getAsString().contains("did not run"), res.get("note").getAsString());
        assertTrue(stopper.calls.stream().anyMatch(c -> c.startsWith("finished " + res.get("planId").getAsString() + " ok=false stopped")),
                "the workspace was told the plan stopped: " + stopper.calls);
        assertTrue(http.read("context", null).get("running").isJsonNull(), "Penny is free for the next plan");
        // the stop is a real cancellation: the next plan runs
        assertEquals("completed", http.change("measure", args("scope", "all", "measurements", List.of("area"))).result().get("status").getAsString());
    }

    /** BUG-01: a running macro is aborted in the engine by cancel_plan (Engine.cancel), not left to finish. */
    @Test
    void aRunningMacroIsAbortedByCancelPlan() throws Exception {
        openBlobs();
        JsonObject held = http.change("run_macro", args("code", "for (i = 0; i < 400; i++) wait(50);")).result();
        assertEquals("awaiting_confirmation", held.get("status").getAsString(), held.toString());
        String planId = held.get("planId").getAsString();
        String ctx = http.contextKey();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<BridgeHttp.Resp> confirm = ex.submit(() -> http.exec("confirm", args("planId", planId), true, ctx));
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && http.read("context", null).get("running").isJsonNull()) Thread.sleep(50);
            assertFalse(http.read("context", null).get("running").isJsonNull(), "the macro plan is running");
            Thread.sleep(500); // inside the macro's loop (20 s if left alone)
            long t0 = System.currentTimeMillis();
            JsonObject cancel = http.exec("cancel_plan", args(), null, null).result();
            assertTrue(cancel.get("cancelled").getAsBoolean(), cancel.toString());
            assertTrue(cancel.get("stopped").getAsBoolean(), cancel.toString());
            assertTrue(cancel.get("engineCancelRequested").getAsBoolean(), cancel.toString());
            BridgeHttp.Resp done = confirm.get(15, TimeUnit.SECONDS);
            long took = System.currentTimeMillis() - t0;
            assertEquals(200, done.status(), done.body().toString());
            JsonObject res = done.result();
            assertEquals("cancelled", res.get("status").getAsString(), res.toString());
            assertTrue(res.get("stopped").getAsBoolean(), res.toString());
            HistoryRecord last = engine.history().get(engine.history().size() - 1);
            System.out.println("[penny-stability] 20 s macro stopped " + took + " ms after cancel_plan; engine state " + res.get("state")
                    + "; history: " + last.label() + " " + last.outcome() + " " + last.errorSummary());
            assertTrue(took < 8_000, "the macro stopped within " + took + " ms, not after its 20 s");
            assertNotEquals(OperationState.COMPLETED, last.outcome(), "the macro did not run to completion");
            assertTrue(http.read("context", null).get("running").isJsonNull(), "Penny is free again");
        } finally {
            ex.shutdownNow();
        }
    }

    /** BUG-04: measure {scope: rois, channel} measures every ROI on that channel and says so; without it, the ROI's own channel. */
    @Test
    void aRequestedChannelIsMeasuredAndReportedForEveryRoi() throws Exception {
        ActionResult made = engine.submit(ActionRequest.session(Origin.PANEL, Operations.NEW_IMAGE,
                Map.of("title", "two-channel", "type", "8-bit", "width", 32, "height", 32, "channels", 2, "fill", "Ramp"), null))
                .get(60, TimeUnit.SECONDS);
        assertTrue(made.succeeded(), String.valueOf(made.error()));
        DocumentId doc = made.digest().added().get(0);
        assertEquals(2, engine.document(doc).orElseThrow().nChannels());
        // channel 2 brighter by 100 (Add has a stack key, false by default: the current plane only)
        http.change("stack", args("channel", 2)).result();
        http.change("command", args("commandId", "ij1:Process>Math>Add...", "params", args("value", 100))).result();
        http.change("stack", args("channel", 1)).result();
        // an object found on channel 1: its ROI carries that position
        RoiGeometry g = new RoiGeometry("RECTANGLE", List.of(0f, 16f), List.of(0f, 32f), new double[] {0, 0, 16, 32}, null, new int[] {1, 0, 0}, null);
        assertTrue(engine.submit(ActionRequest.direct(Origin.PANEL, Operations.SET_ROI, Map.of("roi", g), TargetScope.of(doc))).get(30, TimeUnit.SECONDS).succeeded());
        assertTrue(engine.submit(ActionRequest.direct(Origin.PANEL, Operations.ROI_LIST_ADD, Map.of(), TargetScope.of(doc))).get(30, TimeUnit.SECONDS).succeeded());
        http.exec("select_roi", args("type", "none"), null, null).result();
        ImagePlus imp = engine.imagePlus(doc);
        double[] want = new double[2];
        for (int c = 1; c <= 2; c++) {
            ImageProcessor ip = imp.getStack().getProcessor(imp.getStackIndex(c, 1, 1));
            double sum = 0;
            for (int y = 0; y < 32; y++) for (int x = 0; x < 16; x++) sum += ip.getf(x, y);
            want[c - 1] = sum / (16 * 32);
        }
        assertNotEquals(want[0], want[1]);

        JsonObject asked = http.change("measure", args("scope", "rois", "measurements", List.of("mean"), "channel", 2)).result();
        assertEquals(1, asked.get("rows").getAsInt(), asked.toString());
        assertEquals(2, asked.get("channel").getAsInt(), "the channel asked for was measured: " + asked);
        assertEquals("argument", asked.get("channelBy").getAsString(), asked.toString());
        List<MeasurementRow> rows = engine.results();
        assertEquals(want[1], rows.get(rows.size() - 1).values().get("Mean"), 1e-9, "the mean of channel 2's pixels under the ROI");

        JsonObject own = http.change("measure", args("scope", "rois", "measurements", List.of("mean"))).result();
        assertEquals(1, own.get("channel").getAsInt(), "without an argument the ROI's own channel: " + own);
        assertEquals("roi position", own.get("channelBy").getAsString(), own.toString());
        rows = engine.results();
        assertEquals(want[0], rows.get(rows.size() - 1).values().get("Mean"), 1e-9, "the mean of channel 1's pixels under the ROI");
        assertEquals(1, imp.getC(), "the viewer is back on channel 1");
        System.out.println("[penny-stability] 2-channel ROI on c1: channel 2 asked -> mean " + want[1] + " (" + asked.get("channelBy")
                + "), no argument -> mean " + want[0] + " (" + own.get("channelBy") + ")");
        List<String> added = new ArrayList<>();
        for (HistoryRecord h : engine.history().subList(engine.history().size() - 6, engine.history().size())) added.add(h.label());
        System.out.println("[penny-stability] history tail: " + added);
    }
}
