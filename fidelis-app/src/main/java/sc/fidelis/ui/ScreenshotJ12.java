package sc.fidelis.ui;

import static sc.fidelis.ui.ScreenshotRun.shoot;
import static sc.fidelis.ui.ScreenshotRun.waitUntil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.SwingUtilities;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.OperationState;
import sc.fidelis.engine.api.Enums.Origin;

/**
 * The J-12 demo and the instruction and quit flows of the {@code --screenshot} debug mode (U3). Every step goes
 * through the UI's own paths ({@link UiContext#runCommand}, the Parameters form, the Threshold panel, the command
 * bar's proposal sheet), and the numbers are read back from the engine, never from the picture.
 */
final class ScreenshotJ12 {
    private ScreenshotJ12() {}

    private static final Path BLOBS = Path.of("fixtures", "cache", "FX-003", "blobs.gif");

    // ---- J-12 ---------------------------------------------------------------------------------

    /** One catalogue command as a user does it: runCommand opens the pre-filled form, Run presses its button. */
    private static void uiCommand(Workspace w, UiContext ctx, String id, Map<String, Object> prefill) throws Exception {
        var engine = w.engine();
        var schema = engine.schema(id).orElseThrow(() -> new IllegalStateException("no schema " + id));
        boolean form = schema.schemaComplete() && !schema.parameters().isEmpty();
        int h0 = engine.history().size();
        SwingUtilities.invokeAndWait(() -> ctx.runCommand(id, Origin.PANEL, prefill));
        if (form) {
            String[] macro = new String[1];
            SwingUtilities.invokeAndWait(() -> macro[0] = w.parameters().macroText());
            System.out.println("[fidelis]   form macro: " + macro[0]);
            SwingUtilities.invokeAndWait(() -> w.parameters().runNow());
        }
        waitUntil(() -> engine.history().size() > h0, 180);
        waitUntil(() -> !w.parameters().isBusy(), 60);
        var h = engine.history().get(engine.history().size() - 1);
        System.out.println("[fidelis] " + id + (form ? " (form)" : " (direct)") + " -> " + h.outcome() + " " + h.macroLines()
                + (h.globalsDelta() == null || h.globalsDelta().isEmpty() ? "" : " globalsDelta=" + h.globalsDelta())
                + (h.errorSummary() == null ? "" : " error=" + h.errorSummary()));
        if (h.outcome() != OperationState.COMPLETED) throw new IllegalStateException(id + " ended " + h.outcome());
    }

