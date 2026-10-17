package sc.fidelis.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.EngineListener;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.testkit.FijiFixture;

/**
 * Issue #16, A2 hazard: a disposed {@link Workspace} must leave no engine listener behind, otherwise the
 * next workspace in the same JVM (and every test after it) receives events twice. The engine is the
 * development stub wrapped in a counting delegate, so the assertion is on the registrations themselves.
 */
class WorkspaceDisposeTest {
    @BeforeAll
    static void fiji() {
        FijiFixture.imageJ();
    }

    /** Delegates everything to the real engine and records which listeners are currently registered. */
    static final class CountingEngine implements Engine {
        final Engine inner;
        final List<EngineListener> live = new CopyOnWriteArrayList<>();
        int added;
        int removed;

        CountingEngine(Engine inner) {
            this.inner = inner;
        }

        @Override public EngineState state() { return inner.state(); }
        @Override public synchronized void addListener(EngineListener l) { added++; live.add(l); inner.addListener(l); }
        @Override public synchronized void removeListener(EngineListener l) { removed++; live.remove(l); inner.removeListener(l); }
        @Override public void quit(boolean discardUnsaved) { inner.quit(discardUnsaved); }
        @Override public CompletableFuture<ActionResult> submit(ActionRequest request) { return inner.submit(request); }
        @Override public void cancel(String operationId) { inner.cancel(operationId); }
        @Override public List<DocumentInfo> documents() { return inner.documents(); }
        @Override public Optional<DocumentInfo> document(DocumentId id) { return inner.document(id); }
        @Override public CompletableFuture<ActionResult> open(Path file) { return inner.open(file); }
        @Override public Optional<DocumentId> focused() { return inner.focused(); }
        @Override public void focus(DocumentId id) { inner.focus(id); }
        @Override public ij.ImagePlus imagePlus(DocumentId id) { return inner.imagePlus(id); }
        @Override public ij.gui.ImageWindow hostWindow(DocumentId id) { return inner.hostWindow(id); }
        @Override public List<RoiGeometry> rois(DocumentId id) { return inner.rois(id); }
        @Override public Optional<RoiGeometry> activeRoi(DocumentId id) { return inner.activeRoi(id); }
        @Override public List<MeasurementRow> results() { return inner.results(); }
        @Override public MeasurementRow.Columns resultColumns() { return inner.resultColumns(); }
        @Override public List<CommandSchema.Summary> catalogue() { return inner.catalogue(); }
        @Override public Optional<CommandSchema> schema(String commandId) { return inner.schema(commandId); }
        @Override public List<CommandSchema.Summary> search(String query, int limit) { return inner.search(query, limit); }
        @Override public List<HistoryRecord> history() { return inner.history(); }
        @Override public String exportMacro() { return inner.exportMacro(); }
        @Override public Map<String, String> globals() { return inner.globals(); }
        @Override public long[] undoBudget() { return inner.undoBudget(); }
    }

    private static void waitFor(String what, BooleanSupplier c, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(40);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @Test
    void noEngineListenerRemainsAfterDispose() throws Exception {
        CountingEngine engine = new CountingEngine(new sc.fidelis.ui.dev.StubEngine(null));
        Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            w[0] = Workspace.create(engine, null, new Dimension(1200, 800));
            w[0].frame().setVisible(true);
        });
        waitFor("engine ready", () -> engine.state() == EngineState.READY, 30);
        assertEquals(1, engine.added, "the workspace registers exactly one engine listener");
        assertEquals(1, engine.live.size(), "one live listener while the workspace is open");

        SwingUtilities.invokeAndWait(() -> w[0].frame().dispose());
        // WINDOW_CLOSED is delivered asynchronously after dispose()
        waitFor("engine listener removed on dispose", () -> engine.live.isEmpty(), 10);
        assertEquals(1, engine.removed, "exactly the registered listener was removed");
        assertTrue(engine.live.isEmpty(), "no engine listener remains after Workspace dispose: " + engine.live);
    }
}
