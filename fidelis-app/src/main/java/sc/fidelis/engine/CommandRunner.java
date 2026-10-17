package sc.fidelis.engine;

import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ij.CommandListener;
import ij.Executer;
import ij.IJ;
import ij.WindowManager;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.filter.Analyzer;
import ij.process.ImageProcessor;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionResult.EngineError;
import sc.fidelis.engine.api.ActionResult.StateDigest;
import sc.fidelis.engine.api.ActionResult.StateDigest.DocumentChange;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Provenance;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;

/**
 * Typed command execution (E3, route R1): validation per D06 section 3, {@code IJ.run(imp, label,
 * completeOptions)} on the engine worker, error and dialog watch, postconditions by family, snapshot
 * rollback, results capture and the history record.
 */
final class CommandRunner {
    private final DefaultEngine eng;

    CommandRunner(DefaultEngine eng) {
        this.eng = eng;
    }

    // ---- state captured around an action -------------------------------------------------------------

    /** Cheap image fingerprint used for postconditions and the digest. */
    private record State(String hash, int type, int bitDepth, int width, int height, int c, int z, int t, double dmin,
                         double dmax, String roiSig) {
        static State of(ImagePlus imp, boolean hashAll) {
            Roi roi = imp.getRoi();
            String sig = roi == null ? "" : roi.getTypeAsString() + roi.getBounds() + roi.getName();
            return new State(Pixels.hash(imp, hashAll), imp.getType(), imp.getBitDepth(), imp.getWidth(), imp.getHeight(),
                    imp.getNChannels(), imp.getNSlices(), imp.getNFrames(), imp.getDisplayRangeMin(), imp.getDisplayRangeMax(), sig);
        }
    }

    private record Failure(ErrorCode code, String message, String subcode) {
        EngineError toError() {
            return new EngineError(code, message, subcode, Map.of(), false, List.of());
        }
    }

    // ---- entry ---------------------------------------------------------------------------------------

