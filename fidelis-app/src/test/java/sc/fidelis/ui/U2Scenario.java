package sc.fidelis.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.process.ImageProcessor;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.ui.dev.StubEngine;

/**
 * Scenarios for the threshold, display-range, comparison and stack controls (U2), run against real
 * IJ1 images through the development stub engine. Kept out of the JUnit class so that no ij.* class
 * loads before the ImageJ1 patcher has run.
 */
final class U2Scenario {
    private static Workspace ws;

    private U2Scenario() {}

    static void start() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            ws = Workspace.create(new StubEngine(null), null, new java.awt.Dimension(1300, 850));
            ws.frame().setVisible(true);
        });
        waitFor("engine ready", () -> ws.engine().state() == EngineState.READY, 30);
    }

    static void stop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (ws != null) ws.frame().dispose();
        });
    }

    private static void waitFor(String what, BooleanSupplier c, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (c.getAsBoolean()) return;
            Thread.sleep(40);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    private static Map<String, Object> newImage(String title, String type, String fill, int c, int z) {
        Map<String, Object> ni = new LinkedHashMap<>();
        ni.put("title", title);
        ni.put("type", type);
        ni.put("fill", fill);
        ni.put("width", 128);
        ni.put("height", 128);
        ni.put("channels", c);
        ni.put("slices", z);
        ni.put("frames", 1);
        return ni;
    }

    private static DocumentView open(Path fixture) throws Exception {
        int before = ws.documents().allViews().size();
        ws.engine().open(fixture).get(30, TimeUnit.SECONDS);
        waitFor("view of " + fixture.getFileName(), () -> ws.documents().allViews().size() > before
                && ws.documents().focusedView() != null && ws.documents().focusedView().imagePlus().getTitle().equals(fixture.getFileName().toString()), 20);
        DocumentView v = ws.documents().focusedView();
        waitFor("canvas laid out", () -> v.canvas().getWidth() > 0 && v.canvas().isShowing(), 20);
        return v;
    }

    private static DocumentView create(String title, String type, String fill, int c, int z) throws Exception {
        int before = ws.documents().allViews().size();
        ws.engine().submit(ActionRequest.session(Origin.MENU, Operations.NEW_IMAGE, newImage(title, type, fill, c, z), null))
                .get(30, TimeUnit.SECONDS);
        waitFor("view of " + title, () -> ws.documents().allViews().size() > before
                && ws.documents().focusedView() != null && title.equals(ws.documents().focusedView().imagePlus().getTitle()), 20);
        DocumentView v = ws.documents().focusedView();
        waitFor("canvas laid out", () -> v.canvas().getWidth() > 0 && v.canvas().isShowing(), 20);
        return v;
    }

    private static long pixelHash(ImagePlus imp) {
        long h = 1;
        for (int i = 1; i <= imp.getStackSize(); i++) {
            Object p = imp.getStack().getPixels(i);
            h = 31 * h + (p instanceof byte[] b ? Arrays.hashCode(b) : p instanceof short[] s ? Arrays.hashCode(s)
                    : p instanceof float[] f ? Arrays.hashCode(f) : p instanceof int[] n ? Arrays.hashCode(n) : 0);
        }
        return h;
    }

    private static int paintHash(ImageCanvas c) throws Exception {
        BufferedImage bi = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        edt(() -> {
            var g = bi.createGraphics();
            c.paint(g);
            g.dispose();
        });
        return Arrays.hashCode(bi.getRGB(0, 0, bi.getWidth(), bi.getHeight(), null, 0, bi.getWidth()));
    }

    private static boolean historyHas(String label) {
        for (HistoryRecord r : ws.engine().history()) if (label.equals(r.label())) return true;
        return false;
    }

    // ---- threshold ------------------------------------------------------------------------------

    static void thresholdPreviewLeavesPixelsUnchanged() throws Exception {
        Path fixture = Path.of(System.getProperty("fidelis.repo.dir", ".")).resolve("fixtures").resolve("two-squares.png");
        DocumentView v = open(fixture);
        ImagePlus imp = v.imagePlus();
        long before = pixelHash(imp);
        assertEquals(ImageProcessor.NO_THRESHOLD, imp.getProcessor().getMinThreshold(), 0.0);

        edt(() -> ws.threshold().autoThreshold("Otsu", true));
        waitFor("threshold set", () -> imp.getProcessor().getMinThreshold() != ImageProcessor.NO_THRESHOLD, 20);
        waitFor("SET_AUTO_THRESHOLD in history", () -> historyHas("SET_AUTO_THRESHOLD"), 20);
        assertEquals(before, pixelHash(imp), "SET_AUTO_THRESHOLD must not change pixels");
        waitFor("panel fields", () -> !ws.threshold().lowerText().isEmpty() && !ws.threshold().upperText().isEmpty(), 10);
        assertEquals(HistogramStrip.fmt(imp.getProcessor().getMinThreshold()), ws.threshold().lowerText());

        ws.engine().submit(ActionRequest.direct(Origin.PANEL, Operations.RESET_THRESHOLD, Map.of(), ws.context().target())).get(20, TimeUnit.SECONDS);
        assertEquals(ImageProcessor.NO_THRESHOLD, imp.getProcessor().getMinThreshold(), 0.0);
        assertEquals(before, pixelHash(imp), "RESET_THRESHOLD must not change pixels");
    }

    // ---- display range --------------------------------------------------------------------------

    static void displayRangeIsDisplayOnly() throws Exception {
        DocumentView v = create("ramp16", "16-bit", "Ramp", 1, 1);
        ImagePlus imp = v.imagePlus();
        long before = pixelHash(imp);
        int paintBefore = paintHash(v.canvas());
        edt(() -> ws.display().setRange(10000, 30000));
        waitFor("display range", () -> imp.getDisplayRangeMin() == 10000 && imp.getDisplayRangeMax() == 30000, 20);
        assertEquals(before, pixelHash(imp), "SET_DISPLAY_RANGE must not change pixels");
        waitFor("repaint", () -> {
            try {
                return paintHash(v.canvas()) != paintBefore;
            } catch (Exception e) {
                return false;
            }
        }, 10);
    }

    // ---- compare --------------------------------------------------------------------------------

    static void compareShowsTwoRealCanvases() throws Exception {
        Path fixture = Path.of(System.getProperty("fidelis.repo.dir", ".")).resolve("fixtures").resolve("two-squares.png");
        DocumentView v = open(fixture);
        ImagePlus imp = v.imagePlus();
        long before = pixelHash(imp);
        assertFalse(v.hasBefore());
        Map<String, Object> blur = new LinkedHashMap<>();
        blur.put("sigma", 2.0);
        blur.put("scaled", false);
        blur.put("stack", false);
        @SuppressWarnings("unchecked")
        java.util.concurrent.CompletableFuture<sc.fidelis.engine.api.ActionResult>[] fut = new java.util.concurrent.CompletableFuture[1];
        edt(() -> fut[0] = ws.context().submit(ActionRequest.command(Origin.MENU, "ij1:Process>Filters>Gaussian Blur...", blur, ws.context().target()),
                "Gaussian Blur..."));
        try {
            waitFor("before snapshot", v::hasBefore, 30);
        } catch (AssertionError e) {
            String result = !fut[0].isDone() ? "blur still running" : fut[0].isCompletedExceptionally() ? "blur failed: " + fut[0].handle((r, t) -> String.valueOf(t)).join()
                    : "blur result: state=" + fut[0].join().state() + " digest=" + fut[0].join().digest();
            throw new AssertionError(e.getMessage() + " (" + result + "; " + ws.documents().snapshotDiagnostics() + ")", e);
        }
        assertNotEquals(before, pixelHash(imp), "the blur must have changed pixels");
        assertEquals(before, pixelHash(v.beforeImagePlus()), "the snapshot is the real pre-action pixel copy");

        edt(() -> v.setCompare(true));
        waitFor("two canvases", () -> v.visibleCanvases().size() == 2 && v.visibleCanvases().stream().allMatch(c -> c.isShowing() && c.getWidth() > 0), 20);
        assertNotNull(v.compareCanvas());
        // linked zoom: the before canvas follows the document canvas
        edt(() -> v.zoomStep(true, null));
        waitFor("linked magnification", () -> v.compareCanvas().getMagnification() == v.canvas().getMagnification(), 10);
        assertNotEquals(paintHash(v.canvas()), paintHash(v.compareCanvas()), "before and after must look different");
        edt(() -> v.setCompare(false));
        assertEquals(1, v.visibleCanvases().size());
    }

    // ---- stacks and channels --------------------------------------------------------------------

    static void slidersDriveSetPositionAndRepaint() throws Exception {
        DocumentView v = create("stack10", "8-bit", "Random", 1, 10);
        ImagePlus imp = v.imagePlus();
        assertEquals(1, imp.getSlice());
        int p1 = paintHash(v.canvas());
        edt(() -> v.moveSlider('z', 2));
        waitFor("slice 2", () -> imp.getSlice() == 2, 20);
        waitFor("subtitle shows position", () -> v.subtitleText().startsWith("2/10"), 10);
        waitFor("canvas repainted", () -> {
            try {
                return paintHash(v.canvas()) != p1;
            } catch (Exception e) {
                return false;
            }
        }, 10);
        assertTrue(ws.engine().history().stream().anyMatch(r -> Operations.SET_POSITION.equals(r.label())));

        DocumentView c = create("comp3", "16-bit composite-mode", "Ramp", 3, 1);
        ImagePlus ci = c.imagePlus();
        assertTrue(ci.isComposite());
        edt(() -> c.moveSlider('c', 2));
        waitFor("channel 2", () -> ci.getChannel() == 2, 20);
        waitFor("subtitle shows c:2/3", () -> c.subtitleText().contains("c:2/3"), 10);
    }
}
