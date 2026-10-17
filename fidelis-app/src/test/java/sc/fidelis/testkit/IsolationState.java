package sc.fidelis.testkit;

import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.macro.Interpreter;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import ij.plugin.filter.ParticleAnalyzer;
import ij.plugin.frame.RoiManager;
import ij.process.ImageConverter;
import ij.text.TextWindow;
import sc.fidelis.engine.DefaultEngine;
import sc.fidelis.engine.Engines;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.EngineListener;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.TargetScope;

/**
 * The shared state of the test JVM that one test class can leave behind for the next, and its reset
 * (issue #4; used by {@link SessionIsolation}). Loads {@code ij.*}: touch it only after {@link FijiFixture#imageJ()}.
 *
 * <p>Reset, in order: engine documents closed through the engine (discarding changes), IJ1 images the engine
 * does not own closed, leaked dialogs disposed, ROI Manager frames other than the live singleton disposed and
 * the singleton emptied, Results and Summary tables cleared and the engine's Results mirror re-read, IJ1 batch
 * mode and Analyze Particles' one-shot statics cleared, and {@code blackBackground}, measurements, precision and
 * conversions restored to the values captured when the context started. Engine history is not cleared: it is the
 * session record, and tests that read the macro take the part they produced ({@code exportMacroSince}).
 */
public final class IsolationState {
    private static Map<String, String> baseline;

    private IsolationState() {}

    /** Record the result-affecting globals of a fresh context (called once by {@link FijiFixture#imageJ()}). */
    static synchronized void captureBaseline() {
        if (baseline == null) baseline = globals();
    }

    static Map<String, String> globals() {
        Map<String, String> g = new LinkedHashMap<>();
        g.put("blackBackground", String.valueOf(Prefs.blackBackground));
        g.put("measurements", String.valueOf(Analyzer.getMeasurements()));
        g.put("precision", String.valueOf(Analyzer.getPrecision()));
        g.put("doScaling", String.valueOf(ImageConverter.getDoScaling()));
        g.put("weightedColor", String.valueOf(Prefs.weightedColor));
        return g;
    }

