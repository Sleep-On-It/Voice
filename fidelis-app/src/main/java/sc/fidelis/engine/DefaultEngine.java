package sc.fidelis.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import ij.macro.Interpreter;
import ij.plugin.frame.RoiManager;
import net.imagej.ImageJ;
import sc.fidelis.Bootstrap;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.ActionResult.EngineError;
import sc.fidelis.engine.api.ActionResult.StateDigest;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.EngineListener;
import sc.fidelis.engine.api.Enums.ActionKind;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Provenance;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.HostCanvas;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;

/**
 * The Fidelis engine over the real Fiji/ImageJ runtime (hybrid B). One worker thread
 * ({@code fidelis-engine}) executes every mutating operation; the EDT is only used to create and
 * dispose the hidden host windows. Events are delivered on the EDT in sequence order.
 */
public final class DefaultEngine implements Engine {
    private final ImageJ ij;
    final Events events = new Events();
    final Registry registry = new Registry(events);
    final History history = new History();
    private final ThreadPoolExecutor worker;
    private final AtomicInteger opSeq = new AtomicInteger();
    private final Map<String, Op> ops = new ConcurrentHashMap<>();
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile EngineState state = EngineState.INITIALISING;
    private volatile DocumentId focused;
    private volatile boolean reconcileQueued;
    private final java.util.concurrent.locks.ReentrantLock adoptLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile Catalogue catalogue = Catalogue.empty();
    final Results results = new Results(events);
    final RoiLists roiLists = new RoiLists();
    final Snapshots snapshots = new Snapshots();
    final CommandRunner runner = new CommandRunner(this);
    final DirectOps direct = new DirectOps(this);
    final CodeRunner code = new CodeRunner(this);
    final Observers observers = new Observers(this);
    private final String sessionId = "session-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now());
    private volatile Op running;
    private volatile Guard guard;
    private volatile Interpreter interpreter;

    private DefaultEngine(ImageJ ij) {
        this.ij = ij;
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "fidelis-engine");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Create the engine for a running SciJava context (legacy layer active) and publish it through
     * {@link Engines#current()}. The catalogue is built asynchronously; the state moves
     * INITIALISING, BUILDING_CATALOGUE, READY.
     */
    public static DefaultEngine create(ImageJ ij) {
        DefaultEngine e = new DefaultEngine(ij);
        Engines.set(e);
        e.start();
        return e;
    }

    private void start() {
        setState(EngineState.INITIALISING, "created");
        observers.install();
        Thread t = new Thread(() -> {
            try {
                setState(EngineState.BUILDING_CATALOGUE, "building command catalogue");
                buildCatalogue();
                setState(EngineState.READY, "ready");
            } catch (Throwable e) {
                System.err.println("[fidelis-engine] catalogue build failed: " + e);
                e.printStackTrace();
                setState(EngineState.FAILED, String.valueOf(e));
            } finally {
                ready.countDown();
            }
        }, "fidelis-catalogue");
        t.setDaemon(true);
        t.start();
    }

    /** Block until the catalogue is built (tests and start-up code). */
    public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        return ready.await(timeout, unit) && state == EngineState.READY;
    }

    /** Rebuild the catalogue from the live IJ1 command table (after plugins were added at run time). */
    public void refreshCatalogue() {
        catalogue = Catalogue.buildFromRuntime();
    }

    private void buildCatalogue() {
        catalogue = Catalogue.buildFromRuntime();
    }

    private void setState(EngineState s, String reason) {
        state = s;
        events.emit(seq -> new EngineEvent.EngineStateChanged(seq, s, reason));
    }

    // ---- lifecycle --------------------------------------------------------------------------

    @Override
    public EngineState state() {
        return state;
    }

    @Override
    public void addListener(EngineListener listener) {
        events.add(listener);
    }

    @Override
    public void removeListener(EngineListener listener) {
        events.remove(listener);
    }

