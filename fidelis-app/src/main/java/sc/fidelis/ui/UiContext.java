package sc.fidelis.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.CommandSchema;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.ConsequenceClass;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;

/**
 * Shared state and actions of one workspace: the engine, the frame, and the single path through
 * which every input modality (menu, command bar, panels, proposals) submits work.
 */
public final class UiContext {
    public final Engine engine;
    public final JFrame frame;
    private final Map<String, String> labels = new ConcurrentHashMap<>();
    private final Map<javax.swing.KeyStroke, String> accelerators = new ConcurrentHashMap<>();
    private StatusBar status;
    private ParametersPanel parameters;
    private CommandBar commandBar;
    private DocumentArea documents;
    private MacroEditorPanel editor;
    private Docks docks;
    private Banners banners;
    private LegacyWindows legacyWindows;

    UiContext(Engine engine, JFrame frame) {
        this.engine = engine;
        this.frame = frame;
    }

    void setStatus(StatusBar s) { this.status = s; }
    void setParameters(ParametersPanel p) { this.parameters = p; }
    void setCommandBar(CommandBar c) { this.commandBar = c; }
    void setDocuments(DocumentArea d) { this.documents = d; }
    void setEditor(MacroEditorPanel e) { this.editor = e; }
    void setDocks(Docks d) { this.docks = d; }
    void setBanners(Banners b) { this.banners = b; }
    void setLegacyWindows(LegacyWindows l) { this.legacyWindows = l; }

    StatusBar status() { return status; }
    ParametersPanel parameters() { return parameters; }
    CommandBar commandBar() { return commandBar; }
    DocumentArea documents() { return documents; }
    MacroEditorPanel editor() { return editor; }
    Docks docks() { return docks; }
    Banners banners() { return banners; }
    LegacyWindows legacyWindows() { return legacyWindows; }

    /** Window title: product name and the focused document (docs D12 UI-01). */
    /** Give a request submitted directly to the engine the label the Activity panel and notices show. */
    void registerLabel(String requestId, String label) {
        if (requestId != null && label != null) labels.put(requestId, label);
    }

    /** Remember one of Fiji's own menu accelerators (read from the IJ1 menu bar by {@link MenuBuilder}). */
    void registerAccelerator(javax.swing.KeyStroke ks, String commandId) {
        if (ks != null && commandId != null) accelerators.put(ks, commandId);
    }

    /** The Fiji command bound to a key stroke in the IJ1 menu bar, or null (DEC-031: nothing is re-mapped). */
    public String commandForShortcut(javax.swing.KeyStroke ks) {
        return ks == null ? null : accelerators.get(ks);
    }

    void frameTitleChanged() {
        String t = focusedInfo().map(DocumentInfo::title).orElse(null);
        frame.setTitle(t == null ? "Fidelis" : "Fidelis — " + t);
    }

    // ---- targets ------------------------------------------------------------------------------

    public DocumentId focusedId() {
        return engine.focused().orElse(null);
    }

    /** Explicit target of an action: the focused document, or null when none is open. */
    public TargetScope target() {
        DocumentId f = focusedId();
        return f == null ? null : TargetScope.of(f);
    }

    public Optional<DocumentInfo> focusedInfo() {
        DocumentId f = focusedId();
        return f == null ? Optional.empty() : engine.document(f);
    }

    void focusDocument(DocumentId id) {
        if (!id.equals(focusedId())) engine.focus(id);
        documents.select(id);
    }

    void focusCommandBar() {
        commandBar.focusField();
    }

    // ---- notices ------------------------------------------------------------------------------

    public void notice(String text, boolean error) {
        Theme.onEdt(() -> {
            if (status != null) status.setNotice(text, error);
        });
    }

    public String labelFor(String requestId) {
        return labels.get(requestId);
    }

    // ---- submit -------------------------------------------------------------------------------

    /** Submit a request and report its outcome in the status bar (never blocks the EDT). */
    public CompletableFuture<ActionResult> submit(ActionRequest req, String label) {
        labels.put(req.requestId(), label);
        DocumentArea.Snapshot snap = documents == null ? null : documents.beforeAction(req, label);
        CompletableFuture<ActionResult> f = engine.submit(req);
        f.whenComplete((r, t) -> Theme.onEdt(() -> {
            if (documents != null) documents.afterAction(snap, t == null ? r : null);
            if (t != null) {
                notice(label + " failed: " + t, true);
                return;
            }
            handleResult(req, label, r);
        }));
        return f;
    }

