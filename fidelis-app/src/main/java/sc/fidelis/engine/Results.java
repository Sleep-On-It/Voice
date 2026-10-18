package sc.fidelis.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.MeasurementRow;

/**
 * The engine's mirror of the IJ1 system Results table at full double precision (E4, #5). After every
 * synchronisation the mirror equals the live table row for row (label and every column, bit for bit), and each
 * row carries the document and history action that produced it.
 *
 * <p>Synchronisation compares content, not a row count: leading mirror rows that still equal the table keep their
 * attribution; from the first row that differs (or from row 0 after a restart such as {@code Clear Results}) the
 * mirror is rebuilt from the table and the rebuilt rows are attributed to the action being settled. A table that
 * only gained or lost columns (Set Measurements with the Results window open) keeps its attribution.
 *
 * <p>Attribution follows command boundaries. A native command (observed, run by IJ1 for the user) owns the rows
 * appended after it started until the next command starts: {@link #beforeNative} first settles the previous owner's
 * rows, so back-to-back commands keep exact attribution even when their follow-ups have not run yet, and a delayed
 * follow-up ({@link #settleNative}) can never hand a later command's rows to an earlier one. A typed action settles
 * pending native rows before it runs ({@link #beginTyped}) and captures its own rows when it ends ({@link #capture}).
 */
final class Results {
    /** {@code clear} as an option word (Analyze Particles' "Clear results"): the command restarts the table. */
    private static final Pattern CLEAR_OPTION = Pattern.compile("(^|\\s)clear(\\s|$)");

    private final Events events;
    private final List<MeasurementRow> rows = new ArrayList<>();
    /** A command that restarts the table was seen since the last synchronisation: the next one keeps no row. */
    private boolean restartPending;
    /** Times rows of the mirror were dropped because the table restarted or diverged (diagnostics and tests). */
    private int generation;
    /** The native command that owns rows appended since it started (null: none, or a typed action took over). */
    private DocumentId ownerDoc;
    private String ownerAction;

    Results(Events events) {
        this.events = events;
    }

    /** Current row count of the IJ1 system table. */
    static int tableSize() {
        ResultsTable rt = ResultsTable.getResultsTable();
        return rt == null ? 0 : rt.size();
    }

    /** True when running {@code label} with {@code options} empties the system table before it adds rows. */
    static boolean restartsTable(String label, String options) {
        if ("Clear Results".equals(label)) return true;
        return "Analyze Particles...".equals(label) && options != null && CLEAR_OPTION.matcher(options).find();
    }

    synchronized int generation() {
        return generation;
    }

    // ---- boundaries -----------------------------------------------------------------------------------

    /**
     * A native command is about to run (called synchronously on its thread by the command observer). Rows appended
     * since the previous native command started are settled under that command first; then a table restart by this
     * command is noted, and this command becomes the owner of the rows that follow.
     */
    void beforeNative(String label, String options, DocumentId doc, String actionId) {
        synchronized (this) {
            sync(Integer.MAX_VALUE, ownerDoc, ownerAction);
            if (restartsTable(label, options)) restartPending = true;
            ownerDoc = doc;
            ownerAction = actionId;
        }
    }

    /** A command running inside a macro (CODE action) restarts the table: the macro's capture rebuilds from row 0. */
    synchronized void noteChildCommand(String label, String options) {
        if (restartsTable(label, options)) restartPending = true;
    }

    /** The native command {@code actionId} restarts the table (its dialog said so, e.g. Analyze Particles' clear). */
    synchronized void noteRestart(String actionId) {
        if (actionId != null && actionId.equals(ownerAction)) restartPending = true;
    }

    /**
     * Follow-up of a native command (after it finished, or its timer fired): settle the rows appended so far under
     * the native command that owns them now. That is the command itself unless a later command has started meanwhile,
     * in which case the earlier command's rows were already settled at that boundary.
     */
    synchronized int settleNative() {
        return sync(Integer.MAX_VALUE, ownerDoc, ownerAction);
    }