    /** The registered listeners, in registration order (implementation-only; test isolation and diagnostics). */
    public List<EngineListener> listeners() {
        return events.snapshot();
    }

    @Override
    public void quit(boolean discardUnsaved) {
        quit(discardUnsaved, worker::shutdown, () -> {
            Thread t = new Thread(() -> Bootstrap.exit(ij, 0), "fidelis-quit");
            t.start();
        });
    }

    /**
     * The contract of {@link Engine#quit}: with unsaved changes and {@code discardUnsaved} false, nothing is closed and
     * the call returns (refused); otherwise every document is closed with {@code changes=false}, the workers stop and the
     * JVM is terminated. The two final steps are parameters so the lifecycle can be tested without ending the test JVM.
     *
     * @return true when the quit went ahead
     */
    boolean quit(boolean discardUnsaved, Runnable stopWorkers, Runnable terminate) {
        adoptBeforeNative(); // images IJ1 opened and the registry has not seen yet count as documents
        if (!discardUnsaved) {
            for (Doc d : registry.all()) if (d.imp.changes) return false;
            for (int id : idList()) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null && imp.changes && imp.getProperty(HostCanvas.TRANSIENT_PROPERTY) == null) return false;
            }
        }
        setState(EngineState.QUITTING, "quit");
        for (Doc d : registry.all()) {
            try {
                Edt.run(() -> closeDoc(d, true));
            } catch (RuntimeException ignored) {
                // closing is best effort at shutdown
            }
        }
        for (int id : idList()) { // whatever IJ1 still holds that was never hosted
            ImagePlus imp = WindowManager.getImage(id);
            if (imp == null) continue;
            imp.changes = false;
            try {
                Edt.run(imp::close);
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        reconcile();
        stopWorkers.run();
        terminate.run();
        return true;
    }

    private static int[] idList() {
        int[] ids = WindowManager.getIDList();
        return ids == null ? new int[0] : ids;
    }

    /** Test support: return to READY after a quit that did not terminate the JVM. */
    void resumeAfterQuitForTest() {
        setState(EngineState.READY, "resumed");
    }

    // ---- actions ----------------------------------------------------------------------------

    @Override
    public CompletableFuture<ActionResult> submit(ActionRequest request) {
        String opId = "op-" + opSeq.incrementAndGet();
        Op op = new Op(opId, request);
        CompletableFuture<ActionResult> future = new CompletableFuture<>();
        if (state != EngineState.READY && (request.kind() == ActionKind.COMMAND || request.kind() == ActionKind.CODE)) {
            EngineError err = EngineError.of(ErrorCode.ENGINE_NOT_READY, "engine is " + state);
            future.complete(new ActionResult(opId, OperationState.FAILED, err, null, null, null, 0));
            return future;
        }
        ops.put(opId, op);
        events.emit(seq -> new EngineEvent.OperationStateChanged(seq, opId, request.requestId(), OperationState.QUEUED, null));
        try {
            worker.execute(() -> {
                try {
                    future.complete(runOp(op));
                } catch (Throwable t) {
                    future.complete(new ActionResult(opId, OperationState.FAILED,
                            EngineError.of(ErrorCode.EXECUTION_FAILED, "internal engine error: " + t), null, null, null, 0));
                } finally {
                    ops.remove(opId);
                }
            });
        } catch (RuntimeException e) {
            ops.remove(opId);
            future.complete(new ActionResult(opId, OperationState.FAILED,
                    EngineError.of(ErrorCode.ENGINE_LOST, "engine worker is not running"), null, null, null, 0));
        }
        return future;
    }

    @Override
    public void cancel(String operationId) {
        Op op = ops.get(operationId);
        if (op == null) return;
        op.cancelRequested = true;
        if (running == op) {
            if (op.request.kind() == ActionKind.CODE) {
                // Abort the interpreter this operation runs (never the static Interpreter.instance, which may be
                // another macro's or still unset while ours is tokenising). abortMacro waits up to 2 s for the
                // macro thread to stop, so it runs off the caller's thread (the UI cancels from the EDT).
                boolean started;
                synchronized (op) {
                    started = op.interpreterStarted;
                    op.abortIssued = true;
                }
                Interpreter i = interpreter;
                if (started && i != null) {
                    Thread t = new Thread(() -> {
                        // Interpreter.run(Program) clears the abort flag once it has tokenised the text: an abort
                        // issued in that window is lost, so repeat until the runner reports the macro finished.
                        for (int k = 0; k < 50 && !op.interpreterFinished; k++) {
                            i.abortMacro();
                            try {
                                Thread.sleep(20);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }, "fidelis-abort-" + op.id);
                    t.setDaemon(true);
                    t.start();
                }
            } else {
                op.abortIssued = true;
                CommandRunner.requestEscape();
            }
            Guard g = guard;
            if (g != null) g.disposePending(); // a pending native dialog must not keep the worker blocked
        }
    }

    // ---- plumbing used by the runners --------------------------------------------------------------

    Catalogue cat() {
        return catalogue;
    }

    void setRunning(Op op) {
        running = op;
    }

    void setGuard(Guard g) {
        guard = g;
    }

    void setInterpreter(Interpreter i) {
        interpreter = i;
    }

    /** Reconcile with IJ1 window list; releases snapshots of closed documents and fixes focus. */
    Registry.Change reconcile() {
        adoptLock.lock();
        try {
            Registry.Change ch = registry.reconcile();
            for (Doc d : ch.removed()) {
                snapshots.releaseAll(d);
                observers.forget(d.imp);
                if (d.id.equals(focused)) focused = null;
            }
            return ch;
        } finally {
            adoptLock.unlock();
        }
    }

    /**
     * Adopt images that IJ1 opened behind our back <em>before</em> a native command runs on the calling
     * thread. Re-hosting swaps the image's window, and IJ1's window swap briefly makes another image
     * current; done asynchronously on the worker it raced a macro's {@code open(); run("...")} and the
     * command hit the wrong image (observed in InJvmCrossCheckTest). On the EDT the adoption is skipped
     * when the worker is in the middle of one (it is waiting for this very thread).
     */
    void adoptBeforeNative() {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            if (!adoptLock.tryLock()) return;
        } else {
            adoptLock.lock();
        }
        try {
            reconcile();
        } catch (RuntimeException e) {
            System.err.println("[fidelis-engine] adoption before a native command failed: " + e);
        } finally {
            adoptLock.unlock();
        }
    }

    /** Append a record created by an observer and tell listeners. */
    void recordObserved(HistoryRecord rec) {
        history.add(rec);
        events.emit(seq -> new EngineEvent.HistoryAppended(seq, rec));
    }

    /**
     * Follow-up of a native command: adopt new images, capture rows it added to the Results table, and take over
     * ROI Manager entries it added or removed ({@code managerBefore} is the count before the command, -1 for no
     * manager) into the target document's ROI list, so Analyze Particles run from Fiji's menu fills the same list
     * as the typed request (#5).
     */
    void afterNative(String actionId, Doc doc, int managerBefore) {
        try {
            worker.execute(() -> {
                try {
                    Registry.Change ch = reconcile();
                    results.captureNative(doc == null ? null : doc.id, actionId);
                    boolean roiChanged = false;
                    RoiManager rm = RoiManager.getRawInstance();
                    int managerAfter = rm == null ? -1 : rm.getCount();
                    if (doc != null && managerAfter >= 0 && managerAfter != managerBefore && registry.all().contains(doc)) {
                        roiLists.syncBack(doc);
                        roiChanged = true;
                    }
                    if (!ch.isEmpty() || roiChanged) {
                        List<DocumentId> added = new ArrayList<>();
                        for (Doc d : ch.added()) added.add(d.id);
                        List<DocumentId> removed = new ArrayList<>();
                        for (Doc d : ch.removed()) removed.add(d.id);
                        List<StateDigest.DocumentChange> changed = roiChanged
                                ? List.of(new StateDigest.DocumentChange(doc.id, false, false, false, true, false)) : List.of();
                        StateDigest digest = new StateDigest(added, removed, changed, 0, Map.of(), List.of(), List.of(), List.of());
                        events.emit(seq -> new EngineEvent.StateDigestEvent(seq, null, digest));
                    }
                } catch (Throwable t) {
                    System.err.println("[fidelis-engine] after native command: " + t);
                }
            });
        } catch (RuntimeException ignored) {
            // worker stopped
        }
    }

    /** Make the document IJ1 current image (EXP-02: a focus click on a re-hosted canvas does not do it). */
    void makeCurrent(Doc d) {
        Edt.run(() -> {
            ImageWindow w = d.win;
            if (w != null) WindowManager.setCurrentWindow(w);
        });
    }

    /** Window policy: registered documents keep hidden windows (a macro ending in setBatchMode(false) re-shows them). */
    void rehideWindows() {
        List<ImageWindow> wins = new ArrayList<>();
        for (Doc d : registry.all()) {
            ImageWindow w = d.win;
            if (w != null && w.isVisible()) wins.add(w);
        }
        if (wins.isEmpty()) return;
        Edt.run(() -> {
            for (ImageWindow w : wins) w.setVisible(false);
        });
    }

    private ActionResult runOp(Op op) {
        long t0 = System.nanoTime();
        if (op.cancelRequested) {
            Outcome o = new Outcome(OperationState.CANCELLED, EngineError.of(ErrorCode.CANCELLED, "cancelled before start"), null, null, null);
            return finish(op, o, t0);
        }
        Op.CURRENT.set(op);
        try {
            emitState(op, OperationState.VALIDATING, null);
            Outcome o = switch (op.request.kind()) {
                case SESSION -> runSession(op);
                case COMMAND -> runner.run(op, false);
                case DIRECT -> direct.runDirect(op);
                case CODE -> code.run(op);
            };
            return finish(op, o, t0);
        } finally {
            Op.CURRENT.remove();
            WindowManager.setTempCurrentImage(null); // the worker never keeps an implicit current image between operations
        }
    }

    void emitState(Op op, OperationState s, EngineError err) {
        events.emit(seq -> new EngineEvent.OperationStateChanged(seq, op.id, op.request.requestId(), s, err));
    }

    private ActionResult finish(Op op, Outcome o, long t0) {
        long ms = (System.nanoTime() - t0) / 1_000_000;
        emitState(op, o.state(), o.error());
        if (o.digest() != null) events.emit(seq -> new EngineEvent.StateDigestEvent(seq, op.id, o.digest()));
        if (o.record() != null) events.emit(seq -> new EngineEvent.HistoryAppended(seq, o.record()));
        return new ActionResult(op.id, o.state(), o.error(), o.digest(), o.record(), o.token(), ms);
    }

    // ---- session operations -----------------------------------------------------------------

    private Outcome runSession(Op op) {
        ActionRequest r = op.request;
        return switch (r.operation()) {
            case ActionRequest.Operations.OPEN -> sessionOpen(op);
            case ActionRequest.Operations.CLOSE -> sessionClose(op);
            case ActionRequest.Operations.SAVE_AS -> direct.saveAs(op);
            case ActionRequest.Operations.NEW_IMAGE -> direct.newImage(op);
            default -> Outcome.failed(ErrorCode.VALIDATION_FAILED, "unknown session operation " + r.operation());
        };
    }

    private Outcome sessionOpen(Op op) {
        ActionRequest r = op.request;
        Object p = r.params().get("path");
        if (!(p instanceof String path) || path.isBlank())
            return Outcome.failed(ErrorCode.VALIDATION_FAILED, "OPEN needs params.path");
        return openFile(op, Path.of(path));
    }

    Outcome openFile(Op op, Path path) {
        ActionRequest r = op.request;
        Path file = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) return Outcome.failed(ErrorCode.IO_ERROR, "file not found: " + file);
        Map<String, String> gBefore = Globals.capture();
        emitState(op, OperationState.RUNNING, null);
        Guard guard = Guard.begin(null);
        ImagePlus imp;
        try {
            imp = IJ.openImage(file.toString());
        } finally {
            Guard.Report rep = guard.end();
            if (rep.failed()) return Outcome.failed(ErrorCode.IO_ERROR, "ImageJ could not open " + file + ": " + rep.errorText());
        }
        if (imp == null) return Outcome.failed(ErrorCode.IO_ERROR, "ImageJ could not read the image: " + file);
        imp.changes = false;
        Doc d = registry.register(imp, file);
        focusDoc(d);
        events.emit(EngineEvent.DocumentsChanged::new);
        String actionId = history.nextId();
        HistoryRecord rec = History.record(actionId, r.origin(), Provenance.TYPED, ActionRequest.Operations.OPEN,
                "Open", openParams(file, d), null,
                List.of("open(\"" + Options.macroString(file.toString().replace('\\', '/')) + "\");"), true, gBefore, Map.of(),
                OperationState.COMPLETED, null);
        history.add(rec);
        StateDigest digest = new StateDigest(List.of(d.id), List.of(), List.of(), 0, Map.of(), List.of(), List.of(), List.of());
        return Outcome.ok(digest, rec);
    }

    private Outcome sessionClose(Op op) {
        ActionRequest r = op.request;
        TargetScope t = r.target();
        Doc d = t == null ? null : registry.get(t.document());
        if (d == null) return Outcome.failed(ErrorCode.PRECONDITION_FAILED, "no such open document");
        boolean discard = Boolean.TRUE.equals(r.params().get("discard"));
        if (!discard && d.imp.changes) {
            return Outcome.failed(new EngineError(ErrorCode.CONFIRMATION_REQUIRED, "document has unsaved changes",
                    "UNSAVED_CHANGES", Map.of("document", d.id.value()), true, List.of("close with discard=true")));
        }
        Map<String, String> gBefore = Globals.capture();
        emitState(op, OperationState.RUNNING, null);
        Edt.run(() -> closeDoc(d, discard));
        reconcile();
        String actionId = history.nextId();
        HistoryRecord rec = History.record(actionId, r.origin(), Provenance.TYPED, ActionRequest.Operations.CLOSE,
                "Close", Map.of("discard", discard, "document", d.id.value()), t, List.of("close();"), true, gBefore, Map.of(),
                OperationState.COMPLETED, null);
        history.add(rec);
        registry.remove(d);
        if (d.id.equals(focused)) focused = null;
        events.emit(EngineEvent.DocumentsChanged::new);
        StateDigest digest = new StateDigest(List.of(), List.of(d.id), List.of(), 0, Map.of(), List.of(), List.of(), List.of());
        return Outcome.ok(digest, rec);
    }

    private static void closeDoc(Doc d, boolean discard) {
        if (discard) d.imp.changes = false;
        d.imp.close();
        ImageWindow w = d.win;
        if (w != null && w.isDisplayable()) w.dispose();
    }

    // ---- documents --------------------------------------------------------------------------

    @Override
    public List<DocumentInfo> documents() {
        List<DocumentInfo> l = new ArrayList<>();
        for (Doc d : registry.all()) l.add(registry.info(d));
        return l;
    }

    @Override
    public Optional<DocumentInfo> document(DocumentId id) {
        Doc d = registry.get(id);
        return d == null ? Optional.empty() : Optional.of(registry.info(d));
    }

    @Override
    public CompletableFuture<ActionResult> open(Path file) {
        return submit(ActionRequest.session(sc.fidelis.engine.api.Enums.Origin.PANEL, ActionRequest.Operations.OPEN,
                Map.of("path", file.toString()), null));
    }

    @Override
    public Optional<DocumentId> focused() {
        return Optional.ofNullable(focused);
    }

    @Override
    public void focus(DocumentId id) {
        Doc d = registry.get(id);
        if (d != null) focusDoc(d);
    }

    private static Map<String, Object> openParams(Path file, Doc d) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("path", file.toString().replace('\\', '/'));
        p.put("document", d.id.value());
        if (d.sourceSha256 != null) p.put("sha256", d.sourceSha256);
        return p;
    }

    void focusDoc(Doc d) {
        Edt.run(() -> {
            ImageWindow w = d.win;
            if (w != null) WindowManager.setCurrentWindow(w);
        });
        DocumentId prev = focused;
        focused = d.id;
        if (!d.id.equals(prev)) events.emit(seq -> new EngineEvent.DocumentFocusChanged(seq, d.id));
    }

    @Override
    public ImagePlus imagePlus(DocumentId id) {
        Doc d = registry.get(id);
        return d == null ? null : d.imp;
    }

    @Override
    public ImageWindow hostWindow(DocumentId id) {
        Doc d = registry.get(id);
        return d == null ? null : d.win;
    }

    // ---- ROIs and results (E4) ---------------------------------------------------------------

    @Override
    public List<RoiGeometry> rois(DocumentId id) {
        Doc d = registry.get(id);
        return d == null ? List.of() : RoiLists.geometries(d);
    }

    @Override
    public Optional<RoiGeometry> activeRoi(DocumentId id) {
        Doc d = registry.get(id);
        if (d == null) return Optional.empty();
        Roi roi = d.imp.getRoi();
        return roi == null ? Optional.empty() : Optional.of(RoiCodec.toGeometry(roi));
    }

    @Override
    public List<MeasurementRow> results() {
        return results.all();
    }

    /**
     * Re-read the IJ1 system Results table after it was changed outside any engine action (a reset by other code):
     * a shorter table rebuilds the engine rows from row 0 (implementation-only; test isolation).
     */
    public void resyncResults() {
        results.markSeen();
    }

    @Override
    public MeasurementRow.Columns resultColumns() {
        return results.columns();
    }

    // ---- catalogue (E2) ---------------------------------------------------------------------

    @Override
    public List<CommandSchema.Summary> catalogue() {
        return catalogue.summaries();
    }

    @Override
    public Optional<CommandSchema> schema(String commandId) {
        return catalogue.find(commandId).map(e -> e.schema);
    }

    @Override
    public List<CommandSchema.Summary> search(String query, int limit) {
        return catalogue.search(query, limit);
    }

    // ---- history and globals ----------------------------------------------------------------

    @Override
    public List<HistoryRecord> history() {
        return history.list();
    }

    @Override
    public String exportMacro() {
        return MacroExport.generate(history.list(), sessionId, "Fiji runtime ImageJ " + IJ.getFullVersion());
    }

    /** The macro for the history from record {@code fromIndex} on: a slice of the session (verification uses it). */
    public String exportMacroSince(int fromIndex) {
        List<HistoryRecord> all = history.list();
        return MacroExport.generate(all.subList(Math.min(Math.max(0, fromIndex), all.size()), all.size()), sessionId,
                "Fiji runtime ImageJ " + IJ.getFullVersion());
    }

    @Override
    public Map<String, String> globals() {
        return Globals.capture();
    }

    @Override
    public long[] undoBudget() {
        return snapshots.budget();
    }

    // ---- reconcile scheduling ---------------------------------------------------------------

    /** Ask the worker to compare the registry with IJ1's window list soon (coalesced). */
    void scheduleReconcile() {
        if (reconcileQueued) return;
        reconcileQueued = true;
        try {
            worker.execute(() -> {
                reconcileQueued = false;
                try {
                    reconcile();
                } catch (Throwable t) {
                    System.err.println("[fidelis-engine] reconcile failed: " + t);
                }
            });
        } catch (RuntimeException e) {
            reconcileQueued = false;
        }
    }

    static boolean onEdt() {
        return SwingUtilities.isEventDispatchThread();
    }
}