    Outcome run(Op op, boolean fromRedo) {
        ActionRequest r = op.request;
        Catalogue.Entry en = eng.cat().find(r.commandId()).orElse(null);
        if (en == null) {
            String label = r.commandId().substring(r.commandId().lastIndexOf('>') + 1);
            List<String> sugg = new ArrayList<>();
            for (var s : eng.cat().search(label, 3)) sugg.add(s.commandId());
            return Outcome.failed(new EngineError(ErrorCode.UNKNOWN_COMMAND, "no such command: " + r.commandId(), null,
                    Map.of(), true, sugg));
        }
        TargetScope ts = r.target();
        Doc doc = ts == null ? null : eng.registry.get(ts.document());
        if (ts != null && doc == null)
            return Outcome.failed(ErrorCode.PRECONDITION_FAILED, "target document " + ts.document() + " is not open");
        if (en.needsImage && doc == null)
            return Outcome.failed(ErrorCode.PRECONDITION_FAILED, en.label() + " needs a target document (TargetScope)");
        if (!en.schema.schemaComplete()) return runLegacy(op, en, doc);

        // Convert to Mask / Make Binary on a single image: IJ1 has no black key there, it is the global
        // Prefs.blackBackground, so the request becomes an explicit AC5 global change (never silent)
        Map<String, Object> reqParams = r.params();
        Boolean singleBlack = null;
        if ((en.label().equals("Convert to Mask") || en.label().equals("Make Binary")) && doc != null
                && doc.imp.getStackSize() == 1 && reqParams.get("black") != null) {
            Object b = reqParams.get("black");
            if (b instanceof Boolean bb) singleBlack = bb;
            else if (b instanceof String bs && (bs.equalsIgnoreCase("true") || bs.equalsIgnoreCase("false"))) singleBlack = Boolean.parseBoolean(bs);
            else return Outcome.failed(new EngineError(ErrorCode.VALIDATION_FAILED, "parameter 'black' must be true or false, got " + b,
                    null, Map.of("key", "black"), true, List.of()));
            reqParams = new LinkedHashMap<>(reqParams);
            reqParams.remove("black");
        }

        // 4. parameter completeness and domains
        Options.Validated v;
        try {
            v = Options.validate(en, reqParams, doc == null ? null : doc.imp, eng.registry);
        } catch (Options.Invalid e) {
            return Outcome.failed(new EngineError(ErrorCode.VALIDATION_FAILED, e.getMessage(), null,
                    e.key == null ? Map.of() : Map.of("key", e.key), true, List.of()));
        }
        Map<String, Object> typed = v.typed();
        if (singleBlack != null) typed.put("black", singleBlack);

        // File>Open... is a session operation
        if (en.label().equals("Open...")) {
            Object p = typed.get("open");
            if (p == null) return Outcome.failed(ErrorCode.VALIDATION_FAILED, "Open... needs the 'open' path");
            return eng.openFile(op, Path.of(String.valueOf(p)));
        }

        // 5. preconditions
        Failure pre = checkPreconditions(en, doc, ts);
        if (pre != null) return new Outcome(OperationState.FAILED, pre.toError(), null, null, null);

        // output path policy (FILE_WRITTEN): never overwrite an input
        Path outPath = null;
        if ("FILE_WRITTEN".equals(en.family())) {
            Object p = typed.get("save");
            if (p == null) p = typed.get("path");
            if (p != null) {
                outPath = Path.of(String.valueOf(p)).toAbsolutePath().normalize();
                for (Doc d : eng.registry.all()) {
                    if (d.sourcePath != null && d.sourcePath.equals(outPath))
                        return Outcome.failed(new EngineError(ErrorCode.CONFIRMATION_REQUIRED,
                                "refusing to overwrite the input file " + outPath, "OVERWRITE_INPUT",
                                Map.of("path", outPath.toString()), true, List.of("choose a different output path")));
                }
                if (outPath.getParent() == null || !Files.isDirectory(outPath.getParent()))
                    return Outcome.failed(ErrorCode.IO_ERROR, "output folder does not exist: " + outPath.getParent());
            }
        }

        ImagePlus imp = doc == null ? null : doc.imp;
        String label = en.label();

        // ROI scope
        List<String> lines = new ArrayList<>();
        boolean usesManager = label.equals("Analyze Particles...") && Boolean.TRUE.equals(typed.get("add"));
        Doc checkDoc = doc;
        if (imp != null) {
            if (ts.roiIndex() != null && ts.roiIndex() >= 0) {
                Roi chosen;
                synchronized (doc.roiList) {
                    if (ts.roiIndex() >= doc.roiList.size())
                        return Outcome.failed(ErrorCode.VALIDATION_FAILED, "roiIndex " + ts.roiIndex() + " is outside the ROI list (size "
                                + doc.roiList.size() + ")");
                    chosen = (Roi) doc.roiList.get(ts.roiIndex()).clone();
                }
                imp.setRoi(chosen);
                lines.addAll(RoiCodec.macroLines(RoiCodec.toGeometry(chosen)));
            }
            if (ts.channel() != null || ts.slice() != null || ts.frame() != null) {
                int c = ts.channel() != null ? ts.channel() : imp.getChannel();
                int z = ts.slice() != null ? ts.slice() : imp.getSlice();
                int t = ts.frame() != null ? ts.frame() : imp.getFrame();
                imp.setPosition(c, z, t);
                lines.add(imp.isHyperStack() || imp.getNChannels() > 1 || imp.getNFrames() > 1
                        ? "Stack.setPosition(" + c + ", " + z + ", " + t + ");" : "setSlice(" + z + ");");
            }
        }
        if (label.equals("Image Calculator...") && !Boolean.TRUE.equals(typed.get("create")) && typed.get("image1") != null)
            checkDoc = eng.registry.get(String.valueOf(typed.get("image1")));
        if (usesManager) {
            eng.makeCurrent(doc);
            eng.roiLists.load(doc);
            lines.add("roiManager(\"reset\");");
            for (RoiGeometry g : RoiLists.geometries(doc)) {
                lines.addAll(RoiCodec.macroLines(g));
                lines.add("roiManager(\"add\");");
            }
            Roi active = imp.getRoi();
            if (active == null) lines.add("run(\"Select None\");");
            else lines.addAll(RoiCodec.macroLines(RoiCodec.toGeometry(active)));
        }
        if (singleBlack != null) lines.add("setOption(\"BlackBackground\", " + singleBlack + ");");
        lines.add(Options.runLine(label, v.options()));

        String family = effectiveFamily(en, typed);
        boolean hashAll = checkDoc != null && checkDoc.imp.getStackSize() > 1
                && (Boolean.TRUE.equals(typed.get("stack")) || Boolean.TRUE.equals(typed.get("process_all"))
                || (ts != null && ts.allPlanes()));

        // 6. snapshot, 7. capture before-state
        String actionId = eng.history.nextId();
        op.actionId = actionId;
        Snapshots.Snapshot snap = null;
        boolean reversible = "IN_PLACE_PIXELS".equals(family) || "TYPE_CHANGE".equals(family)
                || (label.equals("Scale...") && !Boolean.TRUE.equals(typed.get("create")));
        if (reversible && checkDoc != null) snap = eng.snapshots.take(checkDoc, actionId, r, !fromRedo);
        Map<String, String> gBefore = Globals.capture();
        boolean oldBlack = ij.Prefs.blackBackground;
        if (singleBlack != null) ij.Prefs.blackBackground = singleBlack;
        State before = checkDoc == null ? null : State.of(checkDoc.imp, hashAll);
        State beforeTarget = doc == null ? null : (doc == checkDoc ? before : State.of(doc.imp, false));
        int resultsBefore = Results.tableSize();
        Set<Integer> idsBefore = Registry.liveIds();
        int roiCountBefore = doc == null ? 0 : doc.roiList.size();

        // 8. run
        Execution ex = execute(op, label, imp, v.options());

        // 9. after
        Registry.Change change = eng.reconcile();
        int roiCountAfter = roiCountBefore;
        if (usesManager) roiCountAfter = eng.roiLists.syncBack(doc);
        Map<String, String> gAfter = Globals.capture();
        State after = checkDoc == null || !eng.registry.all().contains(checkDoc) ? null : State.of(checkDoc.imp, hashAll);
        int resultsAfter = Results.tableSize();

        Failure failure = null;
        if (ex.report.failed() || ex.thrown != null) {
            String text = ex.report.failed() ? ex.report.errorText() : String.valueOf(ex.thrown);
            failure = new Failure(ErrorCode.EXECUTION_FAILED, label + " failed: " + text, ex.report.failed() ? null : "EXCEPTION");
        } else if (!ex.cancelled && !op.commandRan) {
            failure = new Failure(ErrorCode.POSTCONDITION_FAILED, label + " did not run: ImageJ never started the command", "NOT_RUN");
        } else if (!ex.cancelled) {
            failure = postcondition(en, family, typed, before, after, checkDoc, change, resultsBefore, resultsAfter,
                    roiCountBefore, roiCountAfter, outPath, ex.report);
        }

        Outcome result = conclude(op, en, actionId, ts, checkDoc, doc, typed, lines, gBefore, gAfter, before, after, beforeTarget, change,
                resultsBefore, resultsAfter, ex, failure, snap, outPath, idsBefore);
        if (singleBlack != null && result.state() != OperationState.COMPLETED) ij.Prefs.blackBackground = oldBlack;
        return result;
    }

