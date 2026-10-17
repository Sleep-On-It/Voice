package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.ResultsTable;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.Enums.ParameterType;
import sc.fidelis.engine.api.Enums.Provenance;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.TargetScope;

/**
 * Native-path Results synchronisation (#5): commands a person runs through Fiji itself (observed, provenance
 * NATIVE) must leave the engine's Results mirror equal to the IJ1 system table, including when the table is
 * cleared and refilled to the same size before the observer's follow-up timer fires (the drift A1 documented).
 */
class EngineNativeSyncTest {
    DefaultEngine engine;
    DocumentId doc;
    ImagePlus imp;

    @BeforeEach
    void open() throws Exception {
        engine = EngineFixture.engine();
        ActionResult r = engine.open(EngineFixture.repoFile("fixtures/two-squares.png")).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        doc = r.digest().added().get(0);
        engine.focus(doc);
        imp = engine.imagePlus(doc);
        ActionResult sm = engine.submit(ActionRequest.command(Origin.PANEL, "ij1:Analyze>Set Measurements...", setMeasurements("area", "mean"),
                TargetScope.of(doc))).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, sm.state(), String.valueOf(sm.error()));
    }

    @AfterEach
    void close() throws Exception {
        for (var d : engine.documents()) {
            engine.submit(ActionRequest.session(Origin.PANEL, Operations.CLOSE, Map.of("discard", true), TargetScope.of(d.id())))
                    .get(30, TimeUnit.SECONDS);
        }
    }

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

    static void waitFor(String what, BooleanSupplier c, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(40);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    /** The engine mirror must equal the live IJ1 table row by row (bit-exact doubles). */
    void assertMirrorEqualsTable() {
        ResultsTable rt = ResultsTable.getResultsTable();
        List<MeasurementRow> rows = engine.results();
        assertEquals(rt.size(), rows.size(), "row count: engine mirror vs IJ1 table");
        for (int r = 0; r < rt.size(); r++) {
            for (String h : rt.getHeadings()) {
                if (h == null || h.equals("Label")) continue;
                double want = rt.getValueAsDouble(rt.getColumnIndex(h), r);
                Double got = rows.get(r).values().get(h);
                assertTrue(got != null && Double.doubleToLongBits(got) == Double.doubleToLongBits(want),
                        "row " + r + " " + h + ": engine " + got + " vs table " + want);
            }
        }
    }

    private HistoryRecord lastNative(String label) {
        List<HistoryRecord> h = engine.history();
        for (int i = h.size() - 1; i >= 0; i--) {
            if (h.get(i).provenance() == Provenance.NATIVE && label.equals(h.get(i).label())) return h.get(i);
        }
        throw new AssertionError("no NATIVE record labelled " + label);
    }

    @Test
    void nativeClearAndRemeasureWithinTheCoalescingWindowRebuildsTheMirror() throws Exception {
        IJ.run(imp, "Measure", ""); // a menu command on the test thread, like a person clicking Analyze>Measure
        waitFor("first native Measure row", () -> engine.results().size() == 1, 10);
        assertEquals(18.75, engine.results().get(0).values().get("Mean"));

        ActionResult n = engine.submit(ActionRequest.session(Origin.PANEL, Operations.NEW_IMAGE,
                m("title", "white", "type", "8-bit", "width", 32, "height", 32, "slices", 1, "fill", "White"), null)).get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, n.state(), String.valueOf(n.error()));
        ImagePlus white = engine.imagePlus(n.digest().added().get(0));

        // the drift: clear and refill to the same size before the 600 ms follow-up of either command has run
        IJ.run("Clear Results");
        IJ.run(white, "Measure", "");
        assertEquals(1, ResultsTable.getResultsTable().size());
        waitFor("mirror rebuilt from the new table", () -> engine.results().size() == 1
                && Double.valueOf(255.0).equals(engine.results().get(0).values().get("Mean")), 10);
        assertMirrorEqualsTable();
        assertEquals(1024.0, engine.results().get(0).values().get("Area"));
        assertEquals(lastNative("Measure").actionId(), engine.results().get(0).actionId(), "attributed to the Measure that produced it");
    }

    @Test
    void nativeRemeasureOfIdenticalRowsAfterClearIsAttributedToTheNewCommand() throws Exception {
        IJ.run(imp, "Measure", "");
        waitFor("first native Measure row", () -> engine.results().size() == 1, 10);
        String firstAction = engine.results().get(0).actionId();
        assertEquals(lastNative("Measure").actionId(), firstAction);

        IJ.run("Clear Results");
        IJ.run(imp, "Measure", ""); // same image, same settings: the new row is bit-identical to the old one
        String secondAction = lastNative("Measure").actionId();
        assertNotEquals(firstAction, secondAction);
        waitFor("mirror re-attributed", () -> engine.results().size() == 1 && secondAction.equals(engine.results().get(0).actionId()), 10);
        assertMirrorEqualsTable();
    }

    @Test
    void backToBackNativeMeasuresAreEachAttributedToTheirOwnCommand() throws Exception {
        IJ.run(imp, "Measure", "");
        String a1 = lastNative("Measure").actionId();
        IJ.run(imp, "Measure", "");
        String a2 = lastNative("Measure").actionId();
        IJ.run(imp, "Measure", "");
        String a3 = lastNative("Measure").actionId();
        waitFor("three rows", () -> engine.results().size() == 3, 10);
        assertMirrorEqualsTable();
        assertEquals(List.of(a1, a2, a3), engine.results().stream().map(MeasurementRow::actionId).toList());
        assertEquals(doc, engine.results().get(2).document());
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        return p;
    }
}
