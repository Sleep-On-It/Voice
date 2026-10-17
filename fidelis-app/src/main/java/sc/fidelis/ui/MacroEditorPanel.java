package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.text.DefaultCaret;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;

/**
 * Macro editor dock (issue #19, FR-MACRO-002): edit an ImageJ macro (RSyntaxTextArea, the editor Fiji's own
 * script editor uses), run it through the engine as a CODE request and watch its Log lines in a console.
 *
 * <p>Nothing runs without the capability confirmation: the first submit returns
 * {@code AWAITING_CONFIRMATION} with the capabilities the engine found in the text, the sheet shows them, and
 * only the explicit "Run with these capabilities" act resubmits with the token (bound to that exact text).
 * Cancel asks the engine to abort the running operation. Every run is recorded in the history by the engine,
 * with the steps it observed as children of the CODE step.
 */
public final class MacroEditorPanel extends JPanel {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final UiContext ctx;
    private final RSyntaxTextArea editor = new RSyntaxTextArea(12, 80);
    private final JTextArea console = new JTextArea();
    private final JButton load = new JButton("Load generated macro");
    private final JButton open = new JButton("Open .ijm…");
    private final JButton save = new JButton("Save .ijm…");
    private final JButton run = new JButton("Run");
    private final JButton cancel = new JButton("Cancel");
    private final JLabel status = new JLabel("Ready");

    private final JPanel sheet = new JPanel(new BorderLayout(8, 4));
    private final JLabel sheetText = new JLabel();
    private final JButton confirm = new JButton("Run with these capabilities");
    private final JButton decline = new JButton("Don't run");

    private ActionRequest pending;
    private String pendingToken;
    private String capabilities = "";
    private String runningRequestId;
    private String runningOperationId;
    private boolean cancelWhenKnown;
    private ActionResult lastResult;
    private int runs;