    // ---- execution -----------------------------------------------------------------------------------

    private record Execution(Guard.Report report, Throwable thrown, boolean cancelled) {
    }

    private Execution execute(Op op, String label, ImagePlus imp, String options) {
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
        Throwable thrown = null;
        boolean escaped = false;
        // Added last so it is reached only if no earlier listener vetoed the command (Executer stops at the first null).
        CommandListener tail = cmd -> {
            op.commandRan = true;
            return cmd;
        };
        Executer.addCommandListener(tail);
        try {
            IJ.run(imp, label, options);
        } catch (Throwable t) {
            thrown = t;
        } finally {
            Executer.removeCommandListener(tail);
            // IJ.run(imp, ...) restores the thread's temporary current image only on normal return; an abort
            // (IJ.error under redirected messages) would otherwise pin this worker to a document that may be closed
            WindowManager.setTempCurrentImage(null);
            escaped = IJ.escapePressed();
            IJ.resetEscape();
            IJ.setKeyUp(IJ.ALL_KEYS);
            eng.setRunning(null);
            eng.setGuard(null);
        }
        Guard.Report report = guard.end();
        boolean cancelled = op.cancelRequested && escaped;
        return new Execution(report, thrown, cancelled);
    }

    /** Commands without a catalogued schema run through their original dialog (FR-PROC-007). */
    private Outcome runLegacy(Op op, Catalogue.Entry en, Doc doc) {
        ActionRequest r = op.request;
        if (!r.params().isEmpty())
            return Outcome.failed(new EngineError(ErrorCode.VALIDATION_FAILED, en.label()
                    + " has no catalogued parameters; submit it without params to use its original dialog", null,
                    Map.of(), true, List.of()));
        ImagePlus imp = doc == null ? null : doc.imp;
        String actionId = eng.history.nextId();
        op.actionId = actionId;
        Map<String, String> gBefore = Globals.capture();
        State before = doc == null ? null : State.of(doc.imp, doc.imp.getStackSize() > 1);
        int resultsBefore = Results.tableSize();
        Execution ex = execute(op, en.label(), imp, null);
        Registry.Change change = eng.reconcile();
        Map<String, String> gAfter = Globals.capture();
        State after = doc == null || !eng.registry.all().contains(doc) ? null : State.of(doc.imp, doc.imp.getStackSize() > 1);
        int resultsAfter = Results.tableSize();
        Failure failure = null;
        if (ex.report.failed() || ex.thrown != null) {
            failure = new Failure(ErrorCode.EXECUTION_FAILED, en.label() + " failed: "
                    + (ex.report.failed() ? ex.report.errorText() : String.valueOf(ex.thrown)), null);
        } else if (!ex.cancelled && !op.commandRan) {
            failure = new Failure(ErrorCode.POSTCONDITION_FAILED, en.label() + " did not run: ImageJ never started the command", "NOT_RUN");
        }
        List<String> lines = List.of(Options.runLine(en.label(), null));
        return conclude(op, en, actionId, r.target(), doc, doc, Map.of(), lines, gBefore, gAfter, before, after, before, change,
                resultsBefore, resultsAfter, ex, failure, null, null, Set.of());
    }