    /** One line describing the state that matters between classes (documents, images, singletons, globals, listeners). */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        Engine e = Engines.current();
        sb.append("docs=").append(e == null ? "-" : e.documents().size());
        sb.append(" focused=").append(e == null ? "-" : e.focused().map(d -> d.value()).orElse("none"));
        int[] ids = WindowManager.getIDList();
        sb.append(" ijImages=").append(ids == null ? 0 : ids.length);
        sb.append(" batchMode=").append(Interpreter.isBatchMode());
        RoiManager live = RoiManager.getRawInstance();
        sb.append(" roiManager=").append(live == null ? "none" : live.getCount() + (live.isShowing() ? " shown" : " hidden"));
        sb.append(" staleRoiManagers=").append(staleRoiManagers(live).size());
        sb.append(" results=").append(ResultsTable.getResultsTable().size());
        if (e != null) sb.append("/engine ").append(e.results().size());
        sb.append(" dialogs=").append(showingDialogs().size());
        sb.append(" listeners=").append(e instanceof DefaultEngine d ? d.listeners().size() : 0);
        sb.append(" globals=").append(globals());
        return sb.toString();
    }

    /** Reset the shared state before {@code who} runs; prints what was found and what was changed. */
    public static synchronized void reset(String who) {
        String found = describe();
        List<String> fixed = new ArrayList<>();
        Engine e = Engines.current();

        if (e != null) {
            for (DocumentInfo d : new ArrayList<>(e.documents())) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("discard", true);
                try {
                    e.submit(ActionRequest.session(Origin.PANEL, ActionRequest.Operations.CLOSE, p, TargetScope.of(d.id())))
                            .get(30, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    throw new IllegalStateException("isolation: could not close " + d.id() + " (" + d.title() + ")", ex);
                }
                fixed.add("closed " + d.id().value());
            }
        }
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp == null) continue;
                imp.changes = false;
                Edt.run(imp::close);
                fixed.add("closed image '" + imp.getTitle() + "'");
            }
        }
        for (Dialog d : showingDialogs()) {
            fixed.add("disposed dialog '" + d.getTitle() + "'");
            Edt.run(d::dispose);
        }

        RoiManager live = RoiManager.getRawInstance();
        for (RoiManager stale : staleRoiManagers(live)) {
            fixed.add("disposed stale ROI Manager");
            Edt.run(() -> {
                WindowManager.removeWindow((Frame) stale);
                stale.dispose();
            });
        }
        if (live != null) {
            if (live.getCount() > 0) fixed.add("emptied ROI Manager (" + live.getCount() + ")");
            Edt.run(() -> {
                live.reset();
                if (live.isShowing()) live.setVisible(false);
            });
        }
        ParticleAnalyzer.setRoiManager(null);
        ParticleAnalyzer.setResultsTable(null);
        ParticleAnalyzer.setSummaryTable(null);
        if (Interpreter.isBatchMode()) {
            fixed.add("left batch mode");
            Interpreter.batchMode = false;
        }

        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt.size() > 0) fixed.add("cleared " + rt.size() + " Results rows");
        rt.reset();
        for (Frame f : WindowManager.getNonImageWindows()) {
            if (f instanceof TextWindow tw && f.getTitle().startsWith("Summary")) {
                fixed.add("closed '" + f.getTitle() + "'");
                Edt.run(() -> tw.close(false));
            }
        }
        if (e instanceof DefaultEngine d) d.resyncResults();

        Map<String, String> g = globals();
        if (baseline != null && !g.equals(baseline)) {
            fixed.add("restored globals " + g + " -> " + baseline);
            Prefs.blackBackground = Boolean.parseBoolean(baseline.get("blackBackground"));
            Analyzer.setMeasurements(Integer.parseInt(baseline.get("measurements")));
            Analyzer.setPrecision(Integer.parseInt(baseline.get("precision")));
            ImageConverter.setDoScaling(Boolean.parseBoolean(baseline.get("doScaling")));
            Prefs.weightedColor = Boolean.parseBoolean(baseline.get("weightedColor"));
        }

        if (fixed.isEmpty()) System.out.println("[isolation] " + who + ": clean (" + found + ")");
        else System.out.println("[isolation] " + who + ": found " + found + "; reset: " + String.join(", ", fixed));
    }

    /** The engine's listeners as an identity set (empty without a product engine). */
    static Set<Object> listeners() {
        Set<Object> s = Collections.newSetFromMap(new IdentityHashMap<>());
        if (Engines.current() instanceof DefaultEngine d) s.addAll(d.listeners());
        return s;
    }

    /** Remove engine listeners registered while {@code who} ran and still registered after it (a disposed workspace). */
    static void removeListenersAddedSince(Set<?> before, String who) {
        if (!(Engines.current() instanceof DefaultEngine d)) return;
        Set<Object> was = Collections.newSetFromMap(new IdentityHashMap<>());
        was.addAll(before);
        List<String> names = new ArrayList<>();
        for (EngineListener l : d.listeners()) {
            if (was.contains(l)) continue;
            d.removeListener(l);
            names.add(l.getClass().getName());
        }
        if (!names.isEmpty())
            System.out.println("[isolation] after " + who + ": removed " + names.size() + " engine listener(s) it left registered " + names);
    }

    private static List<RoiManager> staleRoiManagers(RoiManager live) {
        List<RoiManager> out = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w instanceof RoiManager rm && rm != live && rm.isDisplayable()) out.add(rm);
        }
        for (Frame f : WindowManager.getNonImageWindows()) {
            if (f instanceof RoiManager rm && rm != live && !out.contains(rm)) out.add(rm);
        }
        return out;
    }

    private static List<Dialog> showingDialogs() {
        List<Dialog> out = new ArrayList<>();
        for (Window w : Window.getWindows()) if (w instanceof Dialog d && d.isShowing()) out.add(d);
        return out;
    }

    /** Run on the EDT and wait (IJ1 windows are AWT components). */
    private static final class Edt {
        static void run(Runnable r) {
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
                return;
            }
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (Exception ex) {
                throw new IllegalStateException("isolation: EDT task failed", ex);
            }
        }
    }
}
