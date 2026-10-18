package sc.fidelis.penny;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;

import ij.ImagePlus;
import ij.process.ImageProcessor;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.CommandSchema.ParameterSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.ActionKind;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.RoiSignature;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.nl.ConfirmationPolicy;
import sc.fidelis.nl.NlService.Proposal;

/**
 * The bridge operations of {@code docs/FIDELIS-PROTOCOL.md} over the Fidelis {@link Engine}. Every change is a plan of
 * {@link ActionRequest}s submitted through {@link Engine#submit} (origin NL_SPOKEN or NL_TYPED, {@code transcriptRef} =
 * the gateway's task id), awaited one by one on the bridge's own plan thread (never the EDT), stopping at the first step
 * the engine does not report COMPLETED. Each plan is assessed by Fidelis's {@link ConfirmationPolicy}: explicit plans
 * (and every plan under {@code fidelis.penny.confirm=always}) are held until {@code confirm}; the rest run at once.
 *
 * <p>Read-only use of {@link Engine#imagePlus}: the current plane position, the inverted-LUT flag, the threshold levels
 * and the pixel range for {@code display auto}. Nothing is ever changed through it.
 */
final class PennyOps {
    static final List<String> OPERATIONS = List.of("context", "images", "open_path", "activate_image", "commands", "command",
            "segment_cells", "measure", "threshold", "analyze_particles", "filter", "convert", "duplicate", "select_roi", "rois",
            "results", "object_stats", "display", "stack", "zoom", "highlight", "undo", "redo", "history", "macro", "run_macro",
            "confirm", "cancel_plan", "dialog", "dialog_action");
    static final Set<String> READ = Set.of("context", "images", "commands", "rois", "results", "object_stats", "history", "macro", "dialog");
    /** Data, session and code operations: need {@code approved:true} and a matching {@code expectedContext}. */
    /** DIRECT operations that depend on the plane (pinned to the approved one; the engine applies a TargetScope plane). */
    static final Set<String> PLANE_DIRECT = Set.of(Operations.SET_THRESHOLD, Operations.SET_AUTO_THRESHOLD, Operations.RESET_THRESHOLD,
            Operations.SET_DISPLAY_RANGE);

    /** View operations that are engine plans (select_roi, display, stack); the other views never reach the engine. */
    static final Set<String> PLAN_VIEWS = Set.of("select_roi", "display", "stack");
    static final Set<String> MUTATING = Set.of("open_path", "command", "segment_cells", "measure", "threshold", "analyze_particles",
            "filter", "convert", "duplicate", "undo", "redo", "run_macro", "confirm", "dialog_action");

    static final List<String> THRESHOLD_METHODS = List.of("Default", "Huang", "Intermodes", "IsoData", "IJ_IsoData", "Li",
            "MaxEntropy", "Mean", "MinError", "Minimum", "Moments", "Otsu", "Percentile", "RenyiEntropy", "Shanbhag", "Triangle", "Yen");

    /** Columns segment_cells asks for when it has to set measurements (it only does so when Area is not recorded). */
    static final List<String> SEGMENT_MEASUREMENTS = List.of("area", "mean", "centroid", "bounding", "shape");

    static final long HTTP_WAIT_SECONDS = 115;
    /** Engine error codes that are decided before an operation touches anything. */
    static final Set<sc.fidelis.engine.api.Enums.ErrorCode> PRE_EXECUTION = Set.of(sc.fidelis.engine.api.Enums.ErrorCode.VALIDATION_FAILED,
            sc.fidelis.engine.api.Enums.ErrorCode.PRECONDITION_FAILED, sc.fidelis.engine.api.Enums.ErrorCode.UNKNOWN_COMMAND,
            sc.fidelis.engine.api.Enums.ErrorCode.ENGINE_NOT_READY, sc.fidelis.engine.api.Enums.ErrorCode.CONFIRMATION_REQUIRED,
            sc.fidelis.engine.api.Enums.ErrorCode.CAPABILITY_CONFIRMATION_REQUIRED);
    static final long PENDING_TTL_MS = 15 * 60_000L;
    static final int HIGHLIGHT_LIMIT = 120;

    enum ConfirmMode { AUTO, ALWAYS }

    /** One {@code /execute} call; {@code expectedContext} is the contextKey the caller approved (null for reads/views without one). */
    record Call(String operation, JsonObject args, boolean spoken, String taskId, String utterance, String expectedContext) {}

    /** A dialog the engine reported for one of a plan's operations (EngineEvent.DialogPending). */
    record PendingDialog(String operationId, String title, String dialogClass, long generation) {
        String id() {
            return "dlg-" + generation;
        }
    }

    private final java.util.concurrent.atomic.AtomicLong dialogGeneration = new java.util.concurrent.atomic.AtomicLong();

    /** A plan admitted for execution and the future of its run on the plan thread. */
    record Running(Plan plan, Future<Map<String, Object>> future) {}

    /** A refusal or failure with its HTTP status and JSON body ({@code error} plus details). */
    static final class Failure extends RuntimeException {
        final int status;
        final Map<String, Object> body;

        Failure(int status, String message, Map<String, Object> extra) {
            super(message);
            this.status = status;
            this.body = new LinkedHashMap<>();
            body.put("error", message);
            if (extra != null) body.putAll(extra);
        }

        Failure(int status, String message) {
            this(status, message, null);
        }
    }

    /** One planned engine request. {@code role} lets the result builder find the step's result. */
    record Step(String role, String label, String menuPath, ActionRequest request, Map<String, String> provenance, String preview,
                java.util.function.Function<List<ActionResult>, ActionRequest> late) {
        /** {@code request} is what the policy assesses and the card shows; {@code late}, when set, builds the request that is
         * actually submitted from the earlier steps' results (a step on a document an earlier step creates). */
        Step(String role, String label, String menuPath, ActionRequest request, Map<String, String> provenance, String preview) {
            this(role, label, menuPath, request, provenance, preview, null);
        }
    }

    interface Finisher {
        Map<String, Object> finish(Plan plan, List<ActionResult> results);
    }

    static final class Plan {
        final String id = "plan-" + UUID.randomUUID().toString().substring(0, 8);
        final Call call;
        final Origin origin;
        final String transcriptRef;
        final DocumentId doc;
        final List<Step> steps = new ArrayList<>();
        final List<String> engineReasons = new ArrayList<>();
        final long created = System.currentTimeMillis();
        Finisher finish = (p, r) -> new LinkedHashMap<>();
        boolean explicit;
        int undoSteps;
        /** History size when the plan was approved (its contextKey includes it). */
        int approvedHistory = -1;
        /** RoiSignature of the plan document's selection when the plan was approved; the first step is bound to it. */
        String approvedRoi;
        /** Request ids submitted to the engine (for the event bookkeeping). */
        final List<String> submitted = new ArrayList<>();
        /** The approved plane of a stack document ({c, z, t}); COMMAND steps are pinned to it. Null for single planes. */
        int[] position;
        /** The original Fiji dialog the plan's current engine operation waits on, set from the engine's events. */
        volatile PendingDialog dialog;
        /** The running plan's future, for dialog_action to wait on again. */
        volatile Running handle;
        /** Index of the step being run (for dialog reports). */
        volatile int current = -1;
        /** Title of a dialog Penny pressed Cancel on (the command then ends as cancelled, not as a failure). */
        volatile String cancelledDialog;
        /** OS file choosers the bridge cancelled on the plan's behalf (a command that wants to save a file mid-way or at its end). */
        final List<String> cancelledFileDialogs = Collections.synchronizedList(new ArrayList<>());
        /** The exact window of {@link #dialog} once Penny reported it (dialog_action acts on this window only). */
        volatile java.awt.Window dialogWindow;
        /** cancel_plan on this running plan: the step being run is cancelled in the engine and no further step starts. */
        volatile boolean stopRequested;
        /** The engine operation of the step being run (from the engine's events), for {@link Engine#cancel}. */
        volatile String currentOperationId;
        /** {@link Engine#cancel} was issued for the current step (once per step). */
        volatile boolean cancelIssued;
        List<String> reasons = List.of();
        String contextKey;
        String codeToken;

        Plan(Call call, DocumentId doc) {
            this.call = call;
            this.origin = call.spoken() ? Origin.NL_SPOKEN : Origin.NL_TYPED;
            this.transcriptRef = call.taskId() == null || call.taskId().isBlank() ? "penny-" + id : call.taskId();
            this.doc = doc;
        }

        int index(String role) {
            for (int i = 0; i < steps.size(); i++) if (role.equals(steps.get(i).role())) return i;
            return -1;
        }

        List<Integer> indices(String role) {
            List<Integer> l = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) if (role.equals(steps.get(i).role())) l.add(i);
            return l;
        }