    // ---- preconditions ---------------------------------------------------------------------------------

    private Failure checkPreconditions(Catalogue.Entry en, Doc doc, TargetScope ts) {
        for (String p : en.schema.preconditions()) {
            switch (p) {
                case "image.open" -> {
                    if (doc == null) return new Failure(ErrorCode.PRECONDITION_FAILED, "no image is open", null);
                }
                case "image.binary" -> {
                    ImageProcessor ip = doc.imp.getProcessor();
                    if (!ip.isBinary())
                        return new Failure(ErrorCode.PRECONDITION_FAILED, en.label()
                                + " needs an 8-bit binary (black and white only) image; threshold or Convert to Mask first", null);
                }
                case "image.stack" -> {
                    if (doc.imp.getStackSize() < 2)
                        return new Failure(ErrorCode.PRECONDITION_FAILED, en.label() + " needs an image stack", null);
                }
                case "threshold.or.binary" -> {
                    ImageProcessor ip = doc.imp.getProcessor();
                    if (ip.getMinThreshold() == ImageProcessor.NO_THRESHOLD && !ip.isBinary())
                        return new Failure(ErrorCode.PRECONDITION_FAILED, en.label()
                                + " needs a thresholded or binary image; set a threshold first", "UNTHRESHOLDED_BINARY");
                }
                case "roi.required" -> {
                    boolean has = doc.imp.getRoi() != null || (ts != null && ts.roiIndex() != null && ts.roiIndex() >= 0);
                    if (!has) return new Failure(ErrorCode.PRECONDITION_FAILED, en.label() + " needs a selection (ROI)", null);
                }
                default -> {
                    // unknown predicate: not enforced
                }
            }
        }
        if (doc != null && doc.imp.isLocked())
            return new Failure(ErrorCode.PRECONDITION_FAILED, "image locked by another operation", "IMAGE_LOCKED");
        return null;
    }