    private void handleResult(ActionRequest req, String label, ActionResult r) {
        switch (r.state()) {
            case COMPLETED -> notice("Done: " + label + " (" + r.durationMs() + " ms)", false);
            case AWAITING_CONFIRMATION -> confirmAndResubmit(req, label, r);
            case CANCELLED -> notice("Cancelled: " + label, false);
            case PARTIAL, FAILED, ROLLED_BACK -> notice(label + ": " + (r.error() != null ? r.error().message() : r.state().toString()), true);
            default -> {
            }
        }
    }

    private void confirmAndResubmit(ActionRequest req, String label, ActionResult r) {
        int choice = JOptionPane.showConfirmDialog(frame,
                label + " needs confirmation" + (r.error() != null ? ":\n" + r.error().message() : "."),
                "Confirm", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) submit(req.withConfirmation(r.confirmationToken()), label);
        else notice("Not confirmed: " + label, false);
    }

    // ---- commands -----------------------------------------------------------------------------

    /**
     * Run a catalogue command the way the menu does: commands with a complete schema open the
     * Parameters panel pre-filled; others are submitted with empty parameters, so the engine
     * surfaces the original Fiji dialog (DialogPending banner).
     */
    public void runCommand(String commandId, Origin origin) {
        runCommand(commandId, origin, null);
    }

    /** As {@link #runCommand(String, Origin)}, with form values pre-filled (for example from a panel). */
    public void runCommand(String commandId, Origin origin, Map<String, Object> prefill) {
        if (engine.state() != EngineState.READY) {
            notice("The engine is still starting (" + engine.state() + ")", false);
            return;
        }
        if (Routes.handle(this, commandId, origin)) return;
        Optional<CommandSchema> schema = engine.schema(commandId);
        if (schema.isPresent() && schema.get().schemaComplete() && !schema.get().parameters().isEmpty()) {
            parameters.show(schema.get(), prefill, origin);
            return;
        }
        String label = schema.map(CommandSchema::label).orElse(commandId.substring(commandId.lastIndexOf('>') + 1));
        submit(ActionRequest.command(origin, commandId, Map.of(), target()), label.trim());
    }

    // ---- session operations the UI initiates ---------------------------------------------------

    void openFileDialog() {
        JFileChooser fc = new JFileChooser(System.getProperty("user.home"));
        fc.setDialogTitle("Open image");
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        for (java.io.File f : fc.getSelectedFiles()) openPath(f.toPath());
    }

    public CompletableFuture<ActionResult> openPath(Path p) {
        return submit(ActionRequest.session(Origin.MENU, Operations.OPEN, Map.of("path", p.toString()), null),
                "Open " + p.getFileName());
    }

    /** Close a document, asking first when it has unsaved changes (AC6). */
    void closeDocument(DocumentId id) {
        Optional<DocumentInfo> info = engine.document(id);
        boolean discard = false;
        if (info.isPresent() && info.get().changed()) {
            int c = JOptionPane.showConfirmDialog(frame,
                    "\"" + info.get().title() + "\" has unsaved changes. Close it and discard them?",
                    "Close image", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (c != JOptionPane.OK_OPTION) return;
            discard = true;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("discard", discard);
        submit(ActionRequest.session(Origin.MENU, Operations.CLOSE, p, TargetScope.of(id)),
                "Close " + info.map(DocumentInfo::title).orElse("image"));
    }

    boolean isIrreversible(String commandId) {
        return engine.schema(commandId).map(s -> s.consequence().ordinal() >= ConsequenceClass.AC4_NON_UNDOABLE_DATA.ordinal()).orElse(false);
    }

    static boolean terminal(OperationState s) {
        return switch (s) {
            case COMPLETED, FAILED, CANCELLED, ROLLED_BACK, PARTIAL -> true;
            default -> false;
        };
    }

    /** Run on the EDT, from any thread. */
    static void edt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }
}