    private static Map<String, Object> bools(boolean value, String... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) m.put(k, value);
        return m;
    }

    static void j12(Workspace w, UiContext ctx, String name, Path repo) throws Exception {
        var engine = w.engine();
        Path blobs = repo.resolve(BLOBS);
        if (!Files.isRegularFile(blobs)) throw new IllegalStateException("fixture not cached: " + blobs);
        int docs0 = engine.documents().size();
        if (name.equals("j12-open")) { // only open the image; the typed instructions do the rest
            ctx.openPath(blobs).get();
            waitUntil(() -> engine.documents().size() > docs0 && engine.focused().isPresent(), 30);
            waitUntil(() -> w.documents().focusedView() != null, 10);
            return;
        }
        ctx.openPath(blobs).get();
        waitUntil(() -> engine.documents().size() > docs0 && engine.focused().isPresent(), 30);
        waitUntil(() -> w.documents().focusedView() != null, 10);
        DocumentId blobsDoc = engine.focused().orElseThrow();
        System.out.println("[fidelis] J-12 opened " + engine.document(blobsDoc).map(d -> d.title()).orElse("?")
                + "; globals before: " + engine.globals());

        Map<String, Object> sb = new LinkedHashMap<>();
        sb.put("rolling", 50);
        sb.putAll(bools(false, "light", "separate", "create", "sliding", "disable"));
        uiCommand(w, ctx, "ij1:Process>Subtract Background...", sb);
        uiCommand(w, ctx, "ij1:Process>Filters>Gaussian Blur...", new LinkedHashMap<>(Map.of("sigma", 2)));

        // Threshold panel: Otsu, dark background (DIRECT SET_AUTO_THRESHOLD)
        int h0 = engine.history().size();
        SwingUtilities.invokeAndWait(() -> {
            w.docks().showThreshold();
            w.threshold().autoThreshold("Otsu", true);
        });
        waitUntil(() -> engine.history().size() > h0, 60);
        var th = engine.history().get(engine.history().size() - 1);
        System.out.println("[fidelis] threshold panel -> " + (th.commandId() != null ? th.commandId() : th.label()) + " " + th.params()
                + " " + th.outcome() + " " + th.macroLines());

        // Convert to Mask: Prefs.blackBackground is a global; show it, then set it through the request's 'black' parameter
        System.out.println("[fidelis] Prefs.blackBackground before Convert to Mask = " + engine.globals().get("blackBackground"));
        var cm = engine.schema("ij1:Process>Binary>Convert to Mask").orElseThrow();
        System.out.println("[fidelis] Convert to Mask schema parameters: " + cm.parameters().stream().map(p -> p.key()).toList()
                + "; hiddenGlobals " + cm.hiddenGlobals());
        int h1 = engine.history().size();
        SwingUtilities.invokeAndWait(() -> ctx.submit(ActionRequest.command(Origin.PANEL, "ij1:Process>Binary>Convert to Mask",
                new LinkedHashMap<>(Map.of("black", true)), ctx.target()), "Convert to Mask"));
        waitUntil(() -> engine.history().size() > h1, 60);
        var cmh = engine.history().get(engine.history().size() - 1);
        System.out.println("[fidelis] Convert to Mask -> " + cmh.outcome() + " " + cmh.macroLines() + " globalsDelta=" + cmh.globalsDelta()
                + "; Prefs.blackBackground now " + engine.globals().get("blackBackground"));
        if (cmh.outcome() != OperationState.COMPLETED) throw new IllegalStateException("Convert to Mask " + cmh.outcome());
        if (!"true".equals(engine.globals().get("blackBackground"))) throw new IllegalStateException("blackBackground is not true");

        uiCommand(w, ctx, "ij1:Process>Binary>Watershed", Map.of());

        Map<String, Object> sm = bools(true, "area", "mean", "min", "integrated");
        sm.putAll(bools(false, "standard", "modal", "centroid", "center", "perimeter", "bounding", "fit", "shape", "feret's", "median",
                "skewness", "kurtosis", "area_fraction", "stack", "limit", "display", "invert", "scientific", "add", "nan"));
        sm.put("redirect", "None");
        sm.put("decimal", 9);
        uiCommand(w, ctx, "ij1:Analyze>Set Measurements...", sm);
        uiCommand(w, ctx, "ij1:Analyze>Measure", Map.of());

        var rows = engine.results();
        var last = rows.get(rows.size() - 1);
        double area = last.values().get("Area");
        double mean = last.values().get("Mean");
        System.out.println("[fidelis] J-12 Measure row (" + rows.size() + " row(s)): Area=" + ResultsPanel.full(area) + " Mean=" + ResultsPanel.full(mean));
        if (area != 65024.0 || mean != 155.9435746801181) throw new IllegalStateException("Measure row differs from the pinned-Fiji baseline");

        if (name.equals("j12-mask")) return; // Analyze Particles is left for the typed instruction

        Map<String, Object> ap = new LinkedHashMap<>();
        ap.put("size", "20-Infinity");
        ap.put("circularity", "0.00-1.00");
        ap.put("show", "Masks");
        ap.putAll(bools(true, "display", "clear", "include", "summarize", "add"));
        ap.putAll(bools(false, "exclude", "overlay", "composite", "record", "in_situ"));
        uiCommand(w, ctx, "ij1:Analyze>Analyze Particles...", ap);
        Thread.sleep(800);
        reportNumbers(w);
        var rs = engine.results();
        int roisMax = 0;
        for (var d : engine.documents()) roisMax = Math.max(roisMax, engine.rois(d.id()).size());
        if (roisMax != 71 || rs.size() < 71)
            throw new IllegalStateException("expected 71 ROIs and 71 result rows, got " + roisMax + " / " + rs.size());
        System.out.println("[fidelis] J-12 ASSERTED: Measure Area 65024 Mean 155.9435746801181; Analyze Particles ROIs=" + roisMax + ", Results rows=" + rs.size());
        System.out.println("[fidelis] exportMacro():\n" + engine.exportMacro());
        SwingUtilities.invokeAndWait(() -> {
            w.docks().showHistory();
            w.docks().showResults();
            w.docks().applyDefaultDividers();
        });
    }

    static void reportNumbers(Workspace w) {
        var engine = w.engine();
        var rows = engine.results();
        System.out.println("[fidelis] engine.results(): " + rows.size() + " row(s); columns " + engine.resultColumns().headings());
        if (!rows.isEmpty()) System.out.println("[fidelis]   first row " + rows.get(0).values() + "; last row " + rows.get(rows.size() - 1).values());
        for (var d : engine.documents())
            System.out.println("[fidelis]   document '" + d.title() + "' engine.rois=" + engine.rois(d.id()).size());
    }

    // ---- typed instruction --------------------------------------------------------------------

    /**
     * Interpret the instruction, screenshot the proposal sheet and print it; with {@code --confirm} press Confirm,
     * wait for the engine, print the engine's numbers and screenshot the result.
     */
    static void instructionFlow(Workspace w, UiContext ctx, Workspace.Options opts, Path out) throws Exception {
        String[] all = opts.instruction().split("\\|\\|");
        for (int i = 0; i < all.length; i++) {
            // several instructions (separated by ||) run one after the other; only the first and the last are photographed
            instructionOne(w, ctx, all[i].trim(), opts, i == 0 || i == all.length - 1 ? out : null);
        }
    }

    private static void instructionOne(Workspace w, UiContext ctx, String text, Workspace.Options opts, Path out) throws Exception {
        ProposalSheet[] sheet = new ProposalSheet[1];
        SwingUtilities.invokeAndWait(() -> sheet[0] = ctx.commandBar().interpret(text, Origin.NL_TYPED));
        waitUntil(() -> sheet[0].proposal() != null, 60);
        Thread.sleep(400);
        var p = sheet[0].proposal();
        System.out.println("[fidelis] instruction '" + text + "' -> status " + (p == null ? "none" : p.status())
                + ", " + (p == null || p.requests() == null ? 0 : p.requests().size()) + " step(s), confirm enabled=" + sheet[0].confirmEnabled());
        if (p != null && p.requests() != null) {
            for (int i = 0; i < p.requests().size(); i++) {
                var r = p.requests().get(i);
                System.out.println("[fidelis]   step " + (i + 1) + ": " + (r.commandId() != null ? r.commandId() : r.operation())
                        + " params=" + r.params()
                        + (p.macroPreview() != null && i < p.macroPreview().size() ? " macro=" + p.macroPreview().get(i).replace('\n', ' ') : ""));
            }
            if (p.questions() != null && !p.questions().isEmpty()) System.out.println("[fidelis]   questions: " + p.questions());
        }
        int histBefore = w.engine().history().size();
        if (out != null) shoot(w, out);
        if (!opts.confirm()) return;
        var dec = sheet[0].decision();
        System.out.println("[fidelis] confirmation policy: required=" + dec.required() + " explicit=" + dec.explicit() + " reasons=" + dec.reasons());
        if (dec.explicit()) SwingUtilities.invokeAndWait(sheet[0]::acknowledgeNow);
        if (!sheet[0].confirmEnabled()) throw new IllegalStateException("proposal cannot be confirmed");
        SwingUtilities.invokeAndWait(sheet[0]::confirmNow);
        waitUntil(() -> sheet[0].outcome != null, 10);
        Boolean ok = sheet[0].outcome.get(180, java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("[fidelis] confirmed proposal: all steps completed=" + ok);
        Thread.sleep(1200);
        var hist = w.engine().history();
        for (int i = histBefore; i < hist.size(); i++) {
            var h = hist.get(i);
            System.out.println("[fidelis]   history: " + h.origin() + " " + h.commandId() + " " + h.params() + " -> " + h.outcome() + " macro=" + h.macroLines());
        }
        reportNumbers(w);
        SwingUtilities.invokeAndWait(() -> {
            w.docks().showHistory();
            w.docks().showResults();
            w.docks().applyDefaultDividers();
            w.frame().toFront();
        });
        Thread.sleep(1200);
        if (out == null) return;
        String name = out.getFileName().toString();
        String exec = name.contains("proposal") ? name.replace("proposal", "executed") : name.replaceFirst("(\\.png)?$", "-executed.png");
        shoot(w, out.resolveSibling(exec));
    }

    // ---- quit ---------------------------------------------------------------------------------

    /** Close the window like a user: WINDOW_CLOSING, answer the discard confirmation, observe the exit. */
    static void closeWindowFlow(Workspace w) throws Exception {
        long t0 = System.nanoTime();
        System.out.println("[fidelis] closing the window (WINDOW_CLOSING); unsaved documents="
                + w.engine().documents().stream().filter(d -> d.changed()).count());
        SwingUtilities.invokeAndWait(() -> w.frame().dispatchEvent(new java.awt.event.WindowEvent(w.frame(), java.awt.event.WindowEvent.WINDOW_CLOSING)));
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(100);
            for (java.awt.Window win : java.awt.Window.getWindows()) {
                if (win instanceof javax.swing.JDialog d && d.isShowing() && d.isModal()) {
                    System.out.println("[fidelis] discard confirmation shown after " + (System.nanoTime() - t0) / 1_000_000 + " ms: '"
                            + d.getTitle() + "'; answering OK");
                    SwingUtilities.invokeAndWait(() -> {
                        javax.swing.JOptionPane op = findOptionPane(d.getContentPane());
                        if (op != null) op.setValue(javax.swing.JOptionPane.OK_OPTION);
                    });
                    Thread.sleep(30_000);
                    System.out.println("[fidelis] STILL ALIVE 30 s after the discard confirmation (quit hang)");
                    return;
                }
            }
            if (w.engine().state() == EngineState.QUITTING) {
                System.out.println("[fidelis] engine is quitting without a confirmation after " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                Thread.sleep(30_000);
                System.out.println("[fidelis] STILL ALIVE 30 s after quit");
                return;
            }
        }
        System.out.println("[fidelis] neither a confirmation nor an exit within 15 s");
    }

    private static javax.swing.JOptionPane findOptionPane(java.awt.Container c) {
        if (c instanceof javax.swing.JOptionPane op) return op;
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof java.awt.Container cc) {
                javax.swing.JOptionPane r = findOptionPane(cc);
                if (r != null) return r;
            }
        }
        return null;
    }
}