    MacroEditorPanel(UiContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        load.setToolTipText("Replace the text with the macro generated from the history (History & macro)");
        open.setToolTipText("Open an .ijm file");
        save.setToolTipText("Save the text as an .ijm file");
        run.setToolTipText("Run the text as an ImageJ macro through the engine; a capability confirmation comes first");
        cancel.setToolTipText("Abort the running macro");
        load.addActionListener(e -> setText(ctx.engine.exportMacro()));
        open.addActionListener(e -> openFile());
        save.addActionListener(e -> saveFile());
        run.addActionListener(e -> run());
        cancel.addActionListener(e -> cancel());
        cancel.setEnabled(false);
        status.setForeground(Theme.muted());
        bar.add(load);
        bar.add(open);
        bar.add(save);
        bar.add(run);
        bar.add(cancel);
        bar.add(status);

        sheet.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.warning()),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        sheet.setVisible(false);
        sheet.add(sheetText, BorderLayout.CENTER);
        JPanel acts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        confirm.addActionListener(e -> confirmRun());
        decline.addActionListener(e -> declineRun());
        acts.add(decline);
        acts.add(confirm);
        sheet.add(acts, BorderLayout.EAST);

        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.NORTH);
        north.add(sheet, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT); // the IJ macro language is JavaScript-like
        editor.setCodeFoldingEnabled(false);
        editor.setTabSize(2);
        editor.setAntiAliasingEnabled(true);
        editor.setText("// Type an ImageJ macro here, or load the one generated from the history.\n");
        applyEditorTheme();
        RTextScrollPane sp = new RTextScrollPane(editor);
        sp.setLineNumbersEnabled(true);
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.panelBorder()));

        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        console.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        ((DefaultCaret) console.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        JPanel consolePane = new JPanel(new BorderLayout());
        JLabel l = new JLabel("Console: Log lines of the running macro");
        l.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        l.setForeground(Theme.muted());
        consolePane.add(l, BorderLayout.NORTH);
        JScrollPane cs = new JScrollPane(console);
        cs.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.panelBorder()));
        consolePane.add(cs, BorderLayout.CENTER);
        consolePane.setMinimumSize(new java.awt.Dimension(0, 0));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sp, consolePane);
        split.setResizeWeight(0.64);
        split.setOneTouchExpandable(true);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
    }

    /** A theme switch (FlatLaf.updateUI) re-skins the tree; the editor's colours are re-applied once that is done. */
    @Override
    public void updateUI() {
        super.updateUI();
        if (editor != null) javax.swing.SwingUtilities.invokeLater(this::applyEditorTheme);
    }

    java.awt.Color editorBackground() {
        return editor.getBackground();
    }

    private void applyEditorTheme() {
        String name = Theme.isDark() ? "dark.xml" : "default.xml";
        try (InputStream in = RSyntaxTextArea.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/" + name)) {
            if (in != null) org.fife.ui.rsyntaxtextarea.Theme.load(in).apply(editor);
        } catch (IOException e) {
            // keep the editor's defaults
        }
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
    }

    // ---- text -----------------------------------------------------------------------------------

    void setText(String text) {
        editor.setText(text == null ? "" : text);
        editor.setCaretPosition(0);
        clearPending();
    }

    String text() {
        return editor.getText();
    }

    private void openFile() {
        JFileChooser fc = new JFileChooser(System.getProperty("user.home"));
        fc.setDialogTitle("Open macro");
        if (fc.showOpenDialog(ctx.frame) != JFileChooser.APPROVE_OPTION) return;
        try {
            setText(Files.readString(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8));
            status("Opened " + fc.getSelectedFile().getName());
        } catch (IOException e) {
            ctx.notice("Could not read " + fc.getSelectedFile() + ": " + e.getMessage(), true);
        }
    }

    private void saveFile() {
        JFileChooser fc = new JFileChooser(System.getProperty("user.home"));
        fc.setDialogTitle("Save macro");
        fc.setSelectedFile(new java.io.File("macro.ijm"));
        if (fc.showSaveDialog(ctx.frame) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.writeString(fc.getSelectedFile().toPath(), editor.getText(), StandardCharsets.UTF_8);
            status("Saved " + fc.getSelectedFile().getName());
        } catch (IOException e) {
            ctx.notice("Could not write " + fc.getSelectedFile() + ": " + e.getMessage(), true);
        }
    }

    // ---- run / confirm / cancel ----------------------------------------------------------------------

    /** Submit the text as a CODE request; the engine answers with the capabilities to confirm before anything runs. */
    void run() {
        if (isRunning()) {
            ctx.notice("A macro is already running", false);
            return;
        }
        String text = editor.getText();
        if (text.isBlank()) {
            ctx.notice("The editor is empty", false);
            return;
        }
        clearPending();
        TargetScope target = ctx.target();
        submit(ActionRequest.code(Origin.PANEL, "ijm", text, target, null));
    }

    private void submit(ActionRequest req) {
        runningRequestId = req.requestId();
        runningOperationId = null;
        cancelWhenKnown = false;
        ctx.registerLabel(req.requestId(), "Run macro");
        setBusy(true);
        boolean confirmed = req.confirmationToken() != null;
        if (!confirmed) runs++;
        append("Run " + runs + ": " + (confirmed ? "confirmed, running " : "submitted, ") + editor.getLineCount() + " line(s)"
                + (req.target() == null ? ", no focused image" : ""));
        status(confirmed ? "Running…" : "Checking…");
        ctx.engine.submit(req).whenComplete((r, t) -> Theme.onEdt(() -> finished(req, r, t)));
    }

    private void finished(ActionRequest req, ActionResult r, Throwable t) {
        if (!req.requestId().equals(runningRequestId)) return; // a result of an earlier run
        runningRequestId = null;
        runningOperationId = null;
        setBusy(false);
        if (t != null) {
            lastResult = null;
            append("Failed: " + t);
            status("Failed");
            ctx.notice("Macro failed: " + t, true);
            return;
        }
        lastResult = r;
        // Log lines written during the run arrive with the result (StateDigest.newLogLines); live LogLine events,
        // when the engine emits them, are appended by handle() as they come
        if (r.digest() != null && r.digest().newLogLines() != null)
            for (String line : r.digest().newLogLines()) append("  " + line);
        switch (r.state()) {
            case AWAITING_CONFIRMATION -> {
                pending = req;
                pendingToken = r.confirmationToken();
                capabilities = r.error() != null && r.error().context() != null ? r.error().context().getOrDefault("capabilities", "") : "";
                String caps = capabilities.isBlank() ? "no sensitive functions named; it can still change images, Results and files"
                        : "uses " + capabilities.replace(",", ", ");
                sheetText.setText("<html><b>Run this macro with Fiji's full privileges?</b> It " + caps
                        + ". Nothing has run yet; the confirmation is bound to this exact text.</html>");
                sheet.setVisible(true);
                revalidate();
                append("Awaiting confirmation (" + (capabilities.isBlank() ? "no sensitive capabilities" : "capabilities: " + capabilities) + ")");
                status("Awaiting confirmation");
            }
            case COMPLETED -> {
                int rows = r.digest() == null ? 0 : r.digest().resultsRowsAdded();
                append("Completed in " + r.durationMs() + " ms" + (rows > 0 ? ", " + rows + " Results row(s)" : "")
                        + (r.digest() == null || r.digest().changed().isEmpty() ? "" : ", changed " + r.digest().changed().size() + " document(s)"));
                status("Completed");
                ctx.notice("Done: macro (" + r.durationMs() + " ms)", false);
            }
            case CANCELLED -> {
                append("Cancelled" + (r.error() != null ? ": " + r.error().message() : ""));
                status("Cancelled");
                ctx.notice("Cancelled: macro", false);
            }
            default -> {
                String msg = r.error() != null ? r.error().message() : r.state().toString();
                append(r.state() + ": " + msg);
                status(r.state().toString());
                ctx.notice("Macro " + r.state().toString().toLowerCase() + ": " + msg, true);
            }
        }
    }

    /** The deliberate act: resubmit the pending text with the engine's token. */
    void confirmRun() {
        if (pending == null || pendingToken == null) return;
        ActionRequest req = pending.withConfirmation(pendingToken);
        clearPending();
        submit(req);
    }

    void declineRun() {
        if (pending == null) return;
        clearPending();
        append("Not run: confirmation declined");
        status("Ready");
    }

    private void clearPending() {
        pending = null;
        pendingToken = null;
        sheet.setVisible(false);
        revalidate();
    }

    /** Ask the engine to abort the running macro (as soon as its operation id is known). */
    void cancel() {
        if (runningRequestId == null) return;
        if (runningOperationId != null) {
            ctx.engine.cancel(runningOperationId);
            append("Cancel requested");
        } else {
            cancelWhenKnown = true;
            append("Cancel requested (waiting for the operation to start)");
        }
    }

    void handle(EngineEvent e) {
        if (runningRequestId == null) return;
        if (e instanceof EngineEvent.OperationStateChanged s && runningRequestId.equals(s.requestId()) && s.operationId() != null) {
            runningOperationId = s.operationId();
            cancel.setEnabled(true);
            if (cancelWhenKnown) {
                cancelWhenKnown = false;
                ctx.engine.cancel(runningOperationId);
                append("Cancel requested");
            }
        } else if (e instanceof EngineEvent.LogLine l) {
            append(l.line());
        } else if (e instanceof EngineEvent.OperationProgress p && p.operationId() != null && p.operationId().equals(runningOperationId)
                && p.statusText() != null && !p.statusText().isBlank()) {
            status(p.statusText());
        }
    }

    private void setBusy(boolean busy) {
        run.setEnabled(!busy);
        load.setEnabled(!busy);
        open.setEnabled(!busy);
        cancel.setEnabled(busy && runningOperationId != null);
        confirm.setEnabled(!busy);
        if (!busy) cancel.setEnabled(false);
    }

    private void status(String s) {
        status.setText(s);
    }

    private void append(String line) {
        console.append("[" + LocalTime.now().format(TIME) + "] " + line + "\n");
    }

    // ---- introspection (tests, screenshot runs) --------------------------------------------------

    boolean isAwaitingConfirmation() {
        return pending != null && sheet.isVisible();
    }

    boolean isRunning() {
        return runningRequestId != null;
    }

    /** Comma-separated capabilities the engine named for the pending text (empty when none). */
    String capabilitiesText() {
        return capabilities;
    }

    String consoleText() {
        return console.getText();
    }

    String statusText() {
        return status.getText();
    }

    /** The last result the engine returned for this editor (null before the first run or after an exception). */
    ActionResult lastResult() {
        return lastResult;
    }

    /** The CODE record of the last completed run, or null. */
    String lastActionId() {
        return lastResult != null && lastResult.record() != null ? lastResult.record().actionId() : null;
    }

    static OperationState stateOf(ActionResult r) {
        return r == null ? null : r.state();
    }
}
