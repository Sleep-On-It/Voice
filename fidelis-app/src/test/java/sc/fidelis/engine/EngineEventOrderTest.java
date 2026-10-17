package sc.fidelis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.EngineListener;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;

/**
 * Lifecycle events for every request kind (#5, {@code EngineEvent} javadoc): per operation, QUEUED, then
 * RUNNING, then the terminal state, then the StateDigestEvent, then HistoryAppended, with strictly increasing
 * sequence numbers and nothing of that operation after its HistoryAppended.
 */
class EngineEventOrderTest {
    DefaultEngine engine;
    DocumentId doc;
    final List<EngineEvent> seen = new CopyOnWriteArrayList<>();
    final EngineListener listener = seen::add;

    @BeforeEach
    void setUp() throws Exception {
        engine = EngineFixture.engine();
        ActionResult r = engine.open(EngineFixture.repoFile("fixtures/two-squares.png")).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        doc = r.digest().added().get(0);
        engine.focus(doc);
        engine.addListener(listener);
    }

    @AfterEach
    void tearDown() throws Exception {
        engine.removeListener(listener);
        for (var d : engine.documents()) {
            engine.submit(ActionRequest.session(Origin.PANEL, Operations.CLOSE, Map.of("discard", true), TargetScope.of(d.id())))
                    .get(30, TimeUnit.SECONDS);
        }
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        return p;
    }

    /** Events of one operation in delivery order, after the EDT has delivered everything emitted so far. */
    private List<String> trace(String opId) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        List<String> out = new ArrayList<>();
        long last = 0;
        for (EngineEvent e : seen) {
            assertTrue(e.seq() > last, "sequence must be strictly increasing: " + e.seq() + " after " + last);
            last = e.seq();
            if (e instanceof EngineEvent.OperationStateChanged s && opId.equals(s.operationId())) out.add(s.state().name());
            else if (e instanceof EngineEvent.StateDigestEvent d && opId.equals(d.operationId())) out.add("DIGEST");
            else if (e instanceof EngineEvent.HistoryAppended h && opId.equals(h.record().actionId())) out.add("HISTORY");
        }
        return out;
    }

    private void assertDocumentedOrder(String kind, ActionResult r, boolean expectHistory) throws Exception {
        List<String> t = trace(r.operationId());
        String terminal = r.state().name();
        List<String> expected = new ArrayList<>(List.of("QUEUED", "VALIDATING", "RUNNING", terminal, "DIGEST"));
        if (expectHistory) expected.add("HISTORY");
        // HistoryAppended carries the record, not the operation id: match it through the result's record
        if (expectHistory && r.record() != null) {
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
            boolean hist = seen.stream().anyMatch(e -> e instanceof EngineEvent.HistoryAppended h
                    && h.record().actionId().equals(r.record().actionId()));
            assertTrue(hist, kind + ": HistoryAppended for " + r.record().actionId() + " was not delivered");
        }
        List<String> states = t.stream().filter(s -> !s.equals("HISTORY")).toList();
        assertEquals(expected.stream().filter(s -> !s.equals("HISTORY")).toList(), states,
                kind + " " + r.operationId() + " events: " + t + " (result " + r.state() + " " + r.error() + ")");
    }

    @Test
    void sessionOperationEmitsQueuedRunningTerminalDigestHistory() throws Exception {
        ActionResult r = engine.submit(ActionRequest.session(Origin.PANEL, Operations.NEW_IMAGE,
                m("title", "evt", "type", "8-bit", "width", 16, "height", 16, "slices", 1, "fill", "Black"), null)).get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertDocumentedOrder("SESSION NEW_IMAGE", r, true);
        ActionResult c = engine.submit(ActionRequest.session(Origin.PANEL, Operations.CLOSE, m("discard", true),
                TargetScope.of(r.digest().added().get(0)))).get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, c.state(), String.valueOf(c.error()));
        assertDocumentedOrder("SESSION CLOSE", c, true);
    }

    @Test
    void directOperationEmitsQueuedRunningTerminalDigestHistory() throws Exception {
        ActionResult r = engine.submit(ActionRequest.direct(Origin.PANEL, Operations.SET_THRESHOLD, m("lower", 10, "upper", 200),
                TargetScope.of(doc))).get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertDocumentedOrder("DIRECT SET_THRESHOLD", r, true);
    }

    @Test
    void commandEmitsQueuedRunningTerminalDigestHistory() throws Exception {
        ActionResult r = engine.submit(ActionRequest.command(Origin.PANEL, "ij1:Edit>Invert", m(), TargetScope.of(doc)))
                .get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), String.valueOf(r.error()));
        assertDocumentedOrder("COMMAND Invert", r, true);
    }

    @Test
    void codeEmitsQueuedRunningTerminalDigestHistoryOnceConfirmed() throws Exception {
        ij.ImagePlus cur = ij.WindowManager.getCurrentImage();
        assertTrue(cur == engine.imagePlus(doc), "IJ1's current image must be the focused document, was "
                + (cur == null ? "null" : cur.getTitle() + " type " + cur.getType() + " id " + cur.getID()));
        assertEquals(ij.ImagePlus.GRAY8, cur.getType(), "two-squares.png opens as 8-bit grayscale");
        String macro = "run(\"Invert\");";
        ActionResult first = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", macro, TargetScope.of(doc), null))
                .get(30, TimeUnit.SECONDS);
        assertEquals(OperationState.AWAITING_CONFIRMATION, first.state());
        List<String> t = trace(first.operationId());
        assertEquals(List.of("QUEUED", "VALIDATING", "AWAITING_CONFIRMATION"), t.stream().filter(s -> !s.equals("DIGEST")).toList(),
                "an unconfirmed CODE request runs nothing: " + t);
        ActionResult r = engine.submit(ActionRequest.code(Origin.PANEL, "ijm", macro, TargetScope.of(doc),
                first.confirmationToken())).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETED, r.state(), () -> r.error() + " log=" + (r.digest() == null ? "-" : r.digest().newLogLines())
                + " batchNow=" + ij.macro.Interpreter.isBatchMode() + " batchImages=" + ij.macro.Interpreter.getBatchModeImageCount()
                + " current=" + ij.WindowManager.getCurrentImage());
        assertDocumentedOrder("CODE", r, true);
    }

    @Test
    void failedRequestStillEndsWithTerminalThenDigestThenHistory() throws Exception {
        ActionResult r = engine.submit(ActionRequest.command(Origin.PANEL, "ij1:Analyze>Analyze Particles...",
                m("size", "0-Infinity", "circularity", "0.00-1.00", "show", "Nothing", "display", true, "exclude", false, "clear", false,
                        "include", false, "summarize", false, "overlay", false, "add", false, "composite", false, "record", false,
                        "in_situ", false), TargetScope.of(doc))).get(60, TimeUnit.SECONDS);
        assertEquals(OperationState.FAILED, r.state(), "no threshold: precondition must fail, got " + r.error());
        List<String> t = trace(r.operationId());
        assertEquals("QUEUED", t.get(0), t.toString());
        assertTrue(t.contains("FAILED"), t.toString());
        int terminal = t.indexOf("FAILED");
        int digest = t.indexOf("DIGEST");
        assertTrue(digest < 0 || digest > terminal, "digest (if any) follows the terminal state: " + t);
        assertEquals(t.size() - 1, Math.max(terminal, digest), "nothing of the operation after its last event: " + t);
    }
}