        List<String> labels() {
            return steps.stream().map(Step::label).toList();
        }
    }

    private final Engine engine;
    private final ConfirmMode mode;
    private final ConfirmationPolicy policy;
    private final ExecutorService planRunner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fidelis-penny-plan");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Plan> pending = new AtomicReference<>();
    private final AtomicReference<Plan> running = new AtomicReference<>();
    /**
     * Serialises admission: the context check, plan construction, the confirmation decision and the reservation of the
     * plan thread happen under this lock, so no other bridge request (or workspace Confirm) can interleave. Waiting for a
     * running plan happens outside it.
     */
    private final Object admission = new Object();
    /** Engine requests of Penny's plans by request id, and their operations by operation id (from the engine's events). */
    private final Map<String, Plan> byRequest = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Plan> byOperation = new java.util.concurrent.ConcurrentHashMap<>();
    /** Engine operations queued or running that are not Penny's (the workspace's, a native command's). */
    private final Set<String> foreignOps = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final sc.fidelis.engine.api.EngineListener listener = this::onEvent;
    /** The last plan discarded without running, and why ({planId, reason}), so a late confirm can say so. */
    private final AtomicReference<String[]> discarded = new AtomicReference<>();

    PennyOps(Engine engine, ConfirmMode mode) {
        this.engine = engine;
        this.mode = mode;
        this.policy = new ConfirmationPolicy(engine::schema, engine::globals);
        engine.addListener(listener);
    }

    private static boolean terminal(OperationState s) {
        return s == OperationState.COMPLETED || s == OperationState.FAILED || s == OperationState.CANCELLED
                || s == OperationState.ROLLED_BACK || s == OperationState.PARTIAL || s == OperationState.AWAITING_CONFIRMATION;
    }

    /** Engine events (on the EDT, must not block): which operations are Penny's, their dialogs, and foreign activity. */
    private void onEvent(sc.fidelis.engine.api.EngineEvent e) {
        if (e instanceof sc.fidelis.engine.api.EngineEvent.OperationStateChanged sc) {
            Plan p = sc.requestId() == null ? null : byRequest.get(sc.requestId());
            if (p != null) {
                byOperation.put(sc.operationId(), p);
                // the operation a stop has to cancel; the plan thread issues the cancel once the id is known
                if (terminal(sc.state())) {
                    if (sc.operationId().equals(p.currentOperationId)) p.currentOperationId = null;
                } else {
                    if (!sc.operationId().equals(p.currentOperationId)) p.cancelIssued = false;
                    p.currentOperationId = sc.operationId();
                }
                if (sc.state() == OperationState.RUNNING || terminal(sc.state())) {
                    PendingDialog d = p.dialog;
                    if (d != null && d.operationId().equals(sc.operationId())) p.dialog = null;
                }
            } else if (byOperation.get(sc.operationId()) == null) {
                if (terminal(sc.state())) foreignOps.remove(sc.operationId());
                else foreignOps.add(sc.operationId());
            }
        } else if (e instanceof sc.fidelis.engine.api.EngineEvent.DialogPending dp) {
            Plan p = dp.operationId() == null ? null : byOperation.get(dp.operationId());
            if (p != null) {
                p.dialog = new PendingDialog(dp.operationId(), dp.windowTitle(), dp.dialogClass(), dialogGeneration.incrementAndGet());
                p.dialogWindow = null;
            }
        }
    }

    ConfirmMode mode() {
        return mode;
    }

    void shutdown() {
        engine.removeListener(listener);
        planRunner.shutdownNow();
    }

    // =============================================================================================
    // dispatch
    // =============================================================================================

    Map<String, Object> execute(Call c) {
        JsonObject a = c.args();
        switch (c.operation()) {
            case "context" -> { return context(); }
            case "images" -> { return images(); }
            case "commands" -> { return commands(a); }
            case "rois" -> { return roiList(a); }
            case "results" -> { return resultsTable(a); }
            case "object_stats" -> { return objectStats(); }
            case "history" -> { return history(a); }
            case "macro" -> { return macro(); }
            case "dialog" -> { return dialogRead(); }
            default -> { }
        }
        Object admitted;
        synchronized (admission) {
            if (c.expectedContext() != null && !c.expectedContext().equals(contextKey()))
                throw new Failure(409, "Fidelis image, selection, results or history changed. Review the action again.",
                        Map.of("contextKey", contextKey()));
            admitted = admit(c);
        }
        if (admitted instanceof Running r) return await(r);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) admitted;
        return m;
    }

    /** Under {@link #admission}: an immediate result (view operations, a held plan) or a {@link Running} plan. */
    private Object admit(Call c) {
        JsonObject a = c.args();
        Plan busy = running.get();
        if (busy != null && MUTATING.contains(c.operation()) && !c.operation().equals("confirm") && !c.operation().equals("dialog_action")
                || busy != null && PLAN_VIEWS.contains(c.operation()))
            throw new Failure(409, "Penny is still running " + busy.call.operation() + " (" + busy.id + ") in Fidelis; wait for it to finish.",
                    Map.of("planId", busy.id, "running", true));
        return switch (c.operation()) {
            case "activate_image" -> activate(a);
            case "zoom" -> zoom(a);
            case "highlight" -> highlight(a);
            case "cancel_plan" -> cancel(PennyJson.str(a, "planId", null));
            case "confirm" -> admitConfirm(PennyJson.str(a, "planId", null));
            case "dialog_action" -> admitDialogAction(a);
            case "open_path" -> run(openPath(c));
            case "command" -> run(command(c));
            case "segment_cells" -> run(segmentCells(c));
            case "measure" -> run(measure(c));
            case "threshold" -> run(threshold(c));
            case "analyze_particles" -> run(analyzeParticles(c));
            case "filter" -> run(filter(c));
            case "convert" -> run(convert(c));
            case "duplicate" -> run(duplicate(c));
            case "select_roi" -> run(selectRoi(c));
            case "display" -> run(display(c));
            case "stack" -> run(stack(c));
            case "undo" -> run(undoRedo(c, Operations.UNDO));
            case "redo" -> run(undoRedo(c, Operations.REDO));
            case "run_macro" -> run(runMacro(c));
            default -> throw new IllegalArgumentException("Unknown operation: " + c.operation());
        };
    }

    // =============================================================================================
    // plans: assessment, holding, running
    // =============================================================================================

    private Object run(Plan p) {
        assess(p);
        p.approvedHistory = engine.history().size();
        p.contextKey = contextKey();
        p.approvedRoi = p.doc == null ? null : RoiSignature.of(engine.activeRoi(p.doc).orElse(null));
        // the workspace may have changed focus or selection while the plan was being built
        if (p.call.expectedContext() != null && !p.call.expectedContext().equals(p.contextKey))
            throw new Failure(409, "Fidelis image, selection, results or history changed while the action was being prepared. Review it again.",
                    Map.of("contextKey", p.contextKey));
        if (p.explicit || mode == ConfirmMode.ALWAYS) return hold(p);
        return reserve(p, false);
    }

    /** Steps the policy does not assess: the plan's own clean-up of a document it created. */
    static final Set<String> HOUSEKEEPING_ROLES = Set.of("closeMask");
    /** Steps the engine can undo without a pixel change: ROI-list edits and the commands that add or clear Results rows. */
    static final Set<String> RESTORABLE_DIRECT = Set.of(Operations.ROI_LIST_ADD, Operations.ROI_LIST_DELETE, Operations.ROI_LIST_RENAME);
    static final Set<String> RESTORABLE_COMMANDS = Set.of(PennyCommands.MEASURE, PennyCommands.ANALYZE_PARTICLES, "ij1:Analyze>Clear Results");

    /** The channel the last segment_cells plan segmented, per document: intensities of the objects come from it. */
    private final Map<DocumentId, Integer> segmentedChannel = new java.util.concurrent.ConcurrentHashMap<>();

    /** Fidelis's own policy on the plan as a READY {@link Proposal}; the bridge adds the engine's capability reasons. */
    private void assess(Plan p) {
        List<ActionRequest> reqs = new ArrayList<>();
        List<String> menus = new ArrayList<>();
        List<String> previews = new ArrayList<>();
        List<Map<String, String>> prov = new ArrayList<>();
        for (Step s : p.steps) {
            // tidying up a document the plan itself created (segment_cells closing its mask) is the bridge's own
            // housekeeping, not a change the researcher must confirm; the engine still records it in History
            if (HOUSEKEEPING_ROLES.contains(s.role())) continue;
            reqs.add(s.request());
            menus.add(s.menuPath());
            previews.add(s.preview());
            prov.add(s.provenance());
        }
        String text = p.call.utterance() == null || p.call.utterance().isBlank() ? p.call.operation() : p.call.utterance();
        Proposal proposal = new Proposal(Proposal.Status.READY, text, reqs, menus, previews, prov, List.of(), null);
        ConfirmationPolicy.Decision d = policy.assess(proposal);
        List<String> reasons = new ArrayList<>(d.reasons());
        reasons.addAll(p.engineReasons);
        p.explicit = d.explicit() || !p.engineReasons.isEmpty();
        p.reasons = List.copyOf(reasons);
    }

    private void discard(Plan old, String reason) {
        discarded.set(new String[] {old.id, reason});
        ui(() -> PennyUi.current().clearPlan(old.id));
    }

    private Map<String, Object> hold(Plan p) {
        Plan old = pending.getAndSet(p);
        if (old != null && old != p) discard(old, "a newer plan (" + p.call.operation() + ") replaced it");
        ui(() -> PennyUi.current().showPlan(card(p), false));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "awaiting_confirmation");
        out.put("planId", p.id);
        out.put("operation", p.call.operation());
        out.put("reasons", p.reasons);
        if (p.reasons.isEmpty() && mode == ConfirmMode.ALWAYS) out.put("policy", "fidelis.penny.confirm=always: every plan waits for confirmation");
        out.put("steps", p.labels());
        out.put("macroPreview", p.steps.stream().map(Step::preview).toList());
        out.put("document", p.doc == null ? null : p.doc.value());
        out.put("contextKey", p.contextKey);
        out.put("note", "Nothing has run. Say yes (confirm) or press Confirm and run in Fidelis; cancel_plan discards it.");
        return out;
    }

    private static boolean blockedFor(Running run, long ms) {
        try {
            run.future().get(ms, TimeUnit.MILLISECONDS);
            return false;
        } catch (TimeoutException e) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Under {@link #admission}: reserve the plan thread and start the plan there. */
    private Running reserve(Plan p, boolean confirmed) {
        if (!running.compareAndSet(null, p)) {
            Plan r = running.get();
            throw new Failure(409, "Penny is still running a plan in Fidelis" + (r == null ? "" : " (" + r.call.operation() + ", " + r.id + ")")
                    + "; wait for it to finish.", Map.of("planId", r == null ? "" : r.id));
        }
        Plan old = pending.get();
        if (old != null && old != p && pending.compareAndSet(old, null))
            discard(old, "another action (" + p.call.operation() + ") ran after it, so the image, selection or results changed");
        ui(() -> PennyUi.current().showPlan(card(p), true));
        try {
            Running r = new Running(p, planRunner.submit(() -> runSteps(p, confirmed)));
            p.handle = r;
            return r;
        } catch (RuntimeException e) {
            running.compareAndSet(p, null);
            throw new Failure(409, "The Penny plan runner is stopped.");
        }
    }

    /** Wait (bounded) for a running plan; it keeps running if the wait times out. */
    private Map<String, Object> await(Running run) {
        Plan p = run.plan();
        long deadline = System.currentTimeMillis() + HTTP_WAIT_SECONDS * 1000;
        try {
            while (true) {
                try {
                    return run.future().get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    PendingDialog d = p.dialog;
                    if (d != null) {
                        java.awt.Window w = DialogDriver.find(d.title(), d.dialogClass());
                        // a modal dialog blocks the command; a non-modal tool window (Brightness/Contrast) does not, unless the
                        // command is still waiting half a second later (a non-blocking dialog the plugin waits on)
                        // a file chooser is never offered to the caller: the plan thread cancels it (autoCancelFileDialog)
                        if (w != null && !DialogDriver.isFileChooser(w)
                                && (DialogDriver.onEdt(() -> ((java.awt.Dialog) w).isModal()) || blockedFor(run, 500)))
                            return dialogResult(p, w, null);
                    }
                    if (System.currentTimeMillis() > deadline) throw e;
                }
            }
        } catch (TimeoutException e) {
            throw new Failure(409, "Still running in Fidelis after " + HTTP_WAIT_SECONDS + " s (a dialog may be waiting there); "
                    + "check history before retrying.", Map.of("planId", p.id, "running", true));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(409, "Interrupted while waiting for Fidelis.");
        } catch (ExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof Failure fail) throw fail;
            if (t instanceof IllegalArgumentException iae) throw iae;
            throw new Failure(409, "The plan failed: " + t, Map.of("planId", p.id));
        }
    }

    private Map<String, Object> runSteps(Plan p, boolean confirmed) {
        List<ActionResult> results = new ArrayList<>();
        try {
            Set<String> ours = new LinkedHashSet<>();
            int cursor = -1;
            for (int i = 0; i < p.steps.size(); i++) {
                Step s = p.steps.get(i);
                final int index = i;
                // a stop (cancel_plan on this plan) between steps: nothing further starts
                if (p.stopRequested) return stopped(p, i, results, null);
                // the approval binds the whole plan: let foreign engine work finish, then make sure nothing else acted
                // on Fidelis since the plan was approved (or since its previous step)
                waitForForeignOps(p, i, results);
                if (p.stopRequested) return stopped(p, i, results, null);
                // the baseline is taken before validation: anything recorded after it makes the engine refuse the step
                int baseline = engine.history().size();
                if (i == 0) {
                    if (baseline != p.approvedHistory || !contextKey().equals(p.contextKey))
                        throw stepFailure(p, i, null, results, "the image, selection, results or history changed after the plan was approved", true);
                } else {
                    // every record since the previous step was submitted must be this plan's (its step and that step's children)
                    for (HistoryRecord h : sinceMark(cursor, baseline)) {
                        if (!mine(p, ours, h))
                            throw stepFailure(p, i, null, results, "another action (" + h.label() + ", " + h.origin() + ") ran in Fidelis after step " + i
                                    + "; the plan stopped so it does not act on a changed image", true);
                    }
                }
                ui(() -> PennyUi.current().planProgress(p.id, index, s.label()));
                p.current = i;
                ActionRequest r = s.late() != null ? s.late().apply(results) : s.request();
                if (r.kind() == ActionKind.CODE && p.codeToken != null) r = r.withConfirmation(p.codeToken);
                cursor = baseline;
                // the engine refuses the step (STATE_CHANGED) if anything else enters the history before its worker starts it
                Map<String, Object> bound = new LinkedHashMap<>(r.params());
                bound.put("expectedHistorySize", cursor);
                // ... and (STATE_CHANGED too) if the target's selection is not the one this step was planned against: the
                // approved selection for the first step, the selection as the previous step left it for the rest. Canvas
                // edits are recorded late, so the history check alone misses a selection drawn just before a step runs.
                DocumentId target = r.target() == null ? null : r.target().document();
                if (target != null) {
                    String expectedRoi = i == 0 && target.equals(p.doc) && p.approvedRoi != null ? p.approvedRoi
                            : RoiSignature.of(engine.activeRoi(target).orElse(null));
                    bound.put("expectedRoi", expectedRoi);
                }
                r = new ActionRequest(r.requestId(), r.origin(), r.kind(), r.commandId(), r.operation(), bound, r.target(),
                        r.confirmationToken(), r.replayOf());
                byRequest.put(r.requestId(), p);
                p.submitted.add(r.requestId());
                ActionResult res;
                try {
                    // no timeout: the plan owns the bridge until the engine reports a terminal state (an original Fiji
                    // dialog waits for the user); releasing earlier would admit new plans behind a still-running action.
                    // Polled, so an OS file chooser the command opens (Coloc 2 ends with "Save as PDF") is cancelled at once.
                    Future<ActionResult> pending = engine.submit(r);
                    while (true) {
                        try {
                            res = pending.get(100, TimeUnit.MILLISECONDS);
                            break;
                        } catch (TimeoutException wait) {
                            autoCancelFileDialog(p);
                            // a stop that arrived before the engine named this step's operation: cancel it now that it has
                            if (p.stopRequested && !p.cancelIssued) {
                                String opId = p.currentOperationId;
                                if (opId != null) {
                                    p.cancelIssued = true;
                                    engine.cancel(opId);
                                }
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw stepFailure(p, i, null, results, "interrupted", false);
                } catch (ExecutionException e) {
                    throw stepFailure(p, i, null, results, String.valueOf(e.getCause()), false);
                }
                results.add(res);
                if (res.record() != null) ours.add(res.record().actionId());
                if (!res.succeeded() && p.cancelledDialog == null && !p.cancelledFileDialogs.isEmpty()) {
                    // the command ended when the bridge cancelled its file chooser: not done, and not a failure of Penny's
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("status", "cancelled");
                    out.put("planId", p.id);
                    out.put("operation", p.call.operation());
                    out.put("dialog", p.cancelledFileDialogs.get(p.cancelledFileDialogs.size() - 1));
                    out.put("cancelledFileDialog", true);
                    out.put("cancelledFileDialogs", List.copyOf(p.cancelledFileDialogs));
                    out.put("state", String.valueOf(res.state()));
                    out.put("engineMessage", res.error() == null ? null : res.error().message());
                    out.put("step", step(s, res));
                    List<Map<String, Object>> done = new ArrayList<>();
                    for (int k = 0; k < i; k++) done.add(step(p.steps.get(k), results.get(k)));
                    out.put("completedSteps", done);
                    out.put("note", "Fiji wanted to save a file (" + String.join(", ", p.cancelledFileDialogs) + "); Penny does not drive OS file "
                            + "dialogs (use export instead), so it was cancelled and " + s.label() + " ended there"
                            + (i + 1 < p.steps.size() ? "; the remaining steps did not run." : "."));
                    out.put("contextKey", contextKey());
                    ui(() -> PennyUi.current().planFinished(p.id, false, "cancelled"));
                    return out;
                }
                if (!res.succeeded() && p.cancelledDialog != null) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("status", "cancelled");
                    out.put("planId", p.id);
                    out.put("operation", p.call.operation());
                    out.put("dialog", p.cancelledDialog);
                    out.put("state", String.valueOf(res.state()));
                    out.put("engineMessage", res.error() == null ? null : res.error().message());
                    out.put("step", step(s, res));
                    List<Map<String, Object>> done = new ArrayList<>();
                    for (int k = 0; k < i; k++) done.add(step(p.steps.get(k), results.get(k)));
                    out.put("completedSteps", done);
                    out.put("note", "The Fiji dialog '" + p.cancelledDialog + "' was cancelled, so " + s.label() + " did not finish"
                            + (i + 1 < p.steps.size() ? " and the remaining steps did not run." : "."));
                    out.put("contextKey", contextKey());
                    ui(() -> PennyUi.current().planFinished(p.id, false, "cancelled"));
                    return out;
                }
                // stopped while this step ran (it may have completed first, or the engine cancelled it): nothing further runs
                if (p.stopRequested || res.state() == OperationState.CANCELLED) return stopped(p, i, results, res);
                if (!res.succeeded()) throw stepFailure(p, i, res, results, null, false);
            }
            int undoable = 0;
            for (int i = 0; i < results.size(); i++) {
                ActionResult r = results.get(i);
                if (r.digest() == null || r.record() == null || "UNDO".equals(r.record().commandId())) continue;
                ActionRequest q = p.steps.get(i).request();
                // the engine's legacy route (no catalogued schema) keeps no undo snapshot
                if (q.kind() == ActionKind.COMMAND && !engine.schema(q.commandId()).map(CommandSchema::schemaComplete).orElse(false)) continue;
                boolean px = false;
                for (var ch : r.digest().changed()) px |= (p.doc == null || ch.id().equals(p.doc)) && (ch.pixelsChanged() || ch.typeChanged());
                // ROI-list and Results changes on the plan's document are restorable too (engine snapshots of 18 Oct)
                // the request actually submitted may have been retargeted to a document an earlier step created (onCreated)
                TargetScope ranOn = r.record() != null && r.record().target() != null ? r.record().target() : q.target();
                DocumentId on = ranOn == null ? null : ranOn.document();
                boolean onDoc = p.doc == null || p.doc.equals(on);
                boolean state = onDoc && (q.kind() == ActionKind.DIRECT && RESTORABLE_DIRECT.contains(q.operation())
                        || q.kind() == ActionKind.COMMAND && RESTORABLE_COMMANDS.contains(q.commandId()));
                if (px || state) undoable++;
            }
            // results are read from the live mirrors: if another action ran after the plan, they may describe it instead
            waitForForeignOpsQuietly();
            int before = engine.history().size();
            boolean foreign = !foreignOps.isEmpty() || sinceMark(cursor, before).stream().anyMatch(h -> !mine(p, ours, h));
            boolean virtual = p.doc != null && engine.document(p.doc).map(DocumentInfo::virtualStack).orElse(false);
            p.undoSteps = virtual ? 0 : undoable;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "completed");
            out.put("planId", p.id);
            out.put("operation", p.call.operation());
            if (confirmed) out.put("confirmed", true);
            if (!foreign) {
                Map<String, Object> fin = p.finish.finish(p, results);
                if (!foreignOps.isEmpty() || engine.history().size() != before
                        || sinceMark(cursor, engine.history().size()).stream().anyMatch(h -> !mine(p, ours, h))) foreign = true;
                else out.putAll(fin);
            }
            if (foreign) {
                out.put("statisticsWithheld", true);
                out.put("note", "Every step completed, but another action ran in Fidelis right after this plan, so the Results and ROI "
                        + "list may describe it; read results or rois again.");
            }
            if (!p.cancelledFileDialogs.isEmpty()) {
                out.put("cancelledFileDialog", true);
                out.put("cancelledFileDialogs", List.copyOf(p.cancelledFileDialogs));
                String tidy = "Fiji wanted to save a file (" + String.join(", ", p.cancelledFileDialogs)
                        + "); Penny does not drive OS file dialogs, use export instead.";
                out.put("note", out.get("note") == null ? tidy : out.get("note") + " " + tidy);
            }
            List<Map<String, Object>> steps = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) steps.add(step(p.steps.get(i), results.get(i)));
            out.put("steps", steps);
            out.put("undoSteps", p.undoSteps);
            out.put("contextKey", contextKey());
            String summary = out.containsKey("count") ? out.get("count") + " found" : "done";
            ui(() -> PennyUi.current().planFinished(p.id, true, summary));
            return out;
        } catch (Failure f) {
            ui(() -> PennyUi.current().planFinished(p.id, false, String.valueOf(f.getMessage())));
            throw f;
        } catch (RuntimeException e) {
            ui(() -> PennyUi.current().planFinished(p.id, false, String.valueOf(e)));
            throw new Failure(409, "The plan stopped: " + e, Map.of("planId", p.id));
        } finally {
            p.dialog = null;
            for (String id : p.submitted) byRequest.remove(id);
            byOperation.values().removeIf(x -> x == p);
            running.compareAndSet(p, null);
        }
    }

    /**
     * An OS file chooser a command opens during or at the end of its run (Coloc 2 finishes with "Save as PDF"): pressed
     * Cancel at once and recorded on the plan, because Penny does not drive OS file dialogs (the up-front
     * {@link #opensFileChooser} refusal covers commands whose first act is one). Called from the plan thread while a step runs.
     */
    private void autoCancelFileDialog(Plan p) {
        PendingDialog d = p.dialog;
        if (d == null) return;
        java.awt.Window w;
        try {
            w = DialogDriver.find(d.title(), d.dialogClass());
        } catch (RuntimeException e) {
            return;
        }
        if (!DialogDriver.isFileChooser(w)) return;
        String title = d.title() == null || d.title().isBlank() ? "file chooser" : d.title();
        try {
            DialogDriver.act(w, Map.of(), "Cancel");
        } catch (RuntimeException e) {
            System.err.println("[fidelis-penny] could not cancel the file chooser '" + title + "': " + e);
            return;
        }
        p.cancelledFileDialogs.add(title);
        p.dialog = null;
        p.dialogWindow = null;
    }

    record ChannelChoice(int channel, String by) {}

    /**
     * The channel "the nuclei" means on a multichannel image when none is given: a blue-LUT channel (DAPI/Hoechst are shown
     * blue by convention), else a channel labelled DAPI/Hoechst/nuc, else the current channel; {@code by} says which rule
     * chose it. Read-only: composite LUTs and slice labels, never {@code getProcessor()}/{@code getStack()}.
     */
    static ChannelChoice nucleiChannel(ImagePlus imp, int channels) {
        if (imp == null) return new ChannelChoice(1, "current");
        if (channels <= 1) return new ChannelChoice(Math.max(1, imp.getC()), "current");
        if (imp instanceof ij.CompositeImage ci) {
            for (int c = 1; c <= channels; c++) {
                ij.process.LUT lut = ci.getChannelLut(c);
                if (lut != null && "blue".equals(lutName(lut))) return new ChannelChoice(c, "lut-blue");
            }
        }
        for (int c = 1; c <= channels; c++) {
            String label = LiveRead.sliceLabel(imp, c);
            if (label != null && label.matches("(?is).*(dapi|hoechst|nuc).*")) return new ChannelChoice(c, "label");
        }
        return new ChannelChoice(Math.max(1, imp.getC()), "current");
    }

    /** The colour a LUT displays at full intensity: red, green, blue, cyan, magenta, yellow, grays, black or other. */
    static String lutName(ij.process.LUT lut) {
        int r = lut.getRed(255), g = lut.getGreen(255), b = lut.getBlue(255);
        int max = Math.max(r, Math.max(g, b));
        if (max < 32) return "black";
        boolean red = r > 0.6 * max, green = g > 0.6 * max, blue = b > 0.6 * max;
        if (red && green && blue) return "grays";
        if (red && green) return "yellow";
        if (red && blue) return "magenta";
        if (green && blue) return "cyan";
        return red ? "red" : green ? "green" : blue ? "blue" : "other";
    }

    private List<HistoryRecord> sinceMark(int from, int to) {
        List<HistoryRecord> h = engine.history();
        int end = Math.min(to, h.size());
        return from < 0 || from >= end ? List.of() : h.subList(from, end);
    }

    private static boolean mine(Plan p, Set<String> ours, HistoryRecord h) {
        return p.transcriptRef.equals(h.transcriptRef()) || ours.contains(h.actionId())
                || (h.params() != null && ours.contains(String.valueOf(h.params().get("parentActionId"))));
    }

    private void waitForForeignOpsQuietly() {
        long end = System.currentTimeMillis() + 5_000;
        while (!foreignOps.isEmpty() && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * The plan was told to stop ({@link #cancel} on a running plan) at step {@code i}: {@code res} is that step's result when
     * it had been submitted (completed before the stop landed, or cancelled by the engine), null when it never started.
     * Everything that ran stays in History; the result says so, step by step, and names documents the plan created.
     */
    private Map<String, Object> stopped(Plan p, int i, List<ActionResult> results, ActionResult res) {
        Step s = p.steps.get(i);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "cancelled");
        out.put("planId", p.id);
        out.put("operation", p.call.operation());
        out.put("stopped", true);
        List<Map<String, Object>> done = new ArrayList<>();
        for (int k = 0; k < i && k < results.size(); k++) done.add(step(p.steps.get(k), results.get(k)));
        out.put("completedSteps", done);
        String at;
        if (res == null) {
            at = "Step " + (i + 1) + " (" + s.label() + ") was not started";
        } else {
            out.put("step", step(s, res));
            out.put("state", String.valueOf(res.state()));
            if (res.error() != null) out.put("engineMessage", res.error().message());
            at = "Step " + (i + 1) + " (" + s.label() + ") " + (res.state() == OperationState.CANCELLED ? "was cancelled in the engine"
                    : res.state() == OperationState.ROLLED_BACK ? "was rolled back" : res.succeeded() ? "had completed" : "ended in " + res.state());
        }
        int remaining = p.steps.size() - (res == null ? i : i + 1);
        List<String> created = new ArrayList<>();
        for (ActionResult x : results) {
            if (x.digest() == null) continue;
            for (DocumentId id : x.digest().added()) engine.document(id).ifPresent(d -> created.add(d.title()));
        }
        out.put("note", "Stopped at the researcher's request. " + at + (remaining > 0 ? "; the remaining " + remaining + " step(s) did not run." : ".")
                + (done.isEmpty() ? "" : " " + done.size() + " earlier step(s) completed and stay in History (undoable there).")
                + (created.isEmpty() ? "" : " Documents the plan created stay open: " + String.join(", ", created) + "."));
        out.put("contextKey", contextKey());
        ui(() -> PennyUi.current().planFinished(p.id, false, "stopped"));
        return out;
    }

    /** Wait (up to 30 s) until no engine operation other than Penny's is queued or running, or the plan is told to stop. */
    private void waitForForeignOps(Plan p, int i, List<ActionResult> results) {
        long end = System.currentTimeMillis() + 30_000;
        while (!foreignOps.isEmpty()) {
            if (p.stopRequested) return;
            if (System.currentTimeMillis() > end)
                throw stepFailure(p, i, null, results, "Fidelis is still busy with another action (" + foreignOps.size() + " operation(s))", true);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw stepFailure(p, i, null, results, "interrupted", true);
            }
        }
    }

    private Failure stepFailure(Plan p, int i, ActionResult res, List<ActionResult> done, String why, boolean notStarted) {
        Step s = p.steps.get(i);
        String msg = why != null ? why : res.error() != null ? res.error().message() : "ended in " + res.state();
        String text = p.steps.size() == 1 ? msg : "Step " + (i + 1) + " of " + p.steps.size() + " (" + s.label() + ") did not complete: " + msg;
        Map<String, Object> extra = new LinkedHashMap<>();
        if (res != null) {
            extra.put("code", res.error() == null ? null : String.valueOf(res.error().code()));
            extra.put("subcode", res.error() == null ? null : res.error().subcode());
            extra.put("suggestions", res.error() == null ? List.of() : res.error().suggestions());
            extra.put("state", String.valueOf(res.state()));
        }
        extra.put("planId", p.id);
        extra.put("failedStep", i + 1);
        extra.put("failedLabel", s.label());
        List<Map<String, Object>> completed = new ArrayList<>();
        for (int k = 0; k < done.size() && k < i; k++) completed.add(step(p.steps.get(k), done.get(k)));
        extra.put("completedSteps", completed);
        if (res != null && res.record() != null) extra.put("step", step(s, res));
        String failedStep;
        if (notStarted) failedStep = "Step " + (i + 1) + " was not started.";
        else if (res == null) failedStep = "The failed step's effect is unknown; check History before retrying.";
        else if (res.state() == OperationState.ROLLED_BACK) failedStep = "The engine rolled the failed step back.";
        else if (res.record() == null && res.error() != null && PRE_EXECUTION.contains(res.error().code()))
            failedStep = "The engine refused the failed step before running it.";
        else if (res.record() == null) failedStep = "The engine reported an internal failure without a record; the failed step's effect is unknown, check History before retrying.";
        else failedStep = "The failed step may have changed the image, ROIs or results (state " + res.state() + "); check History before retrying.";
        extra.put("note", (completed.isEmpty() ? "No earlier step of this plan ran. " : completed.size() + " earlier step(s) completed and stay in History. ")
                + failedStep);
        return new Failure(409, text, extra);
    }

    /** Under {@link #admission}: check the waiting plan, consume it and reserve the plan thread in one step. */
    private Running admitConfirm(String planId) {
        Plan p = pending.get();
        if (p == null) {
            String[] d = discarded.get();
            if (d != null && (planId == null || planId.isBlank() || planId.equals(d[0])))
                throw new Failure(409, "Nothing is waiting for confirmation: plan " + d[0] + " was discarded because " + d[1] + ". Ask again.",
                        Map.of("discarded", d[0]));
            throw new Failure(409, "Nothing is waiting for confirmation.");
        }
        if (planId != null && !planId.isBlank() && !planId.equals(p.id))
            throw new Failure(409, "Plan " + planId + " is not the one waiting (waiting: " + p.id + ").", Map.of("pending", p.id));
        if (System.currentTimeMillis() - p.created > PENDING_TTL_MS) {
            if (pending.compareAndSet(p, null)) discard(p, "it waited longer than " + PENDING_TTL_MS / 60_000 + " min");
            throw new Failure(409, "The plan waited too long and was discarded; ask again.");
        }
        if (!contextKey().equals(p.contextKey)) {
            if (pending.compareAndSet(p, null)) discard(p, "the image, selection, results or history changed");
            throw new Failure(409, "The image, selection, results or history changed since the plan was made; it was discarded. Ask again.");
        }
        Plan busy = running.get();
        if (busy != null) throw new Failure(409, "Penny is still running " + busy.call.operation() + " (" + busy.id + "); confirm again when it finishes.",
                Map.of("planId", busy.id));
        if (!pending.compareAndSet(p, null)) throw new Failure(409, "The plan was already confirmed or cancelled.");
        return reserve(p, true);
    }

    /** Workspace Confirm button (via {@link PennyPlans}): the same checks as {@code confirm}, off the EDT. */
    CompletableFuture<Map<String, Object>> confirmFromUi(String planId) {
        CompletableFuture<Map<String, Object>> f = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                Running r;
                synchronized (admission) {
                    r = admitConfirm(planId);
                }
                f.complete(await(r));
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        }, "fidelis-penny-confirm");
        t.setDaemon(true);
        t.start();
        return f;
    }

    /** Workspace Cancel (via {@link PennyPlans}); never blocks the caller (the EDT) on the admission lock. */
    void cancelFromUi(String planId) {
        Thread t = new Thread(() -> {
            synchronized (admission) {
                cancel(planId);
            }
        }, "fidelis-penny-cancel");
        t.setDaemon(true);
        t.start();
    }

    /**
     * {@code cancel_plan}: discard the plan waiting for confirmation, or stop the running plan ({@code planId} names it, or
     * nothing waits): Cancel is pressed on the Fiji dialog it is stopped at, the step it is on is cancelled in the engine
     * ({@link Engine#cancel}: Escape for a command, abort for a macro) and no further step starts. The plan's own result
     * (the answer to the confirm or operation call that started it) then says {@code status: "cancelled"} and which steps
     * completed; everything that ran stays in History and is undoable there.
     */
    Map<String, Object> cancel(String planId) {
        Plan p = pending.get();
        Map<String, Object> out = new LinkedHashMap<>();
        Plan r = running.get();
        boolean named = planId != null && !planId.isBlank();
        if (r != null && (named ? planId.equals(r.id) : p == null)) {
            PendingDialog rd = r.dialog;
            if (rd != null) {
                java.awt.Window w = r.dialogWindow != null ? r.dialogWindow : DialogDriver.find(rd.title(), rd.dialogClass());
                DialogDriver.Described dd = w == null ? null : DialogDriver.describe(w);
                if (dd != null && dd.buttons().stream().anyMatch(b -> b.equalsIgnoreCase("Cancel"))) {
                    String before = r.cancelledDialog;
                    r.cancelledDialog = dd.title();
                    try {
                        DialogDriver.act(w, Map.of(), "Cancel");
                    } catch (RuntimeException e) {
                        if (!(e instanceof DialogDriver.EdtTimeout t) || t.notApplied) r.cancelledDialog = before;
                        throw e;
                    }
                    // the researcher cancelled the plan, not just its dialog: should the command outlive its Cancel, no later step runs
                    r.stopRequested = true;
                    out.put("cancelled", true);
                    out.put("planId", r.id);
                    out.put("dialogCancelled", dd.title());
                    out.put("note", "Cancel was pressed on the Fiji dialog; the command ends as its plugin decides (see history), and no further step of the plan runs.");
                    return out;
                }
            }
            r.stopRequested = true;
            String opId = r.currentOperationId;
            boolean engineCancel = opId != null && !r.cancelIssued;
            if (engineCancel) {
                r.cancelIssued = true;
                engine.cancel(opId);
            }
            out.put("cancelled", true);
            out.put("planId", r.id);
            out.put("stopped", true);
            out.put("engineCancelRequested", opId != null);
            out.put("note", "The running plan was told to stop: " + (opId != null ? "the step it is on is being cancelled in the engine and " : "")
                    + "no further step will start. Its own result and History say which steps completed; they stay undoable.");
            return out;
        }
        if (p == null || (named && !planId.equals(p.id)) || !pending.compareAndSet(p, null)) {
            out.put("cancelled", false);
            out.put("note", "nothing was waiting" + (planId == null ? "" : " under " + planId));
            return out;
        }
        discard(p, "it was cancelled");
        out.put("cancelled", true);
        out.put("planId", p.id);
        return out;
    }

    private Map<String, Object> dialogResult(Plan p, java.awt.Window w, String note) {
        DialogDriver.Described d = DialogDriver.describe(w);
        PendingDialog pd = p.dialog;
        if (p.dialogWindow == null) p.dialogWindow = w;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "dialog");
        out.put("dialogId", pd == null ? null : pd.id());
        out.put("planId", p.id);
        out.put("operation", p.call.operation());
        int i = p.current;
        out.put("step", i < 0 || i >= p.steps.size() ? null : map("index", i + 1, "of", p.steps.size(), "label", p.steps.get(i).label()));
        out.put("dialog", d.json());
        out.put("note", note != null ? note : "The command is waiting on its original Fiji dialog. Fill and press it with dialog_action "
                + "{fields:{key: value}, press: \"OK\" | \"Cancel\" | <button>}, or answer it in Fidelis; nothing else runs until it is answered.");
        out.put("contextKey", contextKey());
        return out;
    }

    /** {@code dialog}: the dialog Penny's running plan waits on, else the other dialogs showing (read only). */
    private Map<String, Object> dialogRead() {
        Plan p = running.get();
        PendingDialog d = p == null ? null : p.dialog;
        java.awt.Window w = d == null ? null : DialogDriver.find(d.title(), d.dialogClass());
        if (w != null) {
            Map<String, Object> out = dialogResult(p, w, null);
            out.put("pending", true);
            return out;
        }
        List<Map<String, Object>> others = new ArrayList<>();
        for (java.awt.Window x : DialogDriver.showing()) {
            DialogDriver.Described dd = DialogDriver.describe(x);
            others.add(map("title", dd.title(), "kind", dd.kind(), "buttons", dd.buttons()));
        }
        Map<String, Object> out = map("pending", false, "others", others);
        out.put("note", others.isEmpty() ? "No Fiji dialog is open." : "These dialogs are not waiting for Penny; answer them in Fidelis.");
        return out;
    }

    /** Under {@link #admission}: fill and press the dialog Penny's running plan waits on, then wait for the plan again. */
    private Running admitDialogAction(JsonObject a) {
        Plan p = running.get();
        PendingDialog d = p == null ? null : p.dialog;
        java.awt.Window w = p == null ? null : p.dialogWindow;
        if (p == null || d == null || w == null || p.handle == null) throw new Failure(409, "No Fiji dialog is waiting for Penny.");
        String dialogId = PennyJson.str(a, "dialogId", null);
        if (dialogId == null || dialogId.isBlank()) throw new IllegalArgumentException("'dialogId' is required (the dialogId of the dialog result being answered)");
        if (!dialogId.equals(d.id()))
            throw new Failure(409, "A different Fiji dialog is waiting now (" + d.id() + " '" + d.title() + "'); read it again before answering.",
                    Map.of("dialogId", d.id()));
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) PennyJson.toJava(PennyJson.object(a, "fields"));
        String press = PennyJson.str(a, "press", "OK");
        if (press != null && press.isBlank()) press = null;
        String title = DialogDriver.describe(w).title();
        boolean cancel = press != null && (press.trim().equalsIgnoreCase("Cancel") || press.trim().equalsIgnoreCase("No"));
        String before = p.cancelledDialog;
        if (cancel) p.cancelledDialog = title; // set before the press wakes the command, so its end is read as a cancel
        try {
            DialogDriver.act(w, values, press);
        } catch (RuntimeException e) {
            // keep the marker when the press may still have happened (the EDT was busy after the task had started)
            if (!(e instanceof DialogDriver.EdtTimeout t) || t.notApplied) p.cancelledDialog = before;
            if (e instanceof DialogDriver.EdtTimeout t) throw new Failure(409, t.getMessage(), Map.of("applied", t.notApplied ? "no" : "unknown"));
            throw e;
        }
        if (press != null) DialogDriver.awaitClosed(w, 3000);
        return p.handle;
    }

    Map<String, Object> pendingJson() {
        Plan p = pending.get();
        if (p == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", p.id);
        m.put("operation", p.call.operation());
        m.put("reasons", p.reasons);
        m.put("steps", p.labels());
        return m;
    }

    private PennyUi.Plan card(Plan p) {
        Map<String, String> ctx = new LinkedHashMap<>();
        ctx.put("operation", p.call.operation());
        if (p.doc != null) {
            ctx.put("documentId", p.doc.value());
            engine.document(p.doc).ifPresent(d -> ctx.put("document", d.title()));
        }
        ctx.put("transcriptRef", p.transcriptRef);
        String utterance = p.call.utterance() == null ? "" : p.call.utterance();
        return new PennyUi.Plan(p.id, utterance, p.call.spoken(), p.explicit || mode == ConfirmMode.ALWAYS, p.reasons, p.labels(),
                p.steps.stream().map(Step::preview).toList(), ctx);
    }

    private static void ui(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.err.println("[fidelis-penny] workspace hook failed: " + e);
        }
    }

    // =============================================================================================
    // step builders
    // =============================================================================================

    private Map<String, Object> meta(Plan p, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>(params);
        m.put("transcriptRef", p.transcriptRef);
        return m;
    }

    private CommandSchema schema(String id) {
        return engine.schema(id).orElseThrow(() -> new Failure(409, "The command " + id + " is not in this Fiji runtime's catalogue."));
    }

    /** The plan's approved plane of a stack document, read once when the plan is built; null for single planes. */
    private int[] position(Plan p) {
        if (p.position == null && p.doc != null) {
            DocumentInfo d = engine.document(p.doc).orElse(null);
            ImagePlus imp = imp(p.doc);
            if (d != null && d.isStack() && imp != null) p.position = new int[] {imp.getC(), imp.getZ(), imp.getT()};
        }
        return p.position;
    }

    /** A COMMAND step: user keys, recipe keys, then catalogue defaults (see {@link Params}). */
    private Step commandStep(Plan p, String role, String id, Map<String, Object> user, Map<String, Object> recipe, TargetScope ts) {
        CommandSchema s = schema(id);
        // pin the approved plane: a stack command must not follow a plane change made in the workspace meanwhile
        if (ts != null && ts.document().equals(p.doc) && ts.channel() == null && ts.slice() == null && ts.frame() == null) {
            int[] pos = position(p);
            if (pos != null) ts = new TargetScope(ts.document(), pos[0], pos[1], pos[2], ts.allPlanes(), ts.roiIndex());
        }
        DocumentInfo d = ts == null ? null : engine.document(ts.document()).orElse(null);
        Map<String, Object> params;
        Map<String, String> prov;
        if (!s.schemaComplete()) {
            if (!user.isEmpty())
                throw new IllegalArgumentException(s.label() + " has no catalogued parameters; call it without params (its own Fiji dialog opens in Fidelis if it has one)");
            params = new LinkedHashMap<>();
            prov = new LinkedHashMap<>();
        } else {
            Params.Filled f = Params.fill(s, user, recipe, d);
            params = f.params();
            prov = f.provenance();
        }
        String menu = s.menuPath() == null || s.menuPath().isBlank() ? s.label() : s.menuPath() + ">" + s.label();
        ActionRequest r = ActionRequest.command(p.origin, id, meta(p, params), ts);
        return new Step(role, s.label(), menu, r, prov, Params.preview(s, params));
    }

    private Step directStep(Plan p, String role, String op, String label, String menu, Map<String, Object> params,
                            Map<String, String> prov, String preview, TargetScope ts) {
        if (op.equals(Operations.SET_POSITION) && ts != null && ts.document().equals(p.doc)) {
            int[] pos = position(p);
            if (pos != null) {
                if (params.get("channel") instanceof Number n) pos[0] = n.intValue();
                if (params.get("slice") instanceof Number n) pos[1] = n.intValue();
                if (params.get("frame") instanceof Number n) pos[2] = n.intValue();
            }
        }
        if (PLANE_DIRECT.contains(op) && ts != null && ts.document().equals(p.doc) && ts.channel() == null && ts.slice() == null && ts.frame() == null) {
            int[] pos = position(p);
            if (pos != null) ts = new TargetScope(ts.document(), pos[0], pos[1], pos[2], ts.allPlanes(), ts.roiIndex());
        }
        ActionRequest r = ActionRequest.direct(p.origin, op, meta(p, params), ts);
        return new Step(role, label, menu, r, prov, preview);
    }

    private static Map<String, String> prov(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (String) kv[i + 1]);
        return m;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // =============================================================================================
    // documents and reads
    // =============================================================================================

    private DocumentInfo requireDoc() {
        Optional<DocumentId> f = engine.focused();
        Optional<DocumentInfo> d = f.flatMap(engine::document);
        if (d.isEmpty()) throw new Failure(409, engine.documents().isEmpty() ? "No image is open in Fidelis; open one first."
                : "No image is focused in Fidelis; activate one first.");
        return d.get();
    }

    private ImagePlus imp(DocumentId id) {
        return id == null ? null : engine.imagePlus(id);
    }

    Map<String, Object> documentJson(DocumentInfo d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id().value());
        m.put("imageId", d.imagePlusId());
        m.put("title", d.title());
        m.put("width", d.width());
        m.put("height", d.height());
        m.put("channels", d.nChannels());
        m.put("slices", d.nSlices());
        m.put("frames", d.nFrames());
        m.put("bitDepth", d.bitDepth());
        m.put("type", d.type());
        m.put("unit", d.unit());
        m.put("pixelWidth", d.pixelWidth());
        m.put("pixelHeight", d.pixelHeight());
        m.put("path", d.sourcePath() == null ? null : d.sourcePath().toString());
        m.put("changed", d.changed());
        ImagePlus imp = imp(d.id());
        if (imp != null) {
            m.put("position", map("channel", imp.getC(), "slice", imp.getZ(), "frame", imp.getT()));
            m.put("displayRange", List.of(imp.getDisplayRangeMin(), imp.getDisplayRangeMax()));
            ImageProcessor ip = LiveRead.processor(imp);
            // a mask: intensities measured on it are 0/255, not the signal
            m.put("binary", ip != null && d.bitDepth() == 8 && ip.isBinary());
        }
        return m;
    }

    private Map<String, Object> roiJson(RoiGeometry g) {
        if (g == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", g.type());
        Rectangle2D b = Highlight.bounds(g);
        m.put("bounds", b == null ? null : Highlight.rect(b));
        if (g.name() != null) m.put("name", g.name());
        return m;
    }

    /**
     * SHA-256 over the focused document's id, title and plane position, its active ROI (type, bounds, vertex count), its
     * ROI-list size, the Results row count, the history size and the list of open documents.
     */
    String contextKey() {
        StringBuilder sb = new StringBuilder();
        Optional<DocumentId> f = engine.focused();
        if (f.isEmpty()) sb.append("no-document");
        else {
            DocumentId id = f.get();
            sb.append(id.value()).append(':').append(engine.document(id).map(DocumentInfo::title).orElse("?"));
            ImagePlus imp = imp(id);
            if (imp != null) sb.append(':').append(imp.getC()).append(',').append(imp.getZ()).append(',').append(imp.getT());
            Optional<RoiGeometry> roi = engine.activeRoi(id);
            if (roi.isPresent()) {
                Rectangle2D b = Highlight.bounds(roi.get());
                sb.append(":roi=").append(roi.get().type()).append(b == null ? "" : b.getX() + "," + b.getY() + "," + b.getWidth() + "," + b.getHeight())
                        .append('/').append(roi.get().xs() == null ? 0 : roi.get().xs().size());
            } else sb.append(":roi=none");
            sb.append(":rois=").append(engine.rois(id).size());
        }
        sb.append(":results=").append(engine.results().size());
        sb.append(":history=").append(engine.history().size());
        sb.append(":docs=");
        for (DocumentInfo d : engine.documents()) sb.append(d.id().value()).append('/').append(d.title()).append(';');
        return sha256(sb.toString());
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Object> context() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contextKey", contextKey());
        out.put("application", "Fidelis");
        out.put("version", ij.IJ.getFullVersion());
        out.put("adapter", FidelisBridge.identity());
        out.put("engine", map("state", String.valueOf(engine.state())));
        Optional<DocumentId> f = engine.focused();
        DocumentInfo d = f.flatMap(engine::document).orElse(null);
        out.put("document", d == null ? null : documentJson(d));
        List<Map<String, Object>> docs = new ArrayList<>();
        for (DocumentInfo x : engine.documents()) docs.add(map("id", x.id().value(), "title", x.title(), "focused", d != null && x.id().equals(d.id())));
        out.put("documents", docs);
        out.put("roi", d == null ? null : roiJson(engine.activeRoi(d.id()).orElse(null)));
        List<HistoryRecord> h = engine.history();
        out.put("counts", map("rois", d == null ? 0 : engine.rois(d.id()).size(), "resultsRows", engine.results().size(), "history", h.size()));
        if (h.isEmpty()) out.put("lastStep", null);
        else {
            HistoryRecord r = h.get(h.size() - 1);
            out.put("lastStep", map("actionId", r.actionId(), "label", r.label(), "origin", String.valueOf(r.origin()), "outcome", String.valueOf(r.outcome())));
        }
        out.put("globals", engine.globals());
        out.put("viewer", d == null ? null : viewer(d.id()));
        out.put("pending", pendingJson());
        Plan r = running.get();
        out.put("running", r == null ? null : map("planId", r.id, "operation", r.call.operation()));
        out.put("confirmPolicy", mode == ConfirmMode.ALWAYS ? "always" : "auto");
        return out;
    }

    private Map<String, Object> viewer(DocumentId id) {
        PennyUi ui = PennyUi.current();
        double mag;
        Rectangle canvas;
        try {
            mag = ui.magnification(id);
            canvas = ui.canvasOnScreen(id);
        } catch (RuntimeException e) {
            mag = Double.NaN;
            canvas = null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("magnification", Double.isNaN(mag) ? null : mag);
        m.put("canvasScreen", Highlight.screen(canvas));
        return m;
    }

    Map<String, Object> images() {
        Optional<DocumentId> f = engine.focused();
        List<Map<String, Object>> docs = new ArrayList<>();
        for (DocumentInfo d : engine.documents()) {
            docs.add(map("id", d.id().value(), "imageId", d.imagePlusId(), "title", d.title(), "width", d.width(), "height", d.height(),
                    "focused", f.isPresent() && f.get().equals(d.id()), "changed", d.changed()));
        }
        return map("documents", docs);
    }

    private Map<String, Object> activate(JsonObject a) {
        String id = PennyJson.str(a, "id", null);
        String title = PennyJson.str(a, "title", null);
        Double imageId = PennyJson.num(a, "imageId");
        DocumentInfo hit = null;
        for (DocumentInfo d : engine.documents()) {
            if ((id != null && d.id().value().equals(id)) || (imageId != null && d.imagePlusId() == imageId.intValue())) hit = d;
        }
        if (hit == null && id != null) {
            try {
                int n = Integer.parseInt(id.trim());
                for (DocumentInfo d : engine.documents()) if (d.imagePlusId() == n) hit = d;
            } catch (NumberFormatException ignored) {
                // not an IJ1 id
            }
        }
        if (hit == null && title != null) {
            for (DocumentInfo d : engine.documents()) if (d.title().equals(title)) { hit = d; break; }
            if (hit == null) for (DocumentInfo d : engine.documents()) if (d.title().equalsIgnoreCase(title)) { hit = d; break; }
        }
        if (hit == null) throw new IllegalArgumentException("No open document matches " + (id != null ? "id '" + id + "'" : title != null ? "title '" + title + "'" : "(give id or title)"));
        engine.focus(hit.id());
        return map("document", documentJson(hit), "contextKey", contextKey());
    }

    private Map<String, Object> commands(JsonObject a) {
        String q = PennyJson.str(a, "query", "");
        if (q.isBlank()) throw new IllegalArgumentException("'query' is required");
        int limit = PennyJson.integer(a, "limit", 10, 1, 50);
        // the engine's ranked search (every token must match), topped up by a token scan of the whole catalogue (built
        // from the live menus, Plugins included) so long phrases and partial names still find plugins
        List<CommandSchema.Summary> hits = new ArrayList<>(engine.search(q, limit));
        if (hits.size() < limit) {
            String[] tokens = q.toLowerCase(Locale.ROOT).split("[^a-z0-9']+");
            List<Map.Entry<Integer, CommandSchema.Summary>> scored = new ArrayList<>();
            for (CommandSchema.Summary s : engine.catalogue()) {
                if (hits.stream().anyMatch(h -> h.commandId().equals(s.commandId()))) continue;
                String hay = (s.label() + " " + s.menuPath()).toLowerCase(Locale.ROOT);
                int n = 0;
                for (String t : tokens) if (t.length() > 1 && hay.contains(t)) n += s.label().toLowerCase(Locale.ROOT).contains(t) ? 2 : 1;
                if (n > 0) scored.add(Map.entry(n, s));
            }
            scored.sort((x, y) -> y.getKey() - x.getKey());
            for (Map.Entry<Integer, CommandSchema.Summary> e : scored) {
                if (hits.size() >= limit) break;
                hits.add(e.getValue());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (CommandSchema.Summary s : hits) {
            Map<String, Object> m = map("commandId", s.commandId(), "label", s.label(), "menuPath", s.menuPath());
            Optional<CommandSchema> sc = engine.schema(s.commandId());
            m.put("schemaComplete", sc.map(CommandSchema::schemaComplete).orElse(false));
            m.put("consequence", sc.map(x -> String.valueOf(x.consequence())).orElse(null));
            if (sc.isPresent() && !sc.get().schemaComplete()) {
                m.put("route", opensFileChooser(sc.get()) ? "file chooser: not available from Penny (use open_path)"
                        : "original Fiji dialog, if it has one: answered with dialog_action");
            }
            if (sc.isPresent() && sc.get().schemaComplete()) {
                List<Map<String, Object>> ps = new ArrayList<>();
                for (ParameterSchema p : sc.get().parameters()) {
                    ps.add(map("key", p.key(), "type", String.valueOf(p.type()), "default", p.defaultValue(), "choices", p.choices(),
                            "consequential", p.consequential()));
                }
                m.put("parameters", ps);
            }
            out.add(m);
        }
        return map("commands", out);
    }

    private Map<String, Object> roiList(JsonObject a) {
        DocumentInfo d = requireDoc();
        int limit = PennyJson.integer(a, "limit", 100, 1, 2000);
        int offset = PennyJson.integer(a, "offset", 0, 0, Integer.MAX_VALUE);
        List<RoiGeometry> rois = engine.rois(d.id());
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = offset; i < rois.size() && out.size() < limit; i++) {
            Map<String, Object> m = roiJson(rois.get(i));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("name", rois.get(i).name());
            e.putAll(m);
            out.add(e);
        }
        return map("document", d.id().value(), "total", rois.size(), "rois", out);
    }

    private static Object finite(Double v) {
        return v == null ? null : Double.isFinite(v) ? v : "NaN";
    }

    private static Map<String, Object> rowJson(MeasurementRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("row", r.rowIndex());
        if (r.label() != null) m.put("Label", r.label());
        for (Map.Entry<String, Double> e : r.values().entrySet()) m.put(e.getKey(), finite(e.getValue()));
        return m;
    }

    private Map<String, Object> resultsTable(JsonObject a) {
        int limit = PennyJson.integer(a, "limit", 50, 1, 500);
        int offset = PennyJson.integer(a, "offset", 0, 0, Integer.MAX_VALUE);
        List<MeasurementRow> rows = engine.results();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = offset; i < rows.size() && out.size() < limit; i++) out.add(rowJson(rows.get(i)));
        return map("columns", engine.resultColumns().headings(), "rows", out, "total", rows.size(), "offset", offset);
    }

    private static Map<String, Object> stats(List<Double> values) {
        List<Double> v = values.stream().filter(x -> x != null && Double.isFinite(x)).sorted().toList();
        if (v.isEmpty()) return null;
        double sum = 0;
        for (double x : v) sum += x;
        int n = v.size();
        double median = n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
        return map("mean", sum / n, "median", median, "min", v.get(0), "max", v.get(n - 1), "count", n);
    }

    private Map<String, Object> objectStats() {
        List<MeasurementRow> all = engine.results();
        String source = "all Results rows";
        List<MeasurementRow> rows = all;
        List<HistoryRecord> h = engine.history();
        for (int i = h.size() - 1; i >= 0; i--) {
            HistoryRecord r = h.get(i);
            if (PennyCommands.ANALYZE_PARTICLES.equals(r.commandId()) && r.outcome() == OperationState.COMPLETED) {
                rows = all.stream().filter(x -> r.actionId().equals(x.actionId())).toList();
                source = "Analyze Particles " + r.actionId();
                break;
            }
        }
        Set<DocumentId> sources = new LinkedHashSet<>();
        for (MeasurementRow r : rows) sources.add(r.document());
        String unit = sources.size() == 1 && sources.iterator().next() != null
                ? engine.document(sources.iterator().next()).map(DocumentInfo::unit).orElse(null) : null;
        Map<String, Object> out = map("n", rows.size(), "unit", unit, "source", source);
        if (unit == null && !rows.isEmpty()) out.put("unitNote", "the rows come from " + (sources.size() > 1 ? "several documents" : "a document that is no longer open")
                + "; no single unit applies");
        for (String[] col : new String[][] {{"Area", "area"}, {"Mean", "mean"}, {"Circ.", "circularity"}, {"Perim.", "perimeter"},
                {"IntDen", "integratedDensity"}}) {
            List<Double> v = new ArrayList<>();
            for (MeasurementRow r : rows) if (r.values().containsKey(col[0])) v.add(r.values().get(col[0]));
            Map<String, Object> s = stats(v);
            if (s != null) out.put(col[1], s);
        }
        if (rows.isEmpty()) out.put("note", "the Results table is empty; segment or measure first");
        return out;
    }

    private Map<String, Object> history(JsonObject a) {
        int limit = PennyJson.integer(a, "limit", 20, 1, 500);
        List<HistoryRecord> h = engine.history();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = Math.max(0, h.size() - limit); i < h.size(); i++) {
            HistoryRecord r = h.get(i);
            Map<String, Object> m = map("actionId", r.actionId(), "label", r.label(), "commandId", r.commandId(), "origin",
                    String.valueOf(r.origin()), "provenance", String.valueOf(r.provenance()), "outcome", String.valueOf(r.outcome()),
                    "transcriptRef", r.transcriptRef(), "time", String.valueOf(r.time()), "macroLines", r.macroLines());
            if (r.errorSummary() != null) m.put("note", r.errorSummary());
            out.add(m);
        }
        return map("total", h.size(), "steps", out);
    }

    private Map<String, Object> macro() {
        return map("macro", engine.exportMacro(), "steps", engine.history().size());
    }

    private Map<String, Object> step(Step s, ActionResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        HistoryRecord rec = r == null ? null : r.record();
        m.put("actionId", rec == null ? null : rec.actionId());
        m.put("label", s.label());
        m.put("commandId", rec == null ? (s.request().commandId() != null ? s.request().commandId() : s.request().operation()) : rec.commandId());
        m.put("outcome", r == null ? null : String.valueOf(r.state()));
        m.put("durationMs", r == null ? null : r.durationMs());
        m.put("macroLines", rec == null ? List.of() : rec.macroLines());
        return m;
    }

    // =============================================================================================
    // view operations that never reach the engine's history
    // =============================================================================================

    private Map<String, Object> zoom(JsonObject a) {
        DocumentInfo d = requireDoc();
        String how;
        if (PennyJson.bool(a, "fit", false)) how = "fit";
        else if (PennyJson.bool(a, "selection", false)) how = "selection";
        else {
            how = PennyJson.str(a, "direction", "in").toLowerCase(Locale.ROOT);
            if (!how.equals("in") && !how.equals("out")) throw new IllegalArgumentException("direction must be in or out");
        }
        final String h = how;
        ui(() -> PennyUi.current().zoom(d.id(), h));
        Map<String, Object> out = map("zoom", how, "document", d.id().value(), "viewer", viewer(d.id()));
        out.put("highlight", Highlight.canvas(d.id(), "Zoomed " + how));
        return out;
    }

    private Map<String, Object> highlight(JsonObject a) {
        DocumentInfo d = requireDoc();
        String which = PennyJson.str(a, "rois", "all").toLowerCase(Locale.ROOT);
        int limit = PennyJson.integer(a, "limit", HIGHLIGHT_LIMIT, 1, 1000);
        List<RoiGeometry> all = engine.rois(d.id());
        List<RoiGeometry> chosen = new ArrayList<>();
        switch (which) {
            case "all" -> chosen.addAll(all);
            case "indices" -> {
                List<Object> idx = PennyJson.list(a, "indices");
                if (idx == null || idx.isEmpty()) throw new IllegalArgumentException("rois=indices needs 'indices'");
                for (Object o : idx) {
                    if (!(o instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 0 || n.intValue() >= all.size())
                        throw new IllegalArgumentException("index " + o + " is outside the ROI list (size " + all.size() + ")");
                    chosen.add(all.get(n.intValue()));
                }
            }
            case "active" -> engine.activeRoi(d.id()).ifPresent(chosen::add);
            default -> throw new IllegalArgumentException("rois must be all, indices or active");
        }
        if (chosen.isEmpty()) throw new Failure(409, which.equals("active") ? "There is no active selection." : "The ROI list is empty; segment or add regions first.");
        String label = PennyJson.str(a, "label", chosen.size() == 1 ? "1 region" : chosen.size() + " regions");
        return map("highlight", Highlight.rois(d.id(), chosen, label, limit, true), "total", chosen.size());
    }

    // =============================================================================================
    // plans
    // =============================================================================================

    private Plan openPath(Call c) {
        String raw = PennyJson.str(c.args(), "path", "");
        if (raw.isBlank()) throw new IllegalArgumentException("'path' is required");
        if (raw.startsWith("~/")) raw = System.getProperty("user.home") + raw.substring(1);
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("No such file: " + path);
        Plan p = new Plan(c, null);
        Map<String, Object> params = map("path", path.toString());
        ActionRequest r = ActionRequest.session(p.origin, Operations.OPEN, meta(p, params), null);
        p.steps.add(new Step("open", "Open " + path.getFileName(), "File>Open...", r, prov("path", Params.USER),
                "open(\"" + path.toString().replace("\\", "/") + "\");"));
        p.finish = (plan, res) -> {
            ActionResult o = res.get(0);
            DocumentId id = o.digest() == null || o.digest().added().isEmpty() ? null : o.digest().added().get(0);
            DocumentInfo d = id == null ? null : engine.document(id).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("opened", d != null);
            m.put("document", d == null ? null : documentJson(d));
            m.put("highlight", Highlight.canvas(id, d == null ? "Opened" : "Opened " + d.title()));
            return m;
        };
        return p;
    }

    private Plan command(Call c) {
        String id = PennyJson.str(c.args(), "commandId", "");
        if (id.isBlank()) throw new IllegalArgumentException("'commandId' is required (a catalogue id such as ij1:Process>Filters>Gaussian Blur...)");
        CommandSchema s = engine.schema(id).orElseThrow(() -> {
            List<String> sugg = engine.search(id.substring(id.lastIndexOf('>') + 1).replace("...", ""), 3).stream().map(CommandSchema.Summary::commandId).toList();
            return new IllegalArgumentException("Unknown command " + id + (sugg.isEmpty() ? "" : "; did you mean " + sugg + "?"));
        });
        if (!s.schemaComplete() && opensFileChooser(s))
            throw new Failure(409, s.label() + " starts with an operating-system file chooser, which Penny cannot see or fill; use open_path "
                    + "for files (or run it from Fidelis's menu).", Map.of("code", "FILE_CHOOSER"));
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) PennyJson.toJava(PennyJson.object(c.args(), "params"));
        Optional<DocumentInfo> d = engine.focused().flatMap(engine::document);
        if (s.preconditions().contains("image.open") && d.isEmpty()) requireDoc();
        Plan p = new Plan(c, d.map(DocumentInfo::id).orElse(null));
        TargetScope ts = p.doc == null ? null : TargetScope.of(p.doc);
        p.steps.add(commandStep(p, "command", id, user, Map.of(), ts));
        p.finish = (plan, res) -> {
            ActionResult r = res.get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", step(plan.steps.get(0), r));
            if (r.record() != null) m.put("provenance", String.valueOf(r.record().provenance()));
            if (r.digest() != null) {
                m.put("resultsRowsAdded", r.digest().resultsRowsAdded());
                if (!r.digest().added().isEmpty()) engine.document(r.digest().added().get(0)).ifPresent(x -> m.put("document", documentJson(x)));
                if (!r.digest().globalsDelta().isEmpty()) m.put("globalsChanged", r.digest().globalsDelta());
                if (!r.digest().windowsOpened().isEmpty()) m.put("windowsOpened", r.digest().windowsOpened());
                if (!r.digest().newLogLines().isEmpty())
                    m.put("log", r.digest().newLogLines().size() > 20 ? r.digest().newLogLines().subList(0, 20) : r.digest().newLogLines());
            }
            DocumentId shown = m.containsKey("document") ? r.digest().added().get(0) : plan.doc;
            if (shown != null) m.put("highlight", Highlight.canvas(shown, s.label()));
            return m;
        };
        return p;
    }

    private static final Set<String> FILE_CHOOSER_LABELS = Set.of("Open...", "Open Next", "Save", "Import...", "Image Sequence...",
            "Raw...", "LUT... ", "Text Image... ", "Text File... ", "Results... ", "URL...", "Stack From List...", "TIFF Virtual Stack...",
            "AVI...", "XY Coordinates... ", "Open Recent", "Bio-Formats", "Bio-Formats Importer", "Bio-Formats Exporter", "Bio-Formats Windowless Importer",
            "Revert");

    /** Commands whose first act is an OS file chooser (or a file import/export) when run without catalogued options. */
    static boolean opensFileChooser(CommandSchema s) {
        String path = s.menuPath() == null ? "" : s.menuPath();
        String label = s.label().trim();
        return FILE_CHOOSER_LABELS.contains(s.label()) || FILE_CHOOSER_LABELS.contains(label) || path.startsWith("File>Import")
                || path.startsWith("File>Save As") || path.startsWith("File>Export") || label.contains("Importer") || label.contains("Exporter")
                || (path.equals("File") && (label.startsWith("Open") || label.startsWith("Save")));
    }

    private static String method(JsonObject a, String def) {
        String m = PennyJson.str(a, "method", null);
        if (m == null) return def;
        for (String x : THRESHOLD_METHODS) if (x.equalsIgnoreCase(m.trim().replace(' ', '_')) || x.equalsIgnoreCase(m.trim().replace(" ", ""))) return x;
        throw new IllegalArgumentException("method must be one of " + THRESHOLD_METHODS);
    }

    /** Bright objects on a dark background unless the image shows an inverting LUT (ImageJ's display convention). */
    private boolean darkFor(DocumentInfo d) {
        ImagePlus imp = imp(d.id());
        return imp == null || !imp.isInvertedLut();
    }

    private Set<String> currentMeasurements() {
        String s = engine.globals().getOrDefault("measurements", "");
        return new LinkedHashSet<>(s.isBlank() ? List.of() : List.of(s.trim().split("\\s+")));
    }

    /** A Set Measurements step that records {@code current ∪ wanted}; wanted keys are USER (or DERIVED for recipes). */
    private Step setMeasurementsStep(Plan p, Set<String> wanted, boolean userAsked, DocumentInfo d) {
        CommandSchema s = schema(PennyCommands.SET_MEASUREMENTS);
        Set<String> current = currentMeasurements();
        Map<String, Object> user = new LinkedHashMap<>();
        Map<String, Object> recipe = new LinkedHashMap<>();
        for (ParameterSchema ps : s.parameters()) {
            String k = ps.key();
            if (ps.type() == sc.fidelis.engine.api.Enums.ParameterType.BOOLEAN) {
                if (wanted.contains(k)) (userAsked ? user : recipe).put(k, true);
                else recipe.put(k, current.contains(k));
            }
        }
        recipe.put("redirect", "None");
        String precision = engine.globals().getOrDefault("precision", "3");
        recipe.put("decimal", Long.parseLong(precision.trim()));
        return commandStep(p, "set_measurements", PennyCommands.SET_MEASUREMENTS, user, recipe, d == null ? null : TargetScope.of(d.id()));
    }

    private static ActionRequest retarget(ActionRequest r, DocumentId doc) {
        TargetScope t = r.target();
        return new ActionRequest(UUID.randomUUID().toString(), r.origin(), r.kind(), r.commandId(), r.operation(), r.params(),
                new TargetScope(doc, null, null, null, t != null && t.allPlanes(), t == null ? null : t.roiIndex()), r.confirmationToken(), r.replayOf());
    }

    private static DocumentId created(ActionResult r) {
        if (r.digest() == null || r.digest().added().isEmpty()) throw new IllegalStateException("the duplicate produced no new document");
        return r.digest().added().get(0);
    }

    /** The same step on the document step {@code from} created (built when the step runs). */
    private static Step onCreated(Step s, int from) {
        return new Step(s.role(), s.label(), s.menuPath(), s.request(), s.provenance(), s.preview(), res -> retarget(s.request(), created(res.get(from))));
    }

    /**
     * segment_cells on a duplicate: the original keeps its pixels (so measurements of the objects read the real signal)
     * and receives the objects as its ROI list; the duplicate becomes the mask.
     */
    private Plan segmentCells(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        TargetScope ts = TargetScope.of(d.id());
        boolean watershed = PennyJson.bool(a, "watershed", true);
        boolean gaussian = PennyJson.bool(a, "gaussian", true);
        boolean replace = PennyJson.bool(a, "replace", true);
        Double sigma = PennyJson.num(a, "sigma");
        String method = method(a, "Otsu");
        Boolean darkArg = PennyJson.bool(a, "dark");
        final boolean dark = darkArg != null ? darkArg : darkFor(d);
        Double minSize = PennyJson.num(a, "minSize");
        Double maxSize = PennyJson.num(a, "maxSize");
        Boolean exclude = PennyJson.bool(a, "exclude");
        Boolean pixel = PennyJson.bool(a, "pixel");
        boolean keepMask = PennyJson.bool(a, "keepMask", false);
        final Integer channel;
        final String channelBy;
        if (PennyJson.has(a, "channel")) {
            int ch = PennyJson.integer(a, "channel", 1, 1, Math.max(1, d.nChannels()));
            channel = ch;
            channelBy = "argument";
            if (d.nChannels() > 1) p.steps.add(directStep(p, "channel", Operations.SET_POSITION, "Channel " + ch, "Image>Stacks>Set Channel",
                    map("channel", ch), prov("channel", Params.USER), "Stack.setChannel(" + ch + ");", ts));
        } else if (d.nChannels() > 1) {
            // "the nuclei" on a multichannel image: the blue-LUT (DAPI) channel, a DAPI/Hoechst-labelled one, else the current
            ChannelChoice choice = nucleiChannel(imp(d.id()), d.nChannels());
            channel = choice.channel();
            channelBy = choice.by();
            if (!"current".equals(choice.by()))
                p.steps.add(directStep(p, "channel", Operations.SET_POSITION, "Channel " + choice.channel() + " (" + ("lut-blue".equals(choice.by()) ? "blue" : "nuclear label") + ")",
                        "Image>Stacks>Set Channel", map("channel", choice.channel()), prov("channel", Params.DERIVED), "Stack.setChannel(" + choice.channel() + ");", ts));
        } else {
            channel = null;
            channelBy = null;
        }
        // an active selection would be restored onto the duplicate (and can crop it): clear it first, restore it at the end
        Optional<RoiGeometry> selection = engine.activeRoi(d.id());
        if (selection.isPresent()) p.steps.add(directStep(p, "unselect", Operations.CLEAR_ROI, "Select None (restored afterwards)",
                "Edit>Selection>Select None", Map.of(), Map.of(), "run(\"Select None\");", ts));
        // the current plane of the original, whole image, as a new document
        String base = d.title().replaceFirst("\\.[A-Za-z0-9]{2,4}$", "");
        int[] pos = position(p);
        String c0 = pos == null ? "1" : String.valueOf(pos[0]), z0 = pos == null ? "1" : String.valueOf(pos[1]), t0 = pos == null ? "1" : String.valueOf(pos[2]);
        p.steps.add(commandStep(p, "duplicate", PennyCommands.DUPLICATE, Map.of(), map("title", base + "-mask", "ignore", true, "duplicate", false,
                "range", z0 + "-" + z0, "channels", c0 + "-" + c0, "slices", z0 + "-" + z0, "frames", t0 + "-" + t0), ts));
        final int dup = p.steps.size() - 1;
        if (d.bitDepth() == 24 || "COLOR_256".equals(d.type()))
            p.steps.add(onCreated(commandStep(p, "convert", PennyCommands.TO_8_BIT, Map.of(), Map.of(), ts), dup));
        if (gaussian) {
            Map<String, Object> user = new LinkedHashMap<>();
            Map<String, Object> recipe = map("scaled", false, "stack", false);
            if (sigma != null) user.put("sigma", sigma);
            else recipe.put("sigma", 1.0);
            p.steps.add(onCreated(commandStep(p, "gaussian", PennyCommands.GAUSSIAN, user, recipe, ts), dup));
        }
        p.steps.add(onCreated(directStep(p, "threshold", Operations.SET_AUTO_THRESHOLD, "Auto threshold " + method + (dark ? " (dark background)" : ""),
                "Image>Adjust>Threshold...", map("method", method, "dark", dark),
                prov("method", PennyJson.has(a, "method") ? Params.USER : Params.DERIVED, "dark", darkArg != null ? Params.USER : Params.DERIVED),
                "setAutoThreshold(\"" + method + (dark ? " dark" : "") + "\");", ts), dup));
        boolean black = Boolean.parseBoolean(engine.globals().getOrDefault("blackBackground", "false"));
        p.steps.add(onCreated(commandStep(p, "mask", PennyCommands.CONVERT_TO_MASK, Map.of(),
                map("method", "Default", "background", "Default", "calculate", false, "only", false, "black", black), ts), dup));
        p.steps.add(onCreated(commandStep(p, "fill", PennyCommands.FILL_HOLES, Map.of(), Map.of(), ts), dup));
        if (watershed) p.steps.add(onCreated(commandStep(p, "watershed", PennyCommands.WATERSHED, Map.of(), Map.of(), ts), dup));
        if (!currentMeasurements().contains("area")) p.steps.add(setMeasurementsStep(p, new LinkedHashSet<>(SEGMENT_MEASUREMENTS), false, d));
        Map<String, Object> user = new LinkedHashMap<>();
        if (minSize != null || maxSize != null) {
            if (minSize != null && maxSize != null && minSize > maxSize) throw new IllegalArgumentException("minSize must not exceed maxSize");
            user.put("size", Params.number(minSize == null ? 0 : minSize) + "-" + (maxSize == null ? "Infinity" : Params.number(maxSize)));
        }
        if (exclude != null) user.put("exclude", exclude);
        if (pixel != null) user.put("pixel", pixel);
        Map<String, Object> recipe = map("size", "0-Infinity", "pixel", false, "circularity", "0.00-1.00", "show", "Nothing", "display", true,
                "exclude", false, "clear", true, "include", false, "summarize", false, "overlay", false, "add", true, "composite", false,
                "record", false, "in_situ", false, "stack", false);
        p.steps.add(onCreated(commandStep(p, "particles", PennyCommands.ANALYZE_PARTICLES, user, recipe, ts), dup));
        // the objects go to the original's ROI list (replacing it unless replace:false)
        Step copy = directStep(p, "copy", Operations.ROI_LIST_ADD, "Copy the objects to " + d.title(), "Analyze>Tools>ROI Manager...",
                map("fromDocument", "(the mask)", "replace", replace), prov("fromDocument", Params.DERIVED, "replace", PennyJson.has(a, "replace") ? Params.USER : Params.DERIVED),
                "roiManager(\"" + (replace ? "reset" : "add") + "\"); // objects of the mask", ts);
        final int[] segmentedPlane = position(p) == null ? null : position(p).clone();
        final int particlesIndex = p.steps.size() - 1;
        p.steps.add(new Step(copy.role(), copy.label(), copy.menuPath(), copy.request(), copy.provenance(), copy.preview(), res -> {
            Map<String, Object> params = new LinkedHashMap<>(copy.request().params());
            params.put("fromDocument", created(res.get(dup)).value());
            // the objects' measurements (the Analyze Particles rows on the mask) belong with the copy: undoing it removes them
            HistoryRecord particles = res.get(particlesIndex).record();
            if (particles != null) params.put("resultsOf", particles.actionId());
            if (segmentedPlane != null)
                params.put("position", List.of(d.nChannels() > 1 ? segmentedPlane[0] : 0, segmentedPlane[1], d.nFrames() > 1 ? segmentedPlane[2] : 0));
            return new ActionRequest(UUID.randomUUID().toString(), copy.request().origin(), ActionKind.DIRECT, null, Operations.ROI_LIST_ADD, params,
                    TargetScope.of(d.id()), null, null);
        }));
        if (selection.isPresent()) p.steps.add(directStep(p, "reselect", Operations.SET_ROI, "Restore the selection", "Edit>Selection>Restore Selection",
                map("roi", selection.get()), prov("roi", Params.DERIVED), "run(\"Restore Selection\");", ts));
        if (!keepMask) {
            // the objects now live on the original: close the mask (discarding it) so repeated segmentations do not pile up
            // tabs; keepMask:true leaves it open. A housekeeping step: not assessed by the policy, recorded by the engine.
            ActionRequest shown = ActionRequest.session(p.origin, Operations.CLOSE, meta(p, map("discard", true)), null);
            p.steps.add(new Step("closeMask", "Close the mask", "File>Close", shown, prov("discard", Params.DERIVED), "close(); // the mask",
                    res -> ActionRequest.session(p.origin, Operations.CLOSE, meta(p, map("discard", true)), TargetScope.of(created(res.get(dup))))));
        }
        p.finish = (plan, res) -> {
            DocumentId mask = created(res.get(dup));
            engine.focus(d.id()); // the original stays the document Penny and the user work on
            Map<String, Object> m = particlesResult(plan, res, d, "cells");
            m.put("threshold", map("method", method, "dark", dark));
            m.put("watershed", watershed);
            m.put("replacedRois", replace);
            if (channel != null) {
                m.put("channel", channel);
                m.put("channelBy", channelBy);
                segmentedChannel.put(d.id(), channel);
            }
            m.put("mask", engine.document(mask).map(x -> map("id", x.id().value(), "title", x.title(), "closed", false))
                    .orElse(map("id", mask.value(), "title", base + "-mask", "closed", true)));
            m.put("note", "Objects were found on a mask (" + base + "-mask" + (keepMask ? ", kept open" : ", closed again") + "); " + d.title()
                    + " keeps its pixels and now holds them as its ROI list, so measure {scope:\"rois\"} reads the real signal.");
            return m;
        };
        return p;
    }

    /** Count, sizes and highlight of the Analyze Particles step: rows the engine attributed to that action. */
    private Map<String, Object> particlesResult(Plan plan, List<ActionResult> res, DocumentInfo d, String noun) {
        int k = plan.index("particles");
        HistoryRecord rec = res.get(k).record();
        String actionId = rec == null ? null : rec.actionId();
        List<Double> areas = new ArrayList<>();
        int count = 0;
        for (MeasurementRow r : engine.results()) {
            if (actionId != null && actionId.equals(r.actionId())) {
                count++;
                Double v = r.values().get("Area");
                if (v != null) areas.add(v);
            }
        }
        double total = 0;
        for (double v : areas) total += v;
        List<RoiGeometry> rois = engine.rois(d.id());
        Map<String, Object> pa = plan.steps.get(k).request().params();
        String countedFrom = "Results rows of " + actionId;
        Map<String, Object> m = new LinkedHashMap<>();
        if (!Boolean.TRUE.equals(pa.get("display"))) {
            if (Boolean.TRUE.equals(pa.get("add"))) {
                count = rois.size();
                countedFrom = "the ROI list (display was off)";
            } else {
                m.put("note", "display and add were both off, so Fidelis recorded no rows or ROIs to count");
                countedFrom = "nothing (display and add off)";
            }
        }
        boolean added = Boolean.TRUE.equals(pa.get("add"));
        List<RoiGeometry> found = added ? rois.subList(Math.max(0, rois.size() - count), rois.size()) : List.of();
        m.put("count", count);
        m.put("countedFrom", countedFrom);
        m.put("averageSize", areas.isEmpty() ? "NaN" : total / areas.size());
        m.put("totalArea", areas.isEmpty() ? "NaN" : total);
        m.put("unit", d.unit());
        m.put("rois", rois.size());
        m.put("resultsRows", engine.results().size());
        m.put("document", d.id().value());
        String what = count + " " + (count == 1 ? noun.substring(0, noun.length() - 1) : noun);
        // without add the ROI list holds earlier objects, not these: show the image instead of pointing at the wrong ones
        m.put("highlight", added ? Highlight.rois(d.id(), found, what, HIGHLIGHT_LIMIT, true) : Highlight.canvas(d.id(), what));
        return m;
    }

    private Plan analyzeParticles(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        CommandSchema s = schema(PennyCommands.ANALYZE_PARTICLES);
        Map<String, Object> user = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) asMap(PennyJson.toJava(a))).entrySet()) {
            if (s.parameter(e.getKey()).isPresent()) user.put(e.getKey(), e.getValue());
        }
        Double min = PennyJson.num(a, "minSize");
        Double max = PennyJson.num(a, "maxSize");
        if (!user.containsKey("size") && (min != null || max != null))
            user.put("size", Params.number(min == null ? 0 : min) + "-" + (max == null ? "Infinity" : Params.number(max)));
        Double minC = PennyJson.num(a, "minCircularity");
        if (!user.containsKey("circularity") && minC != null) {
            if (minC < 0 || minC > 1) throw new IllegalArgumentException("minCircularity must be in 0..1");
            user.put("circularity", String.format(Locale.ROOT, "%.2f-1.00", minC));
        }
        if (!user.containsKey("add") && PennyJson.has(a, "addToManager")) user.put("add", PennyJson.bool(a, "addToManager"));
        Map<String, Object> recipe = map("size", "0-Infinity", "pixel", false, "circularity", "0.00-1.00", "show", "Nothing", "display", true,
                "exclude", false, "clear", true, "include", false, "summarize", false, "overlay", false, "add", true, "composite", false,
                "record", false, "in_situ", false, "stack", false);
        p.steps.add(commandStep(p, "particles", PennyCommands.ANALYZE_PARTICLES, user, recipe, TargetScope.of(d.id())));
        p.finish = (plan, res) -> particlesResult(plan, res, d, "particles");
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    /** Fiji-protocol measurement names to Set Measurements keys. */
    static String measurementKey(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace("-", "_");
        return switch (n) {
            case "area" -> "area";
            case "mean", "mean_gray_value", "intensity", "mean_intensity" -> "mean";
            case "min", "max", "min_max", "minmax", "minimum", "maximum" -> "min";
            case "standard", "std", "sd", "stddev", "standard_deviation" -> "standard";
            case "modal", "mode" -> "modal";
            case "centroid" -> "centroid";
            case "center", "center_of_mass", "centre_of_mass" -> "center";
            case "perimeter", "perim" -> "perimeter";
            case "bounding", "bounding_rectangle", "rect", "bbox", "bounding_box" -> "bounding";
            case "fit", "ellipse", "fit_ellipse" -> "fit";
            case "shape", "circularity", "shape_descriptors", "solidity", "roundness" -> "shape";
            case "feret", "feret's", "ferets", "feret_diameter" -> "feret's";
            case "integrated", "intden", "integrated_density", "rawintden" -> "integrated";
            case "median" -> "median";
            case "skewness", "skew" -> "skewness";
            case "kurtosis", "kurt" -> "kurtosis";
            case "area_fraction", "%area" -> "area_fraction";
            case "stack", "stack_position" -> "stack";
            default -> throw new IllegalArgumentException("unknown measurement '" + name + "' (area, mean, min, max, standard, modal, centroid, center, "
                    + "perimeter, bounding, fit, shape, feret, integrated, median, skewness, kurtosis, area_fraction, stack)");
        };
    }

    /** Results columns of a Set Measurements key. */
    static List<String> columns(String key) {
        return switch (key) {
            case "area" -> List.of("Area");
            case "mean" -> List.of("Mean");
            case "min" -> List.of("Min", "Max");
            case "standard" -> List.of("StdDev");
            case "modal" -> List.of("Mode");
            case "centroid" -> List.of("X", "Y");
            case "center" -> List.of("XM", "YM");
            case "perimeter" -> List.of("Perim.");
            case "bounding" -> List.of("BX", "BY", "Width", "Height");
            case "fit" -> List.of("Major", "Minor", "Angle");
            case "shape" -> List.of("Circ.", "AR", "Round", "Solidity");
            case "feret's" -> List.of("Feret", "FeretX", "FeretY", "FeretAngle", "MinFeret");
            case "integrated" -> List.of("IntDen", "RawIntDen");
            case "median" -> List.of("Median");
            case "skewness" -> List.of("Skew");
            case "kurtosis" -> List.of("Kurt");
            case "area_fraction" -> List.of("%Area");
            case "stack" -> List.of("Ch", "Slice", "Frame");
            default -> List.of();
        };
    }

    private Plan measure(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        TargetScope ts = TargetScope.of(d.id());
        String scope = PennyJson.str(a, "scope", "roi").toLowerCase(Locale.ROOT);
        if (scope.equals("manager")) scope = "rois";
        List<Object> names = PennyJson.list(a, "measurements");
        Set<String> wanted = new LinkedHashSet<>();
        if (names != null) for (Object o : names) wanted.add(measurementKey(String.valueOf(o)));
        if (!wanted.isEmpty() && !currentMeasurements().containsAll(wanted)) p.steps.add(setMeasurementsStep(p, wanted, true, d));
        Optional<RoiGeometry> active = engine.activeRoi(d.id());
        // On a multichannel image intensities come from the channel asked for, else the channel the objects were segmented
        // on (remembered from the last segment_cells of this document), never silently from the displayed channel.
        ImagePlus live = imp(d.id());
        final int shownChannel = live == null ? 1 : live.getC();
        Integer channelArg = PennyJson.has(a, "channel") ? PennyJson.integer(a, "channel", 1, 1, Math.max(1, d.nChannels())) : null;
        final Integer useChannel = d.nChannels() > 1 ? (channelArg != null ? channelArg : segmentedChannel.get(d.id())) : null;
        final String channelBy = useChannel == null ? null : channelArg != null ? "argument" : "segmented";
        boolean switchChannel = useChannel != null && useChannel != shownChannel;
        if (switchChannel && !scope.equals("rois"))
            p.steps.add(directStep(p, "channel", Operations.SET_POSITION, "Channel " + useChannel + (channelArg != null ? "" : " (segmented there)"), "Image>Stacks>Set Channel",
                    map("channel", useChannel), prov("channel", channelArg != null ? Params.USER : Params.DERIVED), "Stack.setChannel(" + useChannel + ");", ts));
        // the channels actually measured, and what chose each (argument, segmented, roi position, displayed): the result
        // reports the channel measured and that rule, never the argument's number for a channel the ROI's position decided
        final Set<Integer> measuredChannels = new LinkedHashSet<>();
        final Set<String> measuredBy = new LinkedHashSet<>();
        switch (scope) {
            case "roi" -> {
                p.steps.add(commandStep(p, "measure", PennyCommands.MEASURE, Map.of(), Map.of(), ts));
                if (d.nChannels() > 1) {
                    measuredChannels.add(useChannel != null ? useChannel : shownChannel);
                    measuredBy.add(useChannel != null ? channelBy : "displayed");
                }
            }
            case "all" -> {
                if (active.isPresent()) p.steps.add(directStep(p, "clear", Operations.CLEAR_ROI, "Select None", "Edit>Selection>Select None",
                        Map.of(), Map.of(), "run(\"Select None\");", ts));
                p.steps.add(commandStep(p, "measure", PennyCommands.MEASURE, Map.of(), Map.of(), ts));
                if (d.nChannels() > 1) {
                    measuredChannels.add(useChannel != null ? useChannel : shownChannel);
                    measuredBy.add(useChannel != null ? channelBy : "displayed");
                }
                if (active.isPresent()) p.steps.add(directStep(p, "restore", Operations.SET_ROI, "Restore the selection", "Edit>Selection>Restore Selection",
                        map("roi", active.get()), prov("roi", Params.DERIVED), "// restore the selection", ts));
            }
            case "rois" -> {
                List<RoiGeometry> rois = engine.rois(d.id());
                if (rois.isEmpty()) throw new Failure(409, "The ROI list is empty; segment or add regions first.");
                if (rois.size() > 1000) throw new Failure(409, "The ROI list has " + rois.size() + " entries; measure at most 1000 at once.");
                boolean moved = false;
                ImagePlus start = imp(d.id());
                int c0 = start == null ? 1 : start.getC(), z0 = start == null ? 1 : start.getZ(), t0 = start == null ? 1 : start.getT();
                for (int i = 0; i < rois.size(); i++) {
                    int[] pos = rois.get(i).position();
                    Integer pc = null, pz = null, pt = null;
                    if (pos != null && pos.length == 3) {
                        pc = pos[0] > 0 ? pos[0] : null;
                        pz = pos[1] > 0 ? pos[1] : null;
                        pt = pos[2] > 0 ? pos[2] : null;
                    }
                    // the channel asked for wins over the ROI's own (objects found on the DAPI channel, intensity wanted on
                    // another); an ROI without a channel of its own is measured on the segmented channel, else where it is
                    String by;
                    if (channelArg != null) {
                        pc = channelArg;
                        by = "argument";
                    } else if (pc != null) {
                        by = "roi position";
                    } else if (useChannel != null) {
                        pc = useChannel;
                        by = "segmented";
                    } else {
                        by = "displayed";
                    }
                    boolean placed = pc != null || pz != null || pt != null;
                    moved |= placed;
                    if (d.nChannels() > 1) {
                        measuredChannels.add(pc != null ? pc : c0);
                        measuredBy.add(by);
                    }
                    // unspecified coordinates mean the plane the measurement started on, not wherever a previous ROI left the image
                    TargetScope roiTarget = d.isStack()
                            ? new TargetScope(d.id(), pc != null ? pc : c0, pz != null ? pz : z0, pt != null ? pt : t0, false, i)
                            : new TargetScope(d.id(), null, null, null, false, i);
                    Step s = commandStep(p, "measure", PennyCommands.MEASURE, Map.of(), Map.of(), roiTarget);
                    p.steps.add(new Step(s.role(), "Measure ROI " + (i + 1) + " of " + rois.size(), s.menuPath(), s.request(), s.provenance(), s.preview()));
                }
                ImagePlus now = imp(d.id());
                if (moved && now != null)
                    p.steps.add(directStep(p, "position", Operations.SET_POSITION, "Back to channel " + now.getC() + ", slice " + now.getZ() + ", frame " + now.getT(),
                            "Image>Stacks", map("channel", now.getC(), "slice", now.getZ(), "frame", now.getT()), Map.of(),
                            "Stack.setPosition(" + now.getC() + ", " + now.getZ() + ", " + now.getT() + ");", ts));
                if (active.isPresent()) p.steps.add(directStep(p, "restore", Operations.SET_ROI, "Restore the selection", "Edit>Selection>Restore Selection",
                        map("roi", active.get()), prov("roi", Params.DERIVED), "// restore the selection", ts));
                else p.steps.add(directStep(p, "restore", Operations.CLEAR_ROI, "Select None", "Edit>Selection>Select None", Map.of(), Map.of(),
                        "run(\"Select None\");", ts));
            }
            default -> throw new IllegalArgumentException("scope must be roi, all or rois");
        }
        if (switchChannel && !scope.equals("rois") && live != null)
            p.steps.add(directStep(p, "position", Operations.SET_POSITION, "Back to channel " + shownChannel, "Image>Stacks",
                    map("channel", shownChannel, "slice", live.getZ(), "frame", live.getT()), Map.of(),
                    "Stack.setPosition(" + shownChannel + ", " + live.getZ() + ", " + live.getT() + ");", ts));
        final String sc = scope;
        p.finish = (plan, res) -> {
            Set<String> ids = new LinkedHashSet<>();
            for (int i : plan.indices("measure")) {
                HistoryRecord r = res.get(i).record();
                if (r != null) ids.add(r.actionId());
            }
            List<MeasurementRow> rows = new ArrayList<>();
            for (MeasurementRow r : engine.results()) if (ids.contains(r.actionId())) rows.add(r);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rows", rows.size());
            m.put("totalRows", engine.results().size());
            m.put("columns", engine.resultColumns().headings());
            m.put("lastRow", rows.isEmpty() ? Map.of() : rowJson(rows.get(rows.size() - 1)));
            List<String> cols = new ArrayList<>();
            if (!wanted.isEmpty()) for (String k : wanted) cols.addAll(columns(k));
            else if (!rows.isEmpty()) for (String h : rows.get(rows.size() - 1).values().keySet()) if (cols.size() < 16) cols.add(h);
            Map<String, Object> summary = new LinkedHashMap<>();
            for (String col : cols) {
                List<Double> v = new ArrayList<>();
                for (MeasurementRow r : rows) if (r.values().containsKey(col)) v.add(r.values().get(col));
                Map<String, Object> st = stats(v);
                if (st != null) {
                    st.remove("median");
                    summary.put(col.toLowerCase(Locale.ROOT).replace(".", ""), st);
                }
            }
            m.put("summary", summary);
            m.put("unit", d.unit());
            m.put("scope", sc);
            if (measuredChannels.size() == 1) {
                m.put("channel", measuredChannels.iterator().next());
                m.put("channelBy", measuredBy.size() == 1 ? measuredBy.iterator().next() : "roi position");
            } else if (!measuredChannels.isEmpty()) {
                m.put("channels", new ArrayList<>(measuredChannels));
                m.put("channelBy", "roi position");
            }
            if (sc.equals("rois")) m.put("highlight", Highlight.rois(d.id(), engine.rois(d.id()), "Measured " + rows.size() + " regions", HIGHLIGHT_LIMIT, true));
            else if (sc.equals("roi") && active.isPresent()) m.put("highlight", Highlight.rois(d.id(), List.of(active.get()), "Measured the selection", 1, true));
            else m.put("highlight", Highlight.canvas(d.id(), "Measured the whole image"));
            return m;
        };
        return p;
    }

    private Plan threshold(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        TargetScope ts = TargetScope.of(d.id());
        Double lower = PennyJson.num(a, "lower");
        Double upper = PennyJson.num(a, "upper");
        String method;
        if (lower != null || upper != null) {
            if (lower == null || upper == null) throw new IllegalArgumentException("give both lower and upper (or neither, for an automatic method)");
            if (lower > upper) throw new IllegalArgumentException("lower must not exceed upper");
            method = "manual";
            p.steps.add(directStep(p, "threshold", Operations.SET_THRESHOLD, "Threshold " + Params.number(lower) + "–" + Params.number(upper),
                    "Image>Adjust>Threshold...", map("lower", lower, "upper", upper), prov("lower", Params.USER, "upper", Params.USER),
                    "setThreshold(" + Params.number(lower) + ", " + Params.number(upper) + ");", ts));
        } else {
            method = method(a, "Otsu");
            Boolean darkArg = PennyJson.bool(a, "dark");
            boolean dark = darkArg != null ? darkArg : darkFor(d);
            p.steps.add(directStep(p, "threshold", Operations.SET_AUTO_THRESHOLD, "Auto threshold " + method + (dark ? " (dark background)" : ""),
                    "Image>Adjust>Threshold...", map("method", method, "dark", dark),
                    prov("method", PennyJson.has(a, "method") ? Params.USER : Params.DERIVED, "dark", darkArg != null ? Params.USER : Params.DERIVED),
                    "setAutoThreshold(\"" + method + (dark ? " dark" : "") + "\");", ts));
        }
        p.finish = (plan, res) -> {
            ImageProcessor ip = LiveRead.processor(imp(d.id()));
            double lo = ip == null ? Double.NaN : ip.getMinThreshold();
            double hi = ip == null ? Double.NaN : ip.getMaxThreshold();
            boolean set = ip != null && lo != ImageProcessor.NO_THRESHOLD;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lower", set ? lo : null);
            m.put("upper", set ? hi : null);
            m.put("method", method);
            Object dk = plan.steps.get(0).request().params().get("dark");
            if (dk != null) m.put("dark", dk);
            m.put("highlight", Highlight.canvas(d.id(), "Thresholded"));
            return m;
        };
        return p;
    }

    private Plan filter(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        TargetScope ts = TargetScope.of(d.id());
        String name = PennyJson.str(a, "name", "").toLowerCase(Locale.ROOT).replace(' ', '_');
        Map<String, Object> user = new LinkedHashMap<>();
        Map<String, Object> recipe = new LinkedHashMap<>();
        Boolean stack = PennyJson.bool(a, "stack");
        String id;
        switch (name) {
            case "gaussian", "gaussian_blur", "blur" -> {
                id = PennyCommands.GAUSSIAN;
                Double s = PennyJson.num(a, "sigma");
                if (s == null) s = PennyJson.num(a, "radius");
                if (s != null) user.put("sigma", s);
                else recipe.put("sigma", 2.0);
                recipe.put("scaled", false);
            }
            case "median", "mean" -> {
                id = name.equals("median") ? PennyCommands.MEDIAN : PennyCommands.MEAN;
                Double r = PennyJson.num(a, "radius");
                if (r != null) user.put("radius", r);
                else recipe.put("radius", 2.0);
            }
            case "unsharp", "unsharp_mask" -> {
                id = PennyCommands.UNSHARP;
                Double r = PennyJson.num(a, "radius");
                if (r == null) r = PennyJson.num(a, "sigma");
                if (r != null) user.put("radius", r);
                else recipe.put("radius", 1.0);
                Double w = PennyJson.num(a, "mask");
                if (w == null) w = PennyJson.num(a, "weight");
                if (w != null) user.put("mask", w);
                else recipe.put("mask", 0.6);
            }
            case "subtract_background", "background" -> {
                id = PennyCommands.SUBTRACT_BACKGROUND;
                Double r = PennyJson.num(a, "rolling");
                if (r == null) r = PennyJson.num(a, "radius");
                if (r != null) user.put("rolling", r);
                else recipe.put("rolling", 50.0);
                Boolean light = PennyJson.bool(a, "light");
                if (light != null) user.put("light", light);
                else recipe.put("light", false);
                Boolean sliding = PennyJson.bool(a, "sliding");
                if (sliding != null) user.put("sliding", sliding);
                else recipe.put("sliding", false);
                recipe.put("separate", false);
                recipe.put("create", false);
                recipe.put("disable", false);
            }
            case "enhance_contrast", "contrast" -> {
                id = PennyCommands.ENHANCE_CONTRAST;
                Double s = PennyJson.num(a, "saturated");
                if (s != null) user.put("saturated", s);
                else recipe.put("saturated", 0.35);
                Boolean n = PennyJson.bool(a, "normalize");
                if (n != null) user.put("normalize", n);
                else recipe.put("normalize", false);
                Boolean e = PennyJson.bool(a, "equalize");
                if (e != null) user.put("equalize", e);
                else recipe.put("equalize", false);
                if (stack != null) user.put("process_all", stack);
                else recipe.put("process_all", false);
                recipe.put("use", false);
                stack = null;
            }
            case "invert" -> id = PennyCommands.INVERT;
            case "smooth" -> id = PennyCommands.SMOOTH;
            case "sharpen" -> id = PennyCommands.SHARPEN;
            case "find_edges", "edges" -> id = PennyCommands.FIND_EDGES;
            default -> throw new IllegalArgumentException("name must be gaussian, median, mean, unsharp, subtract_background, enhance_contrast, invert, smooth, sharpen or find_edges");
        }
        CommandSchema s = schema(id);
        if (s.parameter("stack").isPresent()) {
            if (stack != null) user.put("stack", stack);
            else recipe.put("stack", false);
        }
        p.steps.add(commandStep(p, "filter", id, user, recipe, ts));
        p.finish = (plan, res) -> map("step", step(plan.steps.get(0), res.get(0)), "highlight", Highlight.canvas(d.id(), s.label()));
        return p;
    }

    private Plan convert(Call c) {
        DocumentInfo d = requireDoc();
        String to = PennyJson.str(c.args(), "to", "").trim();
        String id = switch (to.toLowerCase(Locale.ROOT)) {
            case "8-bit", "8bit", "8" -> PennyCommands.TO_8_BIT;
            case "16-bit", "16bit", "16" -> PennyCommands.TO_16_BIT;
            case "32-bit", "32bit", "32" -> PennyCommands.TO_32_BIT;
            case "rgb", "rgb color", "rgb_color" -> PennyCommands.TO_RGB;
            default -> throw new IllegalArgumentException("to must be 8-bit, 16-bit, 32-bit or RGB");
        };
        Plan p = new Plan(c, d.id());
        p.steps.add(commandStep(p, "convert", id, Map.of(), Map.of(), TargetScope.of(d.id())));
        p.finish = (plan, res) -> map("step", step(plan.steps.get(0), res.get(0)),
                "document", engine.document(d.id()).map(this::documentJson).orElse(null));
        return p;
    }

    private Plan duplicate(Call c) {
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        Map<String, Object> user = new LinkedHashMap<>();
        String title = PennyJson.str(c.args(), "title", null);
        if (title != null && !title.isBlank()) user.put("title", title);
        Map<String, Object> recipe = map("title", d.title().replaceFirst("\\.[A-Za-z0-9]{2,4}$", "") + "-1", "ignore", false,
                "duplicate", d.isStack(), "range", "1-" + Math.max(1, d.nChannels() * d.nSlices() * d.nFrames()),
                "channels", "1-" + d.nChannels(), "slices", "1-" + d.nSlices(), "frames", "1-" + d.nFrames());
        p.steps.add(commandStep(p, "duplicate", PennyCommands.DUPLICATE, user, recipe, TargetScope.of(d.id())));
        p.finish = (plan, res) -> {
            ActionResult r = res.get(0);
            DocumentId added = r.digest() == null || r.digest().added().isEmpty() ? null : r.digest().added().get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("document", added == null ? null : engine.document(added).map(this::documentJson).orElse(null));
            m.put("step", step(plan.steps.get(0), r));
            if (added != null) m.put("highlight", Highlight.canvas(added, "Duplicated"));
            return m;
        };
        return p;
    }

    private Plan selectRoi(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        TargetScope ts = TargetScope.of(d.id());
        String type = PennyJson.str(a, "type", "rectangle").toLowerCase(Locale.ROOT);
        String name = PennyJson.str(a, "name", null);
        if (type.equals("none")) {
            p.steps.add(directStep(p, "roi", Operations.CLEAR_ROI, "Select None", "Edit>Selection>Select None", Map.of(), Map.of(),
                    "run(\"Select None\");", ts));
        } else {
            RoiGeometry g;
            if (type.equals("rectangle") || type.equals("rect") || type.equals("oval") || type.equals("ellipse")) {
                Double x = PennyJson.num(a, "x"), y = PennyJson.num(a, "y"), w = PennyJson.num(a, "width"), h = PennyJson.num(a, "height");
                if (x == null || y == null || w == null || h == null) throw new IllegalArgumentException(type + " needs x, y, width and height (image pixels)");
                if (w <= 0 || h <= 0) throw new IllegalArgumentException("width and height must be positive");
                String t = type.startsWith("rect") ? "RECTANGLE" : "OVAL";
                g = new RoiGeometry(t, List.of(x.floatValue(), (float) (x + w)), List.of(y.floatValue(), (float) (y + h)),
                        new double[] {x, y, w, h}, name, null, null);
            } else if (type.equals("polygon")) {
                List<Object> pts = PennyJson.list(a, "points");
                if (pts == null || pts.size() < 3) throw new IllegalArgumentException("polygon needs at least three points [[x,y],…]");
                List<Float> xs = new ArrayList<>(), ys = new ArrayList<>();
                for (Object o : pts) {
                    if (!(o instanceof List<?> pt) || pt.size() != 2 || !(pt.get(0) instanceof Number px) || !(pt.get(1) instanceof Number py))
                        throw new IllegalArgumentException("each point must be [x, y]");
                    xs.add(px.floatValue());
                    ys.add(py.floatValue());
                }
                g = new RoiGeometry("POLYGON", xs, ys, null, name, null, null);
            } else throw new IllegalArgumentException("type must be rectangle, oval, polygon or none");
            Rectangle2D b = Highlight.bounds(g);
            String preview;
            if (g.type().equals("POLYGON")) {
                StringBuilder xs = new StringBuilder(), ys = new StringBuilder();
                for (int i = 0; i < g.xs().size(); i++) {
                    xs.append(i == 0 ? "" : ", ").append(Params.number(g.xs().get(i)));
                    ys.append(i == 0 ? "" : ", ").append(Params.number(g.ys().get(i)));
                }
                preview = "makeSelection(\"polygon\", newArray(" + xs + "), newArray(" + ys + "));";
            } else {
                preview = (g.type().equals("OVAL") ? "makeOval(" : "makeRectangle(") + Params.number(b.getX()) + ", " + Params.number(b.getY())
                        + ", " + Params.number(b.getWidth()) + ", " + Params.number(b.getHeight()) + ");";
            }
            p.steps.add(directStep(p, "roi", Operations.SET_ROI, "Select " + g.type().toLowerCase(Locale.ROOT)
                            + String.format(Locale.ROOT, " %.0f×%.0f at %.0f,%.0f", b.getWidth(), b.getHeight(), b.getX(), b.getY()),
                    "Edit>Selection", map("roi", g), prov("roi", Params.USER), preview, ts));
        }
        p.finish = (plan, res) -> {
            Optional<RoiGeometry> roi = engine.activeRoi(d.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roi", roiJson(roi.orElse(null)));
            if (roi.isPresent()) m.put("highlight", Highlight.rois(d.id(), List.of(roi.get()), "Selection", 1, true));
            return m;
        };
        return p;
    }

    private Plan display(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        Double min = PennyJson.num(a, "min"), max = PennyJson.num(a, "max");
        Map<String, String> pv;
        Integer channel = PennyJson.has(a, "channel") ? PennyJson.integer(a, "channel", 1, 1, Math.max(1, d.nChannels())) : null;
        if (PennyJson.bool(a, "auto", false) || (min == null && max == null)) {
            double[] range = pixelRange(d.id(), channel);
            if (range == null) throw new Failure(409, "The document's pixels are not available.");
            if (min == null) min = range[0];
            if (max == null) max = range[1];
            pv = prov("min", PennyJson.has(a, "min") ? Params.USER : Params.DERIVED, "max", PennyJson.has(a, "max") ? Params.USER : Params.DERIVED);
        } else {
            if (min == null || max == null) throw new IllegalArgumentException("give min and max, or auto:true");
            pv = prov("min", Params.USER, "max", Params.USER);
        }
        if (!(min < max)) throw new IllegalArgumentException("min must be less than max (the image is flat at " + Params.number(min) + ")");
        Map<String, Object> params = map("min", min, "max", max);
        if (channel != null) params.put("channel", channel);
        p.steps.add(directStep(p, "display", Operations.SET_DISPLAY_RANGE, "Display range " + Params.number(min) + "–" + Params.number(max),
                "Image>Adjust>Brightness/Contrast...", params, pv, "setMinAndMax(" + Params.number(min) + ", " + Params.number(max) + ");",
                TargetScope.of(d.id())));
        final double lo = min, hi = max;
        p.finish = (plan, res) -> map("min", lo, "max", hi, "step", step(plan.steps.get(0), res.get(0)));
        return p;
    }

    /**
     * Raw min/max of one plane (the requested channel at the current slice and frame), read from the pixel array: no
     * ImageJ statistics call, which would set the live processor's ROI while the engine may be using it.
     */
    private double[] pixelRange(DocumentId id, Integer channel) {
        Object pixels = LiveRead.planePixels(imp(id), channel);
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        if (pixels instanceof byte[] b) for (byte v : b) { int x = v & 0xff; lo = Math.min(lo, x); hi = Math.max(hi, x); }
        else if (pixels instanceof short[] sh) for (short v : sh) { int x = v & 0xffff; lo = Math.min(lo, x); hi = Math.max(hi, x); }
        else if (pixels instanceof float[] f) for (float v : f) { if (Float.isFinite(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); } }
        else if (pixels instanceof int[] rgb) for (int v : rgb) {
            for (int sh = 0; sh <= 16; sh += 8) { int x = (v >> sh) & 0xff; lo = Math.min(lo, x); hi = Math.max(hi, x); }
        } else return null;
        return Double.isFinite(lo) ? new double[] {lo, hi} : null;
    }

    private Plan stack(Call c) {
        JsonObject a = c.args();
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, String> pv = new LinkedHashMap<>();
        if (PennyJson.has(a, "channel")) params.put("channel", PennyJson.integer(a, "channel", 1, 1, Math.max(1, d.nChannels())));
        if (PennyJson.has(a, "slice")) params.put("slice", PennyJson.integer(a, "slice", 1, 1, Math.max(1, d.nSlices())));
        if (PennyJson.has(a, "frame")) params.put("frame", PennyJson.integer(a, "frame", 1, 1, Math.max(1, d.nFrames())));
        if (params.isEmpty()) throw new IllegalArgumentException("give slice, channel or frame");
        for (String k : params.keySet()) pv.put(k, Params.USER);
        ImagePlus now = imp(d.id());
        int pc = (int) params.getOrDefault("channel", now == null ? 1 : now.getC());
        int pz = (int) params.getOrDefault("slice", now == null ? 1 : now.getZ());
        int pt = (int) params.getOrDefault("frame", now == null ? 1 : now.getT());
        p.steps.add(directStep(p, "position", Operations.SET_POSITION, "Go to channel " + pc + ", slice " + pz + ", frame " + pt, "Image>Stacks",
                params, pv, "Stack.setPosition(" + pc + ", " + pz + ", " + pt + ");", TargetScope.of(d.id())));
        p.finish = (plan, res) -> {
            ImagePlus imp = imp(d.id());
            return map("position", imp == null ? null : map("channel", imp.getC(), "slice", imp.getZ(), "frame", imp.getT()),
                    "step", step(plan.steps.get(0), res.get(0)));
        };
        return p;
    }

    private Plan undoRedo(Call c, String op) {
        DocumentInfo d = requireDoc();
        Plan p = new Plan(c, d.id());
        boolean undo = op.equals(Operations.UNDO);
        int n = PennyJson.integer(c.args(), "steps", 1, 1, 20);
        for (int i = 0; i < n; i++) {
            String label = (undo ? "Undo" : "Redo") + (n == 1 ? "" : " " + (i + 1) + " of " + n);
            p.steps.add(directStep(p, undo ? "undo" : "redo", op, label, undo ? "Edit>Undo" : "Edit>Redo", Map.of(), Map.of(),
                    undo ? "run(\"Undo\");" : "// redo", TargetScope.of(d.id())));
        }
        p.finish = (plan, res) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", step(plan.steps.get(res.size() - 1), res.get(res.size() - 1)));
            List<Object> undone = new ArrayList<>();
            for (ActionResult x : res) {
                HistoryRecord r = x.record();
                if (r != null && r.params().get("undoneActionId") != null) undone.add(r.params().get("undoneActionId"));
            }
            if (!undone.isEmpty()) m.put("undoneActionId", undone.get(0));
            if (!undone.isEmpty()) m.put("undoneActionIds", undone);
            // what the engine put back over all undone steps: pixels, the ROI list (first before -> last after), Results rows
            Map<String, Object> restored = new LinkedHashMap<>();
            Integer roisFirst = null, roisLast = null, rowsFirst = null, rowsLast = null;
            boolean pixels = false;
            for (ActionResult x : res) {
                HistoryRecord r = x.record();
                if (r == null || !undo) continue;
                pixels |= Boolean.TRUE.equals(r.params().get("pixelsRestored"));
                if (r.params().get("roisBefore") instanceof Number b && r.params().get("roisAfter") instanceof Number a2) {
                    if (roisFirst == null) roisFirst = b.intValue();
                    roisLast = a2.intValue();
                }
                if (r.params().get("resultsRowsBefore") instanceof Number b && r.params().get("resultsRowsAfter") instanceof Number a2) {
                    if (rowsFirst == null) rowsFirst = b.intValue();
                    rowsLast = a2.intValue();
                }
            }
            if (undo && !undone.isEmpty()) {
                restored.put("pixels", pixels);
                if (roisFirst != null) restored.put("rois", map("before", roisFirst, "after", roisLast));
                if (rowsFirst != null) restored.put("resultsRows", map("before", rowsFirst, "after", rowsLast));
                m.put("restored", restored);
            }
            m.put("document", engine.document(d.id()).map(this::documentJson).orElse(null));
            m.put("highlight", Highlight.canvas(d.id(), undo ? "Undone" : "Redone"));
            return m;
        };
        return p;
    }

    private Plan runMacro(Call c) {
        String code = PennyJson.str(c.args(), "code", "");
        if (code.isBlank()) throw new IllegalArgumentException("'code' is required");
        if (code.length() > 32_000) throw new IllegalArgumentException("'code' is longer than 32000 characters");
        Optional<DocumentInfo> d = engine.focused().flatMap(engine::document);
        Plan p = new Plan(c, d.map(DocumentInfo::id).orElse(null));
        Map<String, Object> params = map("language", "ijm", "text", code, "transcriptRef", p.transcriptRef);
        TargetScope ts = p.doc == null ? null : TargetScope.of(p.doc);
        ActionRequest r = new ActionRequest(UUID.randomUUID().toString(), p.origin, ActionKind.CODE, null, "RUN_CODE", params, ts, null, null);
        String[] lines = code.split("\n");
        String preview = lines.length <= 12 ? code : String.join("\n", java.util.Arrays.copyOf(lines, 12)) + "\n// … " + (lines.length - 12) + " more lines";
        p.steps.add(new Step("macro", "Run macro (" + lines.length + (lines.length == 1 ? " line)" : " lines)"), "Plugins>Macros>Run...", r,
                Map.of(), preview));
        // The engine's capability check: the first submit without a token answers AWAITING_CONFIRMATION and issues one.
        ActionResult first;
        try {
            first = engine.submit(r).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new Failure(409, "The engine did not answer the macro check: " + e);
        }
        if (first.state() != OperationState.AWAITING_CONFIRMATION || first.confirmationToken() == null) {
            String msg = first.error() == null ? "the engine answered " + first.state() : first.error().message();
            throw new Failure(409, msg, map("code", first.error() == null ? null : String.valueOf(first.error().code())));
        }
        p.codeToken = first.confirmationToken();
        // the engine's own wording (it names the capabilities its scan found)
        p.engineReasons.add(first.error() == null ? "macro code runs with full privileges" : first.error().message());
        p.finish = (plan, res) -> {
            ActionResult x = res.get(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", step(plan.steps.get(0), x));
            if (x.digest() != null) {
                m.put("resultsRowsAdded", x.digest().resultsRowsAdded());
                m.put("log", x.digest().newLogLines().size() > 50 ? x.digest().newLogLines().subList(0, 50) : x.digest().newLogLines());
                List<DocumentId> added = x.digest().added();
                m.put("documentsAdded", added.stream().map(DocumentId::value).toList());
                // a replay that re-opens the file works on its own copy: show that copy, so the result is on screen
                if (!added.isEmpty()) {
                    DocumentId shown = added.get(added.size() - 1);
                    engine.focus(shown);
                    m.put("focused", shown.value());
                }
            }
            return m;
        };
        return p;
    }
}
