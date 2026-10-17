package sc.fidelis.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.MeasurementRow;

/**
 * Copies rows of the IJ1 system Results table at full double precision into engine-owned records (E4).
 * The engine list mirrors the live table: rows are attributed to the action that produced them, and
 * when IJ1 restarts the table (Analyze Particles with {@code clear}, Clear Results, a shrink seen after
 * a native command) the list is rebuilt from row 0, so the Results panel shows what Fiji's window shows.
 */
final class Results {
    private final Events events;
    private final List<MeasurementRow> rows = new ArrayList<>();
    private int lastSeen;
    /** Fingerprint of table row {@code lastSeen - 1} when it was accounted for: a different row there means the table restarted. */
    private String lastRowKey = "";
    /** A native command about to run was seen to restart the table (Clear Results, a shrink, a changed last row). */
    private boolean restartPending;
    /** Times the mirror was rebuilt from row 0 (diagnostics and tests). */
    private int generation;
    /** The last native command whose rows may still be uncaptured: captured at the start of the next native command. */
    private DocumentId pendingDoc;
    private String pendingAction;

    Results(Events events) {
        this.events = events;
    }

    /** Current row count of the IJ1 system table. */
    static int tableSize() {
        ResultsTable rt = ResultsTable.getResultsTable();
        return rt == null ? 0 : rt.size();
    }

    /** Label and every value of row {@code r} as exact bits; "" when the row does not exist. */
    private static String rowKey(ResultsTable rt, int r) {
        if (rt == null || r < 0 || r >= rt.size()) return "";
        StringBuilder sb = new StringBuilder(String.valueOf(rt.getLabel(r)));
        for (String h : rt.getHeadings()) {
            if (h == null || h.equals("Label")) continue;
            int col = rt.getColumnIndex(h);
            if (col == ResultsTable.COLUMN_NOT_FOUND) continue;
            sb.append('|').append(Double.doubleToLongBits(rt.getValueAsDouble(col, r)));
        }
        return sb.toString();
    }

    synchronized int generation() {
        return generation;
    }

    /**
     * Capture table rows {@code [from, size())}; returns how many were captured. {@code from == 0}, a list
     * longer than {@code from} or a table shorter than the list all mean the table restarted: the list is
     * rebuilt from row 0 under the given attribution.
     */
    int capture(int from, DocumentId doc, String actionId) {
        ResultsTable rt = ResultsTable.getResultsTable();
        int n = rt == null ? 0 : rt.size();
        int start;
        boolean rebuilt;
        synchronized (this) {
            rebuilt = from <= 0 || rows.size() > from || n < rows.size();
            if (rebuilt) rows.clear();
            start = rebuilt ? 0 : Math.min(from, rows.size());
        }
        List<MeasurementRow> fresh = new ArrayList<>();
        if (rt != null && n > start) {
            String[] headings = rt.getHeadings();
            for (int r = start; r < n; r++) {
                Map<String, Double> values = new LinkedHashMap<>();
                for (String h : headings) {
                    if (h == null || h.equals("Label")) continue;
                    int col = rt.getColumnIndex(h);
                    if (col == ResultsTable.COLUMN_NOT_FOUND) continue;
                    values.put(h, rt.getValueAsDouble(col, r));
                }
                fresh.add(new MeasurementRow(r, rt.getLabel(r), values, doc, actionId));
            }
        }
        int total;
        String key = rowKey(rt, n - 1);
        synchronized (this) {
            rows.addAll(fresh);
            total = rows.size();
            lastSeen = n;
            lastRowKey = key;
            restartPending = false;
            if (rebuilt) generation++;
            if (actionId != null && actionId.equals(pendingAction)) pendingAction = null;
        }
        if (rebuilt || !fresh.isEmpty()) events.emit(seq -> new EngineEvent.ResultsChanged(seq, total));
        return fresh.size();
    }

    /** Note the current table size as accounted for (after typed actions); a shrunken table rebuilds the list. */
    void markSeen() {
        ResultsTable rt = ResultsTable.getResultsTable();
        int n = tableSize();
        String key = rowKey(rt, n - 1);
        boolean shrunk;
        synchronized (this) {
            shrunk = n < rows.size();
            lastSeen = n;
            lastRowKey = key;
            restartPending = false;
            pendingAction = null;
        }
        if (shrunk) capture(0, null, null);
    }

    /**
     * Called synchronously when a native command is about to run (#5 native drift). First the rows of the previous
     * native command are captured under its own id, so back-to-back native commands keep exact attribution
     * even though their timers have not fired yet. Then a restart of the table is noted when the command is
     * {@code Clear Results}, the table is shorter than accounted for, or the last accounted row changed: the
     * next capture rebuilds the mirror from row 0 instead of trusting a row count that happens to match.
     */
    void beforeNative(String label, DocumentId doc, String actionId) {
        DocumentId prevDoc;
        String prevAction;
        synchronized (this) {
            prevDoc = pendingDoc;
            prevAction = pendingAction;
        }
        if (prevAction != null) captureNative(prevDoc, prevAction);
        ResultsTable rt = ResultsTable.getResultsTable();
        int n = rt == null ? 0 : rt.size();
        String key = rowKey(rt, n - 1);
        synchronized (this) {
            if ("Clear Results".equals(label) || n < lastSeen || (lastSeen > 0 && n >= lastSeen && !rowKey(rt, lastSeen - 1).equals(lastRowKey)))
                restartPending = true;
            if (n == lastSeen && !restartPending) lastRowKey = key;
            pendingDoc = doc;
            pendingAction = actionId;
        }
    }

    /**
     * After a native command: capture rows it added to the system table, or rebuild the mirror when the table
     * restarted (noted by {@link #beforeNative}, a shrink, or a changed last accounted row).
     */
    int captureNative(DocumentId doc, String actionId) {
        ResultsTable rt = ResultsTable.getResultsTable();
        int n = rt == null ? 0 : rt.size();
        int from;
        boolean restart;
        synchronized (this) {
            restart = restartPending || n < lastSeen || (lastSeen > 0 && !rowKey(rt, lastSeen - 1).equals(lastRowKey));
            from = restart ? 0 : lastSeen;
            if (!restart && from > 0 && n <= from) {
                if (actionId != null && actionId.equals(pendingAction)) pendingAction = null;
                return 0; // nothing new
            }
        }
        return capture(from, doc, actionId);
    }

    synchronized List<MeasurementRow> all() {
        return List.copyOf(rows);
    }

    MeasurementRow.Columns columns() {
        ResultsTable rt = ResultsTable.getResultsTable();
        List<String> h = new ArrayList<>();
        if (rt != null) for (String s : rt.getHeadings()) if (s != null && !s.equals("Label")) h.add(s);
        return new MeasurementRow.Columns(h, Analyzer.getPrecision());
    }
}