    private static String effectiveFamily(Catalogue.Entry en, Map<String, Object> typed) {
        String f = en.family();
        if (f == null) return null;
        String label = en.label();
        if (label.equals("Image Calculator...")) return Boolean.TRUE.equals(typed.get("create")) ? "NEW_IMAGE" : "IN_PLACE_PIXELS";
        if (label.equals("Scale...")) return Boolean.TRUE.equals(typed.get("create")) ? "NEW_IMAGE" : "SCALE_IN_PLACE";
        return f;
    }

    // ---- postconditions --------------------------------------------------------------------------------

    private Failure postcondition(Catalogue.Entry en, String family, Map<String, Object> typed, State before, State after,
                                  Doc checkDoc, Registry.Change change, int resultsBefore, int resultsAfter, int roiBefore,
                                  int roiAfter, Path outPath, Guard.Report report) {
        if (family == null) return null;
        String label = en.label();
        switch (family) {
            case "IN_PLACE_PIXELS" -> {
                if (after == null) return new Failure(ErrorCode.POSTCONDITION_FAILED, label + ": the target image disappeared", null);
                // unchanged pixels are a no-op, not a failure (see the Catalogue javadoc); conclude() notes it
            }
            case "SCALE_IN_PLACE" -> {
                if (after == null || (before.hash().equals(after.hash()) && before.width() == after.width() && before.height() == after.height()))
                    return new Failure(ErrorCode.POSTCONDITION_FAILED, label + " changed nothing", null);
            }
            case "NEW_IMAGE" -> {
                if (change.added().isEmpty())
                    return new Failure(ErrorCode.POSTCONDITION_FAILED, label + " did not produce a new image", null);
            }
            case "TYPE_CHANGE" -> {
                if (after == null) return new Failure(ErrorCode.POSTCONDITION_FAILED, label + ": the target image disappeared", null);
                int want = switch (label) {
                    case "8-bit" -> ImagePlus.GRAY8;
                    case "16-bit" -> ImagePlus.GRAY16;
                    case "32-bit" -> ImagePlus.GRAY32;
                    case "8-bit Color" -> ImagePlus.COLOR_256;
                    case "RGB Color" -> ImagePlus.COLOR_RGB;
                    default -> -1;
                };
                if (want >= 0 && after.type() != want)
                    return new Failure(ErrorCode.POSTCONDITION_FAILED, label + " did not convert the image (type is still "
                            + Registry.typeName(after.type()) + ")", null);
            }
            case "RESULTS_ROWS" -> {
                if (label.equals("Measure") && resultsAfter <= resultsBefore)
                    return new Failure(ErrorCode.POSTCONDITION_FAILED, "Measure added no row to the Results table", null);
                if (label.equals("Analyze Particles...")) {
                    String show = String.valueOf(typed.get("show"));
                    if ((show.equals("Masks") || show.equals("Count Masks")) && change.added().isEmpty())
                        return new Failure(ErrorCode.POSTCONDITION_FAILED, "Analyze Particles produced no output image for show=" + show, null);
                }
            }
            case "GLOBALS_ONLY" -> {
                if (label.equals("Set Measurements...")) {
                    String wantKeys = measurementKeysRequested(typed);
                    String got = Globals.measurementKeys(Analyzer.getMeasurements());
                    if (!Set.of(wantKeys.isEmpty() ? new String[0] : wantKeys.split(" ")).equals(
                            Set.of(got.isEmpty() ? new String[0] : got.split(" "))))
                        return new Failure(ErrorCode.POSTCONDITION_FAILED, "Set Measurements... did not apply: wanted [" + wantKeys
                                + "] but IJ1 has [" + got + "]", null);
                } else if (label.equals("Options...") && typed.containsKey("black")) {
                    if (ij.Prefs.blackBackground != Boolean.TRUE.equals(typed.get("black")))
                        return new Failure(ErrorCode.POSTCONDITION_FAILED, "Options... did not apply black background", null);
                }
            }
            case "FILE_WRITTEN" -> {
                if (outPath != null && writtenFile(outPath) == null)
                    return new Failure(ErrorCode.POSTCONDITION_FAILED, "no output file was written at " + outPath, "OUTPUT_NOT_VERIFIED");
            }
            default -> {
                // DISPLAY_ONLY and unknown families: nothing to verify
            }
        }
        return null;
    }