    /** A typed action is about to run: settle rows a native command left, then no native command owns new rows. */
    synchronized void beginTyped() {
        sync(Integer.MAX_VALUE, ownerDoc, ownerAction);
        ownerDoc = null;
        ownerAction = null;
    }

    /**
     * A typed action ended: rows of the table from {@code from} on are its own (0 for a command that cleared the table
     * first), rows before it keep their attribution where they still equal the table. Returns the rows captured.
     */
    synchronized int capture(int from, DocumentId doc, String actionId) {
        ownerDoc = null;
        ownerAction = null;
        return sync(Math.max(0, from), doc, actionId);
    }

    /** Re-read the table after it was changed outside any action (a reset by other code); no native command owns rows. */
    synchronized void markSeen() {
        sync(Integer.MAX_VALUE, ownerDoc, ownerAction);
        ownerDoc = null;
        ownerAction = null;
    }

    // ---- synchronisation ------------------------------------------------------------------------------

    private record TableRow(String label, Map<String, Double> values) {
    }

    private static List<TableRow> read(ResultsTable rt) {
        List<TableRow> out = new ArrayList<>();
        if (rt == null) return out;
        int n = rt.size();
        String[] headings = rt.getHeadings();
        List<Integer> cols = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String h : headings) {
            if (h == null || h.equals("Label")) continue;
            int col = rt.getColumnIndex(h);
            if (col == ResultsTable.COLUMN_NOT_FOUND) continue;
            cols.add(col);
            names.add(h);
        }
        for (int r = 0; r < n; r++) {
            Map<String, Double> values = new LinkedHashMap<>();
            for (int i = 0; i < cols.size(); i++) values.put(names.get(i), rt.getValueAsDouble(cols.get(i), r));
            out.add(new TableRow(rt.getLabel(r), values));
        }
        return out;
    }

    private static boolean same(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    /**
     * Same label and the same bits in every column both rows have, and at least one column in common unless neither
     * row has any (columns may have been added or removed: the row is the same measurement).
     */
    private static boolean sameMeasurement(MeasurementRow old, TableRow now) {
        if (!Objects.equals(old.label(), now.label())) return false;
        int common = 0;
        for (Map.Entry<String, Double> e : now.values().entrySet()) {
            Double v = old.values().get(e.getKey());
            if (v == null) continue;
            if (!same(v, e.getValue())) return false;
            common++;
        }
        return common > 0 || (old.values().isEmpty() && now.values().isEmpty());
    }

    private static boolean identical(MeasurementRow old, TableRow now) {
        if (!old.values().keySet().equals(now.values().keySet())) return false;
        for (Map.Entry<String, Double> e : now.values().entrySet()) if (!same(old.values().get(e.getKey()), e.getValue())) return false;
        return Objects.equals(old.label(), now.label());
    }

    /**
     * Make the mirror equal the live table. At most {@code keepAtMost} leading rows (none after a restart) keep their
     * attribution, and only while they still hold the same measurement; every other row is attributed to
     * {@code doc}/{@code actionId}. Caller holds the lock. Returns the number of rows (re)attributed.
     */
    private int sync(int keepAtMost, DocumentId doc, String actionId) {
        List<TableRow> table = read(ResultsTable.getResultsTable());
        int limit = restartPending ? 0 : Math.min(keepAtMost, Math.min(rows.size(), table.size()));
        restartPending = false;
        List<MeasurementRow> next = new ArrayList<>(table.size());
        boolean changed = false;
        int keep = 0;
        for (; keep < limit; keep++) {
            MeasurementRow old = rows.get(keep);
            TableRow now = table.get(keep);
            if (!sameMeasurement(old, now)) break;
            if (identical(old, now)) {
                next.add(old);
            } else { // columns added or removed: same row, values re-read
                next.add(new MeasurementRow(keep, now.label(), now.values(), old.document(), old.actionId()));
                changed = true;
            }
        }
        if (keep < rows.size()) {
            generation++;
            changed = true;
        }
        for (int r = keep; r < table.size(); r++) {
            TableRow now = table.get(r);
            next.add(new MeasurementRow(r, now.label(), now.values(), doc, actionId));
            changed = true;
        }
        int captured = table.size() - keep;
        if (changed) {
            rows.clear();
            rows.addAll(next);
            int total = rows.size();
            events.emit(seq -> new EngineEvent.ResultsChanged(seq, total));
        }
        return captured;
    }

    synchronized List<MeasurementRow> all() {
        return List.copyOf(rows);
    }

    /**
     * Undo what one action did to the table: its rows are removed, and the rows it cleared ({@code clearedRows}, when
     * it restarted the table) are put back in front of the survivors. The live IJ1 table is rewritten from the result,
     * so the mirror stays equal to it row for row; other actions' rows keep their attribution and their bits. Returns
     * {rows before, rows after}.
     */
    synchronized int[] undoAction(String actionId, List<MeasurementRow> clearedRows) {
        int before = rows.size();
        List<MeasurementRow> next = new ArrayList<>();
        if (clearedRows != null) {
            // rows already back in the table (the same clear undone through another document's snapshot) are not duplicated
            Set<String> present = new HashSet<>();
            for (MeasurementRow r : rows) if (r.actionId() != null) present.add(r.actionId());
            for (MeasurementRow r : clearedRows) if (r.actionId() == null || !present.contains(r.actionId())) next.add(r);
        }
        for (MeasurementRow r : rows) if (actionId == null || !actionId.equals(r.actionId())) next.add(r);
        rewrite(next);
        return new int[] {before, rows.size()};
    }

    /**
     * Redo what an undo did to the table: the rows that undo put back ({@code restored}: rows the action had cleared,
     * together with any other row from before that clear) are cleared again, and the rows it removed ({@code removed}:
     * the action's own) are appended again with their bits and attribution intact. Returns {rows before, rows after}.
     */
    synchronized int[] redoAction(List<MeasurementRow> restored, List<MeasurementRow> removed) {
        int before = rows.size();
        Set<String> cleared = new HashSet<>();
        if (restored != null) for (MeasurementRow r : restored) if (r.actionId() != null) cleared.add(r.actionId());
        List<MeasurementRow> next = new ArrayList<>();
        for (MeasurementRow r : rows) {
            boolean wiped = restored != null && (r.actionId() == null || cleared.contains(r.actionId()));
            if (!wiped) next.add(r);
        }
        if (removed != null) next.addAll(removed);
        rewrite(next);
        return new int[] {before, rows.size()};
    }

    /** Make the live IJ1 table and the mirror equal {@code next} (renumbered), as one restart of the table. Caller holds the lock. */
    private void rewrite(List<MeasurementRow> next) {
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt == null) rt = new ResultsTable();
        rt.reset();
        // Analyzer.measure() resets a table whose last standard heading is missing (what Analyzer.reset() sets up):
        // keep the standard headings, so the next Measure appends instead of starting over
        rt.setDefaultHeadings();
        List<MeasurementRow> renumbered = new ArrayList<>(next.size());
        for (MeasurementRow r : next) {
            rt.incrementCounter();
            if (r.label() != null) rt.addLabel(r.label());
            for (Map.Entry<String, Double> e : r.values().entrySet()) rt.addValue(e.getKey(), e.getValue());
            renumbered.add(new MeasurementRow(renumbered.size(), r.label(), r.values(), r.document(), r.actionId()));
        }
        if (ij.WindowManager.getFrame("Results") != null) rt.show("Results");
        rows.clear();
        rows.addAll(renumbered);
        restartPending = false;
        ownerDoc = null;
        ownerAction = null;
        generation++;
        int total = rows.size();
        events.emit(seq -> new EngineEvent.ResultsChanged(seq, total));
    }

    MeasurementRow.Columns columns() {
        ResultsTable rt = ResultsTable.getResultsTable();
        List<String> h = new ArrayList<>();
        if (rt != null) for (String s : rt.getHeadings()) if (s != null && !s.equals("Label")) h.add(s);
        return new MeasurementRow.Columns(h, Analyzer.getPrecision());
    }
}
