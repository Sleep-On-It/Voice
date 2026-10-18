package sc.fidelis.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ImageProcessor;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.ActionResult.EngineError;
import sc.fidelis.engine.api.ActionResult.StateDigest;
import sc.fidelis.engine.api.ActionResult.StateDigest.DocumentChange;
import sc.fidelis.engine.api.Enums.ErrorCode;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Provenance;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.MeasurementRow;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;

/** DIRECT operations (selection, display, position, threshold, ROI list, undo) and the SAVE_AS / NEW_IMAGE session operations. */
final class DirectOps {
    private static final Set<String> THRESHOLD_METHODS = Set.of("Default", "Huang", "Intermodes", "IsoData", "IJ_IsoData", "Li",
            "MaxEntropy", "Mean", "MinError", "Minimum", "Moments", "Otsu", "Percentile", "RenyiEntropy", "Shanbhag", "Triangle", "Yen");

    private final DefaultEngine eng;

    DirectOps(DefaultEngine eng) {
        this.eng = eng;
    }

    private static final class Bad extends Exception {
        Bad(String m) {
            super(m);
        }
    }

    // ---- DIRECT --------------------------------------------------------------------------------------

    Outcome runDirect(Op op) {
        ActionRequest r = op.request;
        String name = r.operation();
        TargetScope ts = r.target();
        Doc doc = ts == null ? null : eng.registry.get(ts.document());
        if (doc == null) return Outcome.failed(ErrorCode.PRECONDITION_FAILED, name + " needs a target document that is open");
        ImagePlus imp = doc.imp;
        Map<String, String> gBefore = Globals.capture();
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        boolean exact = true;
        boolean roiChanged = false;
        boolean displayChanged = false;
        boolean pixelsChanged = false;
        boolean dimsChanged = false;
        // a ROI-list change (and the Results rows a ROI copy brings along) is undoable like a pixel change
        Snapshots.Snapshot stateSnap = null;
        boolean fromRedo = r.origin() == sc.fidelis.engine.api.Enums.Origin.REPLAY && r.replayOf() != null;
        try {
            if (ts.channel() != null || ts.slice() != null || ts.frame() != null) {
                switch (name) {
                    case Operations.SET_THRESHOLD, Operations.SET_AUTO_THRESHOLD, Operations.RESET_THRESHOLD, Operations.SET_DISPLAY_RANGE -> {
                        // plane-dependent operations act on the plane the request names, not on wherever the viewer is now
                        int c = ts.channel() != null ? ts.channel() : imp.getChannel();
                        int z = ts.slice() != null ? ts.slice() : imp.getSlice();
                        int t = ts.frame() != null ? ts.frame() : imp.getFrame();
                        if (c < 1 || c > imp.getNChannels() || z < 1 || z > imp.getNSlices() || t < 1 || t > imp.getNFrames())
                            throw new Bad("the target plane " + c + "," + z + "," + t + " is outside the image");
                        if (c != imp.getChannel() || z != imp.getSlice() || t != imp.getFrame()) {
                            imp.setPosition(c, z, t);
                            lines.add(imp.isHyperStack() || imp.getNChannels() > 1 || imp.getNFrames() > 1
                                    ? "Stack.setPosition(" + c + ", " + z + ", " + t + ");" : "setSlice(" + z + ");");
                        }
                    }
                    default -> { }
                }
            }
            switch (name) {
                case Operations.SET_ROI -> {
                    Object g = r.params().get("roi");
                    if (!(g instanceof RoiGeometry geom)) throw new Bad("SET_ROI needs params.roi as a RoiGeometry");
                    Roi roi;
                    try {
                        roi = RoiCodec.toRoi(geom);
                    } catch (IllegalArgumentException e) {
                        throw new Bad(e.getMessage());
                    }
                    eng.emitState(op, OperationState.RUNNING, null);
                    imp.setRoi(roi);
                    params.put("roi", geom);
                    lines.addAll(RoiCodec.macroLines(geom));
                    exact = !lines.isEmpty();
                    roiChanged = true;
                }
                case Operations.CLEAR_ROI -> {
                    eng.emitState(op, OperationState.RUNNING, null);
                    imp.deleteRoi();
                    lines.add("run(\"Select None\");");
                    roiChanged = true;
                }
                case Operations.SET_DISPLAY_RANGE -> {
                    double min = number(r, "min");
                    double max = number(r, "max");
                    if (!(min < max)) throw new Bad("min must be less than max");
                    eng.emitState(op, OperationState.RUNNING, null);
                    params.put("min", min);
                    params.put("max", max);
                    if (r.params().get("channel") != null || imp instanceof CompositeImage) {
                        if (!(imp instanceof CompositeImage)) throw new Bad("channel is only valid for composite images");
                        int orig = imp.getChannel();
                        int ch = r.params().get("channel") == null ? orig : (int) number(r, "channel");
                        if (ch < 1 || ch > imp.getNChannels()) throw new Bad("channel out of range (1.." + imp.getNChannels() + ")");
                        params.put("channel", ch);
                        // IJ1's channel slider and Brightness/Contrast set the range of the current channel only:
                        // select it, set the range, restore the position (macro: Stack.setChannel then setMinAndMax).
                        imp.setC(ch);
                        imp.setDisplayRange(min, max);
                        if (ch != orig) imp.setC(orig);
                        lines.add("Stack.setChannel(" + ch + ");");
                        lines.add("setMinAndMax(" + Options.formatNumber(min) + ", " + Options.formatNumber(max) + ");");
                        if (ch != orig) lines.add("Stack.setChannel(" + orig + ");");
                    } else {
                        imp.setDisplayRange(min, max);
                        lines.add("setMinAndMax(" + Options.formatNumber(min) + ", " + Options.formatNumber(max) + ");");
                    }
                    imp.updateAndDraw();
                    displayChanged = true;
                }
                case Operations.SET_POSITION -> {
                    int c = pos(r, "channel", imp.getChannel(), imp.getNChannels());
                    int z = pos(r, "slice", imp.getSlice(), imp.getNSlices());
                    int t = pos(r, "frame", imp.getFrame(), imp.getNFrames());
                    eng.emitState(op, OperationState.RUNNING, null);
                    params.put("channel", c);
                    params.put("slice", z);
                    params.put("frame", t);
                    imp.setPosition(c, z, t);
                    lines.add(imp.isHyperStack() || imp.getNChannels() > 1 || imp.getNFrames() > 1
                            ? "Stack.setPosition(" + c + ", " + z + ", " + t + ");" : "setSlice(" + z + ");");
                    displayChanged = true;
                }
                case Operations.SET_THRESHOLD -> {
                    double lo = number(r, "lower");
                    double hi = number(r, "upper");
                    if (lo > hi) throw new Bad("lower must not exceed upper");
                    eng.emitState(op, OperationState.RUNNING, null);
                    params.put("lower", lo);
                    params.put("upper", hi);
                    ImageProcessor ip = imp.getProcessor();
                    ip.setThreshold(lo, hi, ImageProcessor.RED_LUT);
                    imp.updateAndDraw();
                    lines.add("setThreshold(" + Options.formatNumber(lo) + ", " + Options.formatNumber(hi) + ");");
                    displayChanged = true;
                }
                case Operations.SET_AUTO_THRESHOLD -> {
                    Object m = r.params().get("method");
                    if (!(m instanceof String method) || !THRESHOLD_METHODS.contains(method))
                        throw new Bad("method must be one of " + new java.util.TreeSet<>(THRESHOLD_METHODS));
                    Object d = r.params().get("dark");
                    boolean dark = d instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(d));
                    if (d == null) throw new Bad("dark must be given explicitly (true or false)");
                    eng.emitState(op, OperationState.RUNNING, null);
                    params.put("method", method);
                    params.put("dark", dark);
                    String spec = method + (dark ? " dark" : "");
                    IJ.setAutoThreshold(imp, spec);
                    if (imp.getProcessor().getMinThreshold() == ImageProcessor.NO_THRESHOLD)
                        return failRecord(op, doc, gBefore, name, params, ErrorCode.POSTCONDITION_FAILED, "no threshold was set by " + spec);
                    lines.add("setAutoThreshold(\"" + Options.macroString(spec) + "\");");
                    displayChanged = true;
                }
                case Operations.RESET_THRESHOLD -> {
                    eng.emitState(op, OperationState.RUNNING, null);
                    IJ.resetThreshold(imp);
                    lines.add("resetThreshold();");
                    displayChanged = true;
                }
                case Operations.ROI_LIST_ADD -> {
                    Object from = r.params().get("fromDocument");
                    Redo redo = op.redo;
                    if (redo != null && redo.replaysState()) {
                        // REDO of an undone add/copy: re-establish what the undo recorded (the objects it removed from the
                        // list and the Results rows it took out and put back) instead of running the request again, whose
                        // source document (segment_cells' mask) is normally closed by now
                        boolean replace = redo.replace();
                        List<Roi> copies = new ArrayList<>();
                        for (Roi x : redo.roisAdded()) copies.add((Roi) x.clone());
                        String resultsOf = redo.resultsAction();
                        if (resultsOf != null) params.put("resultsOf", resultsOf);
                        // undoing this redo removes the rows again and puts the cleared ones back, as the first undo did
                        stateSnap = eng.snapshots.take(doc, null, r, !fromRedo, false, true, redo.rowsRemoved() != null, redo.rowsRestored(), resultsOf);
                        eng.emitState(op, OperationState.RUNNING, null);
                        synchronized (doc.roiList) {
                            if (replace) doc.roiList.clear();
                            doc.roiList.addAll(copies);
                            params.put("count", (long) copies.size());
                            params.put("total", (long) doc.roiList.size());
                        }
                        if (from != null) params.put("fromDocument", String.valueOf(from));
                        if (r.params().get("position") instanceof List<?> pl && pl.size() == 3) params.put("position", List.copyOf(pl));
                        params.put("replace", replace);
                        params.put("redone", true);
                        if (redo.rowsRemoved() != null) {
                            int[] rows = eng.results.redoAction(redo.rowsRestored(), redo.rowsRemoved());
                            params.put("resultsRowsBefore", rows[0]);
                            params.put("resultsRowsAfter", rows[1]);
                        }
                        if (replace) lines.add("roiManager(\"reset\");");
                        for (Roi x : copies) {
                            lines.addAll(RoiCodec.macroLines(RoiCodec.toGeometry(x)));
                            lines.add("roiManager(\"add\");");
                        }
                        lines.add("run(\"Select None\");");
                        exact = false;
                    } else if (from != null) {
                        // opt-in: copy another document's ROI list (objects segmented on a derived mask, kept on the original)
                        Doc src = eng.registry.get(String.valueOf(from));
                        if (src == null) throw new Bad("fromDocument " + from + " is not open");
                        if (src == doc) throw new Bad("fromDocument is the target document");
                        boolean replace = Boolean.TRUE.equals(r.params().get("replace"));
                        List<Roi> copies = new ArrayList<>();
                        synchronized (src.roiList) {
                            for (Roi x : src.roiList) copies.add((Roi) x.clone());
                        }
                        // the plane the objects were found on, for a stack target ({c, z, t}; 0 = any)
                        if (r.params().get("position") instanceof List<?> pl && pl.size() == 3 && imp.getStackSize() > 1) {
                            int[] pos = new int[3];
                            for (int k = 0; k < 3; k++) pos[k] = pl.get(k) instanceof Number n ? n.intValue() : 0;
                            for (Roi x : copies) x.setPosition(pos[0], pos[1], pos[2]);
                            params.put("position", List.of(pos[0], pos[1], pos[2]));
                        }
                        // resultsOf: the measurements of the copied objects (an Analyze Particles on the source) go with them,
                        // so undoing the copy also removes those Results rows, and puts back the rows that action cleared
                        // (its own snapshot on the source records them; the source is closed, and that snapshot lost, before
                        // the original is ever undone)
                        String resultsOf = r.params().get("resultsOf") instanceof String ro && !ro.isBlank() ? ro : null;
                        if (resultsOf != null) params.put("resultsOf", resultsOf);
                        List<MeasurementRow> cleared = resultsOf == null ? null : clearedBy(src, resultsOf);
                        stateSnap = eng.snapshots.take(doc, null, r, !fromRedo, false, true, resultsOf != null, cleared, resultsOf);
                        eng.emitState(op, OperationState.RUNNING, null);
                        synchronized (doc.roiList) {
                            if (replace) doc.roiList.clear();
                            doc.roiList.addAll(copies);
                            params.put("count", (long) copies.size());
                            params.put("total", (long) doc.roiList.size());
                        }
                        params.put("fromDocument", src.id.value());
                        params.put("replace", replace);
                        if (replace) lines.add("roiManager(\"reset\");");
                        for (Roi x : copies) {
                            lines.addAll(RoiCodec.macroLines(RoiCodec.toGeometry(x)));
                            lines.add("roiManager(\"add\");");
                        }
                        lines.add("run(\"Select None\");");
                        exact = false; // Fidelis keeps one ROI list per document; the ROI Manager lines are its classic-Fiji equivalent
                    } else {
                        Roi roi = imp.getRoi();
                        if (roi == null) throw new Bad("there is no active selection to add");
                        stateSnap = eng.snapshots.take(doc, null, r, !fromRedo, false, true, false, null, null);
                        eng.emitState(op, OperationState.RUNNING, null);
                        synchronized (doc.roiList) {
                            doc.roiList.add((Roi) roi.clone());
                            params.put("index", doc.roiList.size() - 1);
                        }
                    }
                }
                case Operations.ROI_LIST_SELECT -> {
                    int i = index(doc, r);
                    eng.emitState(op, OperationState.RUNNING, null);
                    Roi roi;
                    synchronized (doc.roiList) {
                        roi = (Roi) doc.roiList.get(i).clone();
                    }
                    params.put("index", i);
                    imp.setRoi(roi);
                    lines.addAll(RoiCodec.macroLines(RoiCodec.toGeometry(roi)));
                    exact = !lines.isEmpty();
                    roiChanged = true;
                }
                case Operations.ROI_LIST_DELETE -> {
                    int i = index(doc, r);
                    stateSnap = eng.snapshots.take(doc, null, r, !fromRedo, false, true, false, null, null);
                    eng.emitState(op, OperationState.RUNNING, null);
                    synchronized (doc.roiList) {
                        doc.roiList.remove(i);
                    }
                    params.put("index", i);
                }
                case Operations.ROI_LIST_RENAME -> {
                    int i = index(doc, r);
                    Object n = r.params().get("name");
                    if (!(n instanceof String s) || s.isBlank()) throw new Bad("name must be non-empty text");
                    stateSnap = eng.snapshots.take(doc, null, r, !fromRedo, false, true, false, null, null);
                    eng.emitState(op, OperationState.RUNNING, null);
                    synchronized (doc.roiList) {
                        doc.roiList.get(i).setName(s);
                    }
                    params.put("index", i);
                    params.put("name", s);
                }
                case Operations.UNDO -> {
                    return undo(op, doc, gBefore);
                }
                case Operations.REDO -> {
                    return redo(op, doc);
                }
                default -> {
                    return Outcome.failed(ErrorCode.VALIDATION_FAILED, "unknown direct operation " + name);
                }
            }
        } catch (Bad e) {
            if (stateSnap != null) eng.snapshots.release(stateSnap);
            return Outcome.failed(new EngineError(ErrorCode.VALIDATION_FAILED, name + ": " + e.getMessage(), null, Map.of(), true, List.of()));
        }
        if (roiChanged) eng.observers.noteRoi(doc);
        eng.observers.noteThreshold(doc);
        String actionId = eng.history.nextId();
        if (stateSnap != null) stateSnap.actionId = actionId;
        Map<String, String> gAfter = Globals.capture();
        HistoryRecord rec = History.record(actionId, r.origin(), Provenance.TYPED, name, name, params, ts, lines, exact, gBefore,
                Globals.delta(gBefore, gAfter), OperationState.COMPLETED, null);
        eng.history.add(rec);
        StateDigest digest = new StateDigest(List.of(), List.of(),
                List.of(new DocumentChange(doc.id, pixelsChanged, false, displayChanged, roiChanged, dimsChanged)), 0, Map.of(),
                List.of(), List.of(), List.of());
        return Outcome.ok(digest, rec);
    }

    private Outcome failRecord(Op op, Doc doc, Map<String, String> gBefore, String name, Map<String, Object> params, ErrorCode code,
                               String msg) {
        String actionId = eng.history.nextId();
        HistoryRecord rec = History.record(actionId, op.request.origin(), Provenance.TYPED, name, name, params, op.request.target(),
                List.of(), false, gBefore, Map.of(), OperationState.FAILED, msg);
        eng.history.add(rec);
        return new Outcome(OperationState.FAILED, EngineError.of(code, msg), null, rec, null);
    }

    private static double number(ActionRequest r, String key) throws Bad {
        Object v = r.params().get(key);
        if (v instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
        if (v instanceof String s) {
            try {
                double d = Double.parseDouble(s.trim());
                if (Double.isFinite(d)) return d;
            } catch (NumberFormatException ignored) {
                // falls through
            }
        }
        throw new Bad("parameter '" + key + "' must be a finite number");
    }

    private static int pos(ActionRequest r, String key, int current, int max) throws Bad {
        if (r.params().get(key) == null) return current;
        double d = number(r, key);
        if (d != Math.rint(d) || d < 1 || d > max) throw new Bad(key + " must be an integer in 1.." + max);
        return (int) d;
    }

    private static int index(Doc doc, ActionRequest r) throws Bad {
        double d = number(r, "index");
        synchronized (doc.roiList) {
            if (d != Math.rint(d) || d < 0 || d >= doc.roiList.size())
                throw new Bad("index must be an integer in 0.." + (doc.roiList.size() - 1) + " (ROI list size " + doc.roiList.size() + ")");
        }
        return (int) d;
    }

    // ---- undo / redo -----------------------------------------------------------------------------------

    private Outcome undo(Op op, Doc doc, Map<String, String> gBefore) {
        Snapshots.Snapshot snap;
        synchronized (doc.undo) {
            snap = doc.undo.peekFirst();
        }
        if (snap == null)
            return Outcome.failed(ErrorCode.PRECONDITION_FAILED, "nothing to undo for this document (no snapshot: virtual stack, over budget or no data action yet)");
        eng.emitState(op, OperationState.RUNNING, null);
        boolean hashAll = doc.imp.getStackSize() > 1;
        String hBefore = Pixels.hash(doc.imp, hashAll);
        // what a redo has to re-establish, recorded before the restore changes it (see Redo)
        Redo redo = redoEntry(doc, snap);
        Snapshots.Restored back = Snapshots.restore(snap, eng.results);
        eng.snapshots.release(snap);
        synchronized (doc.undo) {
            doc.redo.addFirst(redo);
        }
        boolean px = !hBefore.equals(Pixels.hash(doc.imp, hashAll));
        String actionId = eng.history.nextId();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("undoneActionId", snap.actionId);
        params.put("undoneOperation", snap.request.commandId() != null ? snap.request.commandId() : snap.request.operation());
        // what came back, for the History panel and Penny: pixels, the ROI list (count before -> after), Results rows
        params.put("pixelsRestored", back.pixels());
        if (back.rois()) {
            params.put("roisBefore", back.roisBefore());
            params.put("roisAfter", back.roisAfter());
        }
        if (back.results()) {
            params.put("resultsRowsBefore", back.rowsBefore());
            params.put("resultsRowsAfter", back.rowsAfter());
        }
        HistoryRecord rec = History.record(actionId, op.request.origin(), Provenance.TYPED, Operations.UNDO, "Undo", params,
                op.request.target(), List.of(), true, gBefore, Map.of(), OperationState.COMPLETED, null);
        eng.history.add(rec);
        StateDigest digest = new StateDigest(List.of(), List.of(),
                List.of(new DocumentChange(doc.id, px, back.pixels(), back.pixels(), back.rois() && back.roisBefore() != back.roisAfter(), back.pixels())),
                0, Map.of(), List.of(), List.of(), List.of());
        return Outcome.ok(digest, rec);
    }

    private Outcome redo(Op op, Doc doc) {
        Redo entry;
        synchronized (doc.undo) {
            entry = doc.redo.pollFirst();
        }
        if (entry == null) return Outcome.failed(ErrorCode.PRECONDITION_FAILED, "nothing to redo for this document");
        ActionRequest req = entry.request();
        ActionRequest replay = new ActionRequest(req.requestId() + "-redo", sc.fidelis.engine.api.Enums.Origin.REPLAY, req.kind(),
                req.commandId(), req.operation(), req.params(), req.target(), req.confirmationToken(),
                req.requestId());
        Op again = new Op(op.id, replay);
        again.redo = entry; // a ROI-list add/copy replays its recorded state (see Redo); every other request runs again
        Outcome o = req.kind() == sc.fidelis.engine.api.Enums.ActionKind.DIRECT ? runDirect(again) : eng.runner.run(again, true);
        if (o.state() != OperationState.COMPLETED) {
            synchronized (doc.undo) {
                doc.redo.addFirst(entry);
            }
        }
        return o;
    }

    /**
     * The redo entry for undoing {@code snap}: for a ROI-list add/copy, the objects the action added (the whole list when
     * it replaced it, else the entries beyond the snapshot's) and the Results rows this undo removes ({@code resultsAction}'s)
     * and puts back ({@code clearedRows}), all read before the restore. Other actions are redone by re-running their request.
     */
    private Redo redoEntry(Doc doc, Snapshots.Snapshot snap) {
        ActionRequest req = snap.request;
        if (req == null || req.kind() != sc.fidelis.engine.api.Enums.ActionKind.DIRECT || !Operations.ROI_LIST_ADD.equals(req.operation())
                || snap.rois == null)
            return Redo.of(req);
        boolean replace = Boolean.TRUE.equals(req.params().get("replace"));
        List<Roi> added = new ArrayList<>();
        synchronized (doc.roiList) {
            int start = replace ? 0 : Math.min(doc.roiList.size(), snap.rois.size());
            for (int i = start; i < doc.roiList.size(); i++) added.add((Roi) doc.roiList.get(i).clone());
        }
        List<MeasurementRow> removed = null;
        String action = null;
        if (snap.results) {
            action = snap.resultsAction != null ? snap.resultsAction : snap.actionId;
            removed = new ArrayList<>();
            if (action != null) for (MeasurementRow row : eng.results.all()) if (action.equals(row.actionId())) removed.add(row);
        }
        return new Redo(req, added, replace, removed, snap.clearedRows, action);
    }

    /** The Results rows the action {@code actionId} cleared, as its own undo snapshot on {@code src} recorded them; null when none. */
    private static List<MeasurementRow> clearedBy(Doc src, String actionId) {
        synchronized (src.undo) {
            for (Snapshots.Snapshot s : src.undo) if (actionId.equals(s.actionId) && s.clearedRows != null) return s.clearedRows;
        }
        return null;
    }

    // ---- session: SAVE_AS, NEW_IMAGE -------------------------------------------------------------------

    Outcome saveAs(Op op) {
        ActionRequest r = op.request;
        TargetScope ts = r.target();
        Doc doc = ts == null ? null : eng.registry.get(ts.document());
        if (doc == null) return Outcome.failed(ErrorCode.PRECONDITION_FAILED, "SAVE_AS needs a target document that is open");
        Object p = r.params().get("path");
        if (!(p instanceof String ps) || ps.isBlank()) return Outcome.failed(ErrorCode.VALIDATION_FAILED, "SAVE_AS needs params.path");
        Path out = Path.of(ps).toAbsolutePath().normalize();
        String format = r.params().get("format") instanceof String f ? f.toLowerCase(Locale.ROOT) : extension(out);
        String ijFormat = switch (format) {
            case "tif", "tiff" -> "tiff";
            case "png" -> "png";
            case "jpg", "jpeg" -> "jpeg";
            case "gif" -> "gif";
            case "bmp" -> "bmp";
            case "zip" -> "zip";
            case "pgm" -> "pgm";
            case "txt", "text image" -> "text image";
            default -> null;
        };
        if (ijFormat == null) return Outcome.failed(ErrorCode.VALIDATION_FAILED, "unsupported format '" + format + "'");
        for (Doc d : eng.registry.all()) {
            if (d.sourcePath != null && d.sourcePath.equals(out))
                return Outcome.failed(new EngineError(ErrorCode.CONFIRMATION_REQUIRED, "refusing to overwrite the input file " + out,
                        "OVERWRITE_INPUT", Map.of("path", out.toString()), true, List.of("choose a different output path")));
        }
        if (out.getParent() == null || !Files.isDirectory(out.getParent()))
            return Outcome.failed(ErrorCode.IO_ERROR, "output folder does not exist: " + out.getParent());
        if ("jpeg".equals(ijFormat)) {
            return Outcome.failed(new EngineError(ErrorCode.CONFIRMATION_REQUIRED, "JPEG is lossy and would not preserve the data",
                    "LOSSY_FORMAT", Map.of(), true, List.of("save as tiff or png")));
        }
        String actionId = eng.history.nextId();
        Map<String, String> gBefore = Globals.capture();
        eng.emitState(op, OperationState.RUNNING, null);
        Guard guard = Guard.begin(null);
        Throwable thrown = null;
        try {
            if (ijFormat.equals("tiff")) IJ.saveAsTiff(doc.imp, out.toString());
            else IJ.saveAs(doc.imp, ijFormat, out.toString());
        } catch (Throwable t) {
            thrown = t;
        }
        Guard.Report rep = guard.end();
        Path written = CommandRunner.writtenFile(out);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", out.toString().replace('\\', '/'));
        params.put("format", ijFormat);
        String err = null;
        if (rep.failed()) err = rep.errorText();
        else if (thrown != null) err = String.valueOf(thrown);
        else if (written == null) err = "no output file was written at " + out;
        if (err != null) {
            HistoryRecord rec = History.record(actionId, r.origin(), Provenance.TYPED, Operations.SAVE_AS, "Save As", params, ts,
                    List.of(), false, gBefore, Map.of(), OperationState.FAILED, err);
            eng.history.add(rec);
            return new Outcome(OperationState.FAILED, EngineError.of(rep.failed() || thrown != null ? ErrorCode.EXECUTION_FAILED
                    : ErrorCode.POSTCONDITION_FAILED, err), null, rec, null);
        }
        String sha = Registry.sha256(written);
        if (sha != null) params.put("outputSha256", sha);
        params.put("outputPath", written.toString().replace('\\', '/'));
        String macroFormat = switch (ijFormat) {
            case "tiff" -> "Tiff";
            case "png" -> "PNG";
            case "gif" -> "Gif";
            case "bmp" -> "BMP";
            case "zip" -> "ZIP";
            case "pgm" -> "PGM";
            default -> "Text Image";
        };
        List<String> lines = List.of("saveAs(\"" + macroFormat + "\", \"" + Options.macroString(out.toString().replace('\\', '/')) + "\");");
        HistoryRecord rec = History.record(actionId, r.origin(), Provenance.TYPED, Operations.SAVE_AS, "Save As", params, ts, lines,
                true, gBefore, Globals.delta(gBefore, Globals.capture()), OperationState.COMPLETED, null);
        eng.history.add(rec);
        eng.events.emit(seq -> new sc.fidelis.engine.api.EngineEvent.DocumentsChanged(seq));
        return Outcome.ok(new StateDigest(List.of(), List.of(), List.of(), 0, Map.of(), rep.newLogLines(), rep.windowsOpened(),
                rep.windowsClosed()), rec);
    }

    private static String extension(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "tiff" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    Outcome newImage(Op op) {
        ActionRequest r = op.request;
        Map<String, Object> pr = r.params();
        try {
            String title = pr.get("title") instanceof String s && !s.isBlank() ? s : "Untitled";
            String type = pr.get("type") instanceof String s ? s : "8-bit";
            String fill = pr.get("fill") instanceof String s ? s : "Black";
            if (!Set.of("8-bit", "16-bit", "32-bit", "RGB").contains(type)) throw new Bad("type must be 8-bit, 16-bit, 32-bit or RGB");
            if (!Set.of("White", "Black", "Ramp", "Noise").contains(fill)) throw new Bad("fill must be White, Black, Ramp or Noise");
            int w = (int) number(r, "width");
            int h = (int) number(r, "height");
            int n = pr.get("slices") == null ? 1 : (int) number(r, "slices");
            int c = pr.get("channels") == null ? 1 : (int) number(r, "channels");
            int t = pr.get("frames") == null ? 1 : (int) number(r, "frames");
            if (w < 1 || h < 1 || n < 1 || c < 1 || t < 1) throw new Bad("width, height, channels, slices and frames must be at least 1");
            if ((long) w * h * c * n * t > 400_000_000L) throw new Bad("the requested image is larger than 400 million pixels");
            boolean hyper = c > 1 || t > 1;
            String actionId = eng.history.nextId();
            Map<String, String> gBefore = Globals.capture();
            eng.emitState(op, OperationState.RUNNING, null);
            ImagePlus imp = hyper
                    ? IJ.createImage(title, type + " " + fill.toLowerCase(Locale.ROOT), w, h, c, n, t)
                    : IJ.createImage(title, type + " " + fill.toLowerCase(Locale.ROOT), w, h, n);
            if (imp == null) return Outcome.failed(ErrorCode.EXECUTION_FAILED, "ImageJ could not create the image");
            Doc d = eng.registry.register(imp, null);
            eng.focusDoc(d);
            eng.events.emit(seq -> new sc.fidelis.engine.api.EngineEvent.DocumentsChanged(seq));
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("title", title);
            params.put("type", type);
            params.put("width", (long) w);
            params.put("height", (long) h);
            params.put("slices", (long) n);
            if (hyper) {
                params.put("channels", (long) c);
                params.put("frames", (long) t);
            }
            params.put("fill", fill);
            params.put("document", d.id.value());
            List<String> lines = List.of("newImage(\"" + Options.macroString(title) + "\", \"" + type + " " + fill.toLowerCase(Locale.ROOT)
                    + "\", " + w + ", " + h + ", " + (hyper ? c + ", " + n + ", " + t : String.valueOf(n)) + ");");
            HistoryRecord rec = History.record(actionId, r.origin(), Provenance.TYPED, Operations.NEW_IMAGE, "New Image", params, null,
                    lines, true, gBefore, Map.of(), OperationState.COMPLETED, null);
            eng.history.add(rec);
            return Outcome.ok(new StateDigest(List.of(d.id), List.of(), List.of(), 0, Map.of(), List.of(), List.of(), List.of()), rec);
        } catch (Bad e) {
            return Outcome.failed(new EngineError(ErrorCode.VALIDATION_FAILED, "NEW_IMAGE: " + e.getMessage(), null, Map.of(), true, List.of()));
        }
    }
}