    private static String measurementKeysRequested(Map<String, Object> typed) {
        List<String> keys = new ArrayList<>();
        for (String k : Globals.measurementKeys(~0).split(" ")) if (Boolean.TRUE.equals(typed.get(k))) keys.add(k);
        return String.join(" ", keys);
    }

    /** The file IJ1 actually wrote for the requested path (it may append an extension), or null. */
    static Path writtenFile(Path requested) {
        try {
            if (Files.isRegularFile(requested) && Files.size(requested) > 0) return requested;
            Path dir = requested.getParent();
            String base = requested.getFileName().toString();
            if (dir != null) {
                try (var s = Files.list(dir)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        String n = p.getFileName().toString();
                        if (n.startsWith(base) && Files.isRegularFile(p) && Files.size(p) > 0) return p;
                        int dot = base.lastIndexOf('.');
                        if (dot > 0 && n.startsWith(base.substring(0, dot) + ".") && Files.isRegularFile(p) && Files.size(p) > 0
                                && Files.getLastModifiedTime(p).toMillis() > System.currentTimeMillis() - 60_000) return p;
                    }
                }
            }
        } catch (java.io.IOException ignored) {
            // treated as not written
        }
        return null;
    }

    // ---- conclusion ------------------------------------------------------------------------------------

    private Outcome conclude(Op op, Catalogue.Entry en, String actionId, TargetScope ts, Doc checkDoc, Doc doc,
                             Map<String, Object> typed, List<String> lines, Map<String, String> gBefore,
                             Map<String, String> gAfter, State before, State after, State beforeTarget, Registry.Change change,
                             int resultsBefore, int resultsAfter, Execution ex, Failure failure, Snapshots.Snapshot snap,
                             Path outPath, Object extra) {
        ActionRequest r = op.request;
        Map<String, String> gDelta = Globals.delta(gBefore, gAfter);
        boolean legacy = en.schema.schemaComplete() ? false : true;
        boolean sawDialog = ex.report.sawDialog();
        OperationState state;
        EngineError error = null;
        if (ex.cancelled) {
            state = restoreOrPartial(checkDoc, snap, before, after, OperationState.CANCELLED);
            error = new EngineError(ErrorCode.CANCELLED, en.label() + " was cancelled"
                    + (state == OperationState.ROLLED_BACK ? " and rolled back" : state == OperationState.PARTIAL ? " (partial result)" : ""),
                    "MACRO_ABORTED", Map.of(), false, List.of());
        } else if (failure != null) {
            state = restoreOrPartial(checkDoc, snap, before, after, OperationState.FAILED);
            error = failure.toError();
        } else {
            state = OperationState.COMPLETED;
        }

        // history record
        Provenance prov = legacy && sawDialog ? Provenance.LEGACY_DIALOG : Provenance.TYPED;
        boolean exact = !(legacy && sawDialog);
        Map<String, Object> params = new LinkedHashMap<>(typed);
        if (outPath != null && state == OperationState.COMPLETED) {
            Path written = writtenFile(outPath);
            if (written != null) {
                params.put("outputPath", written.toString().replace('\\', '/'));
                String sha = Registry.sha256(written);
                if (sha != null) params.put("outputSha256", sha);
            }
        }
        if (op.parentActionId != null) params.put("parentActionId", op.parentActionId);
        if (!change.added().isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Doc d : change.added()) ids.add(d.id.value());
            params.put("createdDocuments", ids);
        }
        List<String> macroLines = state == OperationState.COMPLETED || legacy ? lines : lines;
        String note = error == null ? null : error.message();
        if (state == OperationState.COMPLETED && "IN_PLACE_PIXELS".equals(en.family()) && before != null && after != null
                && before.hash().equals(after.hash()))
            note = "no-op: " + en.label() + " ran without error and left the pixels unchanged";
        HistoryRecord rec = History.record(actionId, r.origin(), prov, en.schema.commandId(), en.label(), params, ts, macroLines,
                exact, gBefore, gDelta, state, note);
        eng.history.add(rec);
        if (doc != null) {
            eng.observers.noteRoi(doc);
            eng.observers.noteThreshold(doc);
        }

        if (state != OperationState.COMPLETED) {
            eng.results.markSeen();
            if (snap != null && state != OperationState.ROLLED_BACK) { /* kept: image changed, snapshot still valid */ }
            return new Outcome(state, error, digest(en, doc, before, after, change, resultsBefore, resultsAfter, gDelta, ex, false),
                    rec, null);
        }

        // success: capture results and register the undo snapshot's survival
        int captured = 0;
        // A command that clears the system table first (Analyze Particles with clear=true) restarts its rows at 0.
        int rowsFrom = Boolean.TRUE.equals(typed.get("clear")) ? 0 : resultsBefore;
        if ("RESULTS_ROWS".equals(en.family()) && resultsAfter > rowsFrom) {
            captured = eng.results.capture(rowsFrom, doc == null ? null : doc.id, actionId);
        }
        eng.results.markSeen();
        if (snap != null && before != null && after != null && before.hash().equals(after.hash())
                && before.width() == after.width() && before.type() == after.type()) {
            eng.snapshots.release(snap); // a no-op leaves nothing to undo
        }
        return Outcome.ok(digest(en, doc, before, after, change, rowsFrom, resultsAfter, gDelta, ex, true), rec);
    }

    private OperationState restoreOrPartial(Doc checkDoc, Snapshots.Snapshot snap, State before, State after,
                                            OperationState failed) {
        if (snap != null && checkDoc != null && eng.registry.all().contains(checkDoc)) {
            try {
                Snapshots.restore(snap);
                eng.snapshots.release(snap);
                return OperationState.ROLLED_BACK;
            } catch (RuntimeException e) {
                System.err.println("[fidelis-engine] rollback failed: " + e);
            }
        }
        if (before != null && after != null && !before.hash().equals(after.hash())) return OperationState.PARTIAL;
        return failed;
    }

    private StateDigest digest(Catalogue.Entry en, Doc doc, State before, State after, Registry.Change change, int resultsBefore,
                               int resultsAfter, Map<String, String> gDelta, Execution ex, boolean ok) {
        List<DocumentId> added = new ArrayList<>();
        for (Doc d : change.added()) added.add(d.id);
        List<DocumentId> removed = new ArrayList<>();
        for (Doc d : change.removed()) removed.add(d.id);
        List<DocumentChange> changed = new ArrayList<>();
        if (doc != null && before != null && after != null) {
            boolean px = !before.hash().equals(after.hash());
            boolean type = before.type() != after.type() || before.bitDepth() != after.bitDepth();
            boolean disp = before.dmin() != after.dmin() || before.dmax() != after.dmax();
            boolean roi = !before.roiSig().equals(after.roiSig());
            boolean dims = before.width() != after.width() || before.height() != after.height() || before.c() != after.c()
                    || before.z() != after.z() || before.t() != after.t();
            if (px || type || disp || roi || dims) changed.add(new DocumentChange(doc.id, px, type, disp, roi, dims));
        }
        int rows = Math.max(0, resultsAfter - resultsBefore);
        return new StateDigest(added, removed, changed, rows, gDelta, ex.report.newLogLines(), ex.report.windowsOpened(),
                ex.report.windowsClosed());
    }

    /** Escape-key cancellation used by {@link DefaultEngine#cancel}. */
    static void requestEscape() {
        IJ.setKeyDown(KeyEvent.VK_ESCAPE);
    }
}
