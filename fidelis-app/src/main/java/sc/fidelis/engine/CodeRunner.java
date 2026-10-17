package sc.fidelis.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import ij.ImagePlus;
import ij.Macro;
import ij.macro.Interpreter;
import ij.macro.Program;
import ij.macro.Symbol;
import ij.macro.Tokenizer;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult.EngineError;
import sc.fidelis.engine.api.ActionResult.StateDigest;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Provenance;
import sc.fidelis.engine.api.HistoryRecord;

/**
 * CODE actions: user macro text run through an owned {@link Interpreter} (mirrors
 * {@code Macro_Runner.runMacro(String, String)} so that {@code wasError()} and the error message stay
 * readable, D07 section 2), behind a capability confirmation token keyed to the text hash.
 */
final class CodeRunner {
    /** Capability tokens recognised by the scan: functions the user must consent to by name. */
    private static final List<String> CAPABILITIES = List.of("exec", "eval", "call", "runMacro", "File.delete", "File.rename",
            "File.copy", "File.append", "File.saveString", "File.openUrlAsString", "File.makeDirectory", "getURL");

    /** Statements that map one-to-one onto a generated (TYPED-equivalent) macro line. */
    private static final Pattern PURE_LINE = Pattern.compile(
            "^(run|setAutoThreshold|setThreshold|resetThreshold|makeRectangle|makeOval|makePolygon|makeLine|makePoint|makeSelection"
                    + "|setMinAndMax|setSlice|setOption)\\(.*\\);\\s*$");

    private final DefaultEngine eng;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    CodeRunner(DefaultEngine eng) {
        this.eng = eng;
    }

    /** Capabilities found in the macro text (token scan plus a text scan for dotted names). */
    static List<String> scan(String text) {
        Set<String> found = new LinkedHashSet<>();
        try {
            Program pgm = new Tokenizer().tokenize(text);
            Symbol[] table = pgm.getSymbolTable();
            Set<String> symbols = new LinkedHashSet<>();
            if (table != null) for (Symbol s : table) if (s != null && s.str != null) symbols.add(s.str);
            for (String cap : CAPABILITIES) {
                if (symbols.contains(cap) || pgm.hasWord(cap)) found.add(cap);
            }
        } catch (RuntimeException e) {
            // the interpreter will report syntax errors; the text scan below still applies
        }
        for (String cap : CAPABILITIES) {
            String re = cap.contains(".") ? "\\b" + Pattern.quote(cap) + "\\s*\\(" : "\\b" + Pattern.quote(cap) + "\\s*\\(";
            if (Pattern.compile(re).matcher(text).find()) found.add(cap);
        }
        return new ArrayList<>(found);
    }

    private static String sha(String text) {
        try {
            return Registry.hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Outcome run(Op op) {
        ActionRequest r = op.request;
        Object t = r.params().get("text");
        if (!(t instanceof String text) || text.isBlank()) return Outcome.failed(ErrorCode.VALIDATION_FAILED, "CODE needs params.text");
        String lang = String.valueOf(r.params().getOrDefault("language", "ijm")).toLowerCase(Locale.ROOT);
        if (!Set.of("ijm", "macro", "imagej macro", "ij1 macro", "ijm macro").contains(lang))
            return Outcome.failed(ErrorCode.UNSUPPORTED_IN_MODE, "only the ImageJ 1.x macro language is supported, not '" + lang + "'");
        String hash = sha(text);
        List<String> caps = scan(text);
        String token = r.confirmationToken();
        if (token == null || !hash.equals(tokens.get(token))) {
            String fresh = "conf-" + UUID.randomUUID();
            tokens.put(fresh, hash);
            Map<String, String> ctx = new LinkedHashMap<>();
            ctx.put("sha256", hash);
            ctx.put("capabilities", String.join(",", caps));
            EngineError err = new EngineError(ErrorCode.CAPABILITY_CONFIRMATION_REQUIRED,
                    "macro code runs with full privileges; confirm to run" + (caps.isEmpty() ? "" : " (uses " + String.join(", ", caps) + ")"),
                    null, ctx, true, List.of("resubmit with the returned confirmationToken"));
            return new Outcome(OperationState.AWAITING_CONFIRMATION, err, null, null, fresh);
        }

        String actionId = eng.history.nextId();
        op.actionId = actionId;
        Map<String, String> gBefore = Globals.capture();
        int resultsBefore = Results.tableSize();
        // a targeted macro runs on its document: typed actions never rely on IJ1's implicit current image
        Doc target = r.target() == null || r.target().document() == null ? null : eng.registry.get(r.target().document());
        ij.WindowManager.setTempCurrentImage(null);
        if (target != null) eng.makeCurrent(target);
        eng.setRunning(op);
        eng.emitState(op, OperationState.RUNNING, null);
        Guard guard = Guard.begin(new Guard.Listener() {
            @Override
            public void dialogOpened(String title, String cls) {
                eng.events.emit(seq -> new EngineEvent.DialogPending(seq, op.id, title, cls));
                eng.emitState(op, OperationState.WAITING_FOR_DIALOG, null);
            }

            @Override
            public void dialogsClosed() {
                eng.emitState(op, OperationState.RUNNING, null);
            }
        });
        eng.setGuard(guard);
        Interpreter interp = new Interpreter();
        eng.setInterpreter(interp);
        String ret = null;
        Throwable thrown = null;
        boolean cancelledBeforeStart;
        synchronized (op) {
            // Interpreter.run resets the interpreter's abort flag when it starts, so a cancel that arrived before
            // this point would be lost: honour it here, and let cancel() abort only an interpreter that has started.
            cancelledBeforeStart = op.cancelRequested;
            if (cancelledBeforeStart) op.abortIssued = true;
            else op.interpreterStarted = true;
        }
        try {
            ret = cancelledBeforeStart ? "[aborted]" : interp.run(text, null);
        } catch (Throwable e) {
            thrown = e;
            interp.abortMacro();
            ImagePlus cur = ij.WindowManager.getCurrentImage();
            if (cur != null) cur.unlock();
        } finally {
            op.interpreterFinished = true;
            eng.setInterpreter(null);
            eng.setRunning(null);
            eng.setGuard(null);
            ij.IJ.resetEscape();
            ij.IJ.setKeyUp(ij.IJ.ALL_KEYS);
            Macro.setOptions(null);
        }
        Guard.Report rep = guard.end();
        showBatchImages();
        Registry.Change change = eng.reconcile();
        eng.rehideWindows();
        boolean cancelled = op.cancelRequested && op.abortIssued;
        boolean macroError = interp.wasError() || (ret != null && ret.equals("[aborted]"));
        String message = null;
        if (rep.failed()) message = rep.errorText();
        else if (macroError || thrown != null) {
            String m = interp.getErrorMessage();
            message = m != null ? m : (thrown != null && !Macro.MACRO_CANCELED.equals(thrown.getMessage()) ? String.valueOf(thrown) : "macro aborted");
        }

        Map<String, String> gAfter = Globals.capture();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("language", "ijm");
        params.put("text", text);
        params.put("sha256", hash);
        params.put("capabilities", caps);
        if (!change.added().isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Doc d : change.added()) ids.add(d.id.value());
            params.put("createdDocuments", ids);
        }
        boolean pure = isPure(text);
        List<String> lines = pure ? pureLines(text) : List.of();
        Provenance prov = pure ? Provenance.TYPED : Provenance.UNRECORDABLE;

        OperationState state;
        EngineError error = null;
        if (cancelled) {
            state = OperationState.CANCELLED;
            error = new EngineError(ErrorCode.CANCELLED, "macro was cancelled", "MACRO_ABORTED", Map.of(), false, List.of());
        } else if (message != null) {
            state = OperationState.FAILED;
            error = new EngineError(ErrorCode.EXECUTION_FAILED, "macro failed: " + message, "MACRO_ERROR", Map.of(), false, List.of());
        } else {
            state = OperationState.COMPLETED;
        }
        HistoryRecord rec = History.record(actionId, r.origin(), prov, null, "macro", params, r.target(), lines, pure, gBefore,
                Globals.delta(gBefore, gAfter), state, error == null ? null : error.message());
        eng.history.add(rec);
        int rows = Results.tableSize() - resultsBefore;
        if (rows > 0) eng.results.capture(resultsBefore, null, actionId);
        else eng.results.markSeen();
        List<DocumentId> added = new ArrayList<>();
        for (Doc d : change.added()) added.add(d.id);
        List<DocumentId> removed = new ArrayList<>();
        for (Doc d : change.removed()) removed.add(d.id);
        StateDigest digest = new StateDigest(added, removed, List.of(), Math.max(0, rows), Globals.delta(gBefore, gAfter),
                rep.newLogLines(), rep.windowsOpened(), rep.windowsClosed());
        return new Outcome(state, error, digest, rec, null);
    }

    /** Images a macro left in batch mode are shown (as setBatchMode(false) would) so that the registry can adopt them. */
    private static void showBatchImages() {
        if (!Interpreter.isBatchMode() && Interpreter.getBatchModeImageCount() == 0) return;
        List<ImagePlus> imgs = new ArrayList<>();
        int[] ids = Interpreter.getBatchModeImageIDs();
        if (ids != null) for (int id : ids) {
            ImagePlus imp = Interpreter.getBatchModeImage(id);
            if (imp != null) imgs.add(imp);
        }
        Interpreter.batchMode = false;
        for (ImagePlus imp : imgs) imp.show();
    }

    static boolean isPure(String text) {
        boolean any = false;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//")) continue;
            if (!PURE_LINE.matcher(line).matches()) return false;
            any = true;
        }
        return any;
    }

    private static List<String> pureLines(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (!line.isEmpty() && !line.startsWith("//")) out.add(line);
        }
        return out;
    }
}
