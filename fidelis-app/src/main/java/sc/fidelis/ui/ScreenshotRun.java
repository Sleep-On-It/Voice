package sc.fidelis.ui;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.RoiGeometry;
import sc.fidelis.engine.api.TargetScope;

/**
 * The {@code --screenshot <path>} debug mode: three seconds after start it opens the fixture and a
 * synthetic 512 x 512 16-bit image, does a little real work so every panel has content, applies the
 * default layout, writes a PNG of the whole frame and exits. {@code Container.printAll} omits
 * heavyweight components (EXP-01), so each visible hosted canvas is painted in at its location.
 */
final class ScreenshotRun {
    private ScreenshotRun() {}

    static void schedule(Workspace w, Workspace.Options opts, Runnable exit) {
        Path out = opts.screenshot();
        Timer t = new Timer(3000, e -> new Thread(() -> run(w, opts, exit), "fidelis-screenshot").start());
        t.setRepeats(false);
        t.start();
    }

    private static void run(Workspace w, Workspace.Options opts, Runnable exit) {
        Path out = opts.screenshot();
        try {
            Path repo = Path.of(System.getProperty("fidelis.repo.dir", "."));
            var engine = w.engine();
            UiContext ctx = w.context();
            long deadline = System.currentTimeMillis() + 20_000;
            while (engine.state() != sc.fidelis.engine.api.Enums.EngineState.READY && System.currentTimeMillis() < deadline)
                Thread.sleep(100);
            if (opts.scenario() != null) {
                scenario(w, ctx, opts.scenario(), repo);
                Thread.sleep(300);
                SwingUtilities.invokeAndWait(() -> {
                    w.docks().applyDefaultDividers();
                    w.frame().toFront();
                });
                Thread.sleep(1500);
                if (opts.instruction() != null) ScreenshotJ12.instructionFlow(w, ctx, opts, out);
                else shoot(w, out);
                SwingUtilities.invokeAndWait(() -> System.out.println("[fidelis] frame " + w.frame().getWidth() + "x" + w.frame().getHeight()
                        + "; layout " + w.docks().describe()
                        + (w.documents().focusedView() == null ? "" : "; canvas " + w.documents().focusedView().canvas().getWidth() + "x"
                        + w.documents().focusedView().canvas().getHeight() + " at " + w.documents().focusedView().zoomLabelText())
                        + "; results columns visible " + w.results().visibleColumnCount()));
                if (opts.closeWindow()) ScreenshotJ12.closeWindowFlow(w); // a working quit never returns: the engine halts the JVM
                return;
            }
            engine.open(repo.resolve("fixtures").resolve("two-squares.png")).get();
            Map<String, Object> ni = new LinkedHashMap<>();
            ni.put("title", "synthetic-16bit");
            ni.put("type", "16-bit");
            ni.put("fill", "Ramp");
            ni.put("width", 512);
            ni.put("height", 512);
            ni.put("slices", 1);
            engine.submit(ActionRequest.session(Origin.MENU, Operations.NEW_IMAGE, ni, null)).get();
            DocumentId syn = engine.focused().orElseThrow();
            TargetScope t = TargetScope.of(syn);
            Map<String, Object> blur = new LinkedHashMap<>();
            blur.put("sigma", 3.0);
            blur.put("scaled", false);
            blur.put("stack", false);
            engine.submit(ActionRequest.command(Origin.MENU, "ij1:Process>Filters>Gaussian Blur...", blur, t)).get();
            RoiGeometry rect = new RoiGeometry("RECTANGLE", java.util.List.of(), java.util.List.of(),
                    new double[] {120, 140, 240, 150}, "region-1", null, null);
            Map<String, Object> roi = new LinkedHashMap<>();
            roi.put("roi", rect);
            engine.submit(ActionRequest.direct(Origin.CANVAS, Operations.SET_ROI, roi, t)).get();
            engine.submit(ActionRequest.direct(Origin.PANEL, Operations.ROI_LIST_ADD, Map.of(), t)).get();
            engine.submit(ActionRequest.command(Origin.MENU, "ij1:Analyze>Measure", Map.of(), t)).get();
            SwingUtilities.invokeAndWait(() -> {
                w.docks().showHistory();
                ctx.runCommand("ij1:Process>Filters>Median...", Origin.MENU);
            });
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> {
                w.docks().applyDefaultDividers();
                w.frame().toFront();
            });
            SwingUtilities.invokeAndWait(() -> reportPopupRealisation(w));
            if (opts.instruction() != null) {
                SwingUtilities.invokeAndWait(() -> ctx.commandBar().interpret(opts.instruction(), Origin.NL_TYPED));
                Thread.sleep(1500);
            }
            if (opts.search() != null) {
                SwingUtilities.invokeAndWait(() -> {
                    ctx.commandBar().focusField();
                    ctx.commandBar().setText(opts.search());
                });
                Thread.sleep(800);
                SwingUtilities.invokeAndWait(() -> System.out.println("[fidelis] search '" + opts.search() + "': popup visible="
                        + ctx.commandBar().popupVisible() + " rows=" + ctx.commandBar().visibleRowLabels()));
            }
            Thread.sleep(1800);
            BufferedImage[] img = new BufferedImage[1];
            SwingUtilities.invokeAndWait(() -> img[0] = capture(w));
            Files.createDirectories(out.toAbsolutePath().getParent());
            ImageIO.write(img[0], "png", out.toFile());
            System.out.println("[fidelis] screenshot written: " + out.toAbsolutePath());
            try {
                Path robotOut = Path.of(out.toString().replaceFirst("\\.png$", "") + "-robot.png");
                BufferedImage shot = robotCapture(w);
                if (isBlack(shot)) {
                    System.out.println("[fidelis] robot capture is all black (locked desktop or no interactive session); not written");
                } else {
                    ImageIO.write(shot, "png", robotOut.toFile());
                    System.out.println("[fidelis] robot capture written: " + robotOut.toAbsolutePath());
                }
            } catch (Exception | Error ex) {
                System.out.println("[fidelis] robot capture unavailable: " + ex);
            }
        } catch (Throwable ex) {
            System.err.println("[fidelis] screenshot failed: " + ex);
            ex.printStackTrace();
        } finally {
            exit.run();
        }
    }

    static void shoot(Workspace w, Path out) throws Exception {
        BufferedImage[] one = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> one[0] = capture(w));
        Files.createDirectories(out.toAbsolutePath().getParent());
        ImageIO.write(one[0], "png", out.toFile());
        System.out.println("[fidelis] screenshot written: " + out.toAbsolutePath());
    }

    /** The whole frame, with every showing hosted canvas composited in (EXP-01 section 5.2). */
    static BufferedImage capture(Workspace w) {
        JRootPane root = w.frame().getRootPane();
        BufferedImage img = new BufferedImage(Math.max(1, root.getWidth()), Math.max(1, root.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        // match the on-screen text metrics (the default offscreen hints are narrower and clip right-aligned labels)
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        root.printAll(g);
        for (DocumentView v : w.documents().allViews()) {
            if (!v.isShowing()) continue;
            for (ij.gui.ImageCanvas c : v.visibleCanvases()) {
                if (c.getWidth() <= 0 || !c.isShowing()) continue;
                Point p = SwingUtilities.convertPoint(c, 0, 0, root);
                Graphics2D cg = (Graphics2D) g.create(p.x, p.y, c.getWidth(), c.getHeight());
                c.paint(cg);
                cg.dispose();
            }
        }
        // heavyweight popups (command results, menus) are separate native windows: paint them in place
        java.awt.Point rootOrigin = root.getLocationOnScreen();
        for (java.awt.Window pw : java.awt.Window.getWindows()) {
            if (!pw.isShowing() || pw == w.frame() || !(pw instanceof javax.swing.JWindow)) continue;
            java.awt.Point po = pw.getLocationOnScreen();
            Graphics2D pg = (Graphics2D) g.create(po.x - rootOrigin.x, po.y - rootOrigin.y, pw.getWidth(), pw.getHeight());
            pw.printAll(pg);
            pg.dispose();
        }
        g.dispose();
        return img;
    }

    private static Map<String, Object> newImage(String title, String type, String fill, int w, int h, int c, int z, int t) {
        Map<String, Object> ni = new LinkedHashMap<>();
        ni.put("title", title);
        ni.put("type", type);
        ni.put("fill", fill);
        ni.put("width", w);
        ni.put("height", h);
        ni.put("channels", c);
        ni.put("slices", z);
        ni.put("frames", t);
        return ni;
    }

    static void waitUntil(java.util.function.BooleanSupplier c, int seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (!c.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(50);
    }

    /** U2 scenarios: real work through the engine so every panel shows genuine state. */
    private static void scenario(Workspace w, UiContext ctx, String name, Path repo) throws Exception {
        var engine = w.engine();
        if (name.startsWith("j12")) {
            ScreenshotJ12.j12(w, ctx, name.equals("j12-editor") ? "j12" : name, repo);
            if (name.equals("j12-editor")) { // the generated macro in the editor, stopped at the capability confirmation
                SwingUtilities.invokeAndWait(() -> {
                    w.editor().setText(engine.exportMacro());
                    w.docks().showEditor();
                    w.editor().run();
                });
                waitUntil(() -> w.editor().isAwaitingConfirmation(), 30);
                System.out.println("[fidelis] macro editor: " + w.editor().statusText() + "; capabilities '" + w.editor().capabilitiesText()
                        + "'; " + w.editor().text().lines().count() + " lines");
            }
            return;
        }
        engine.open(repo.resolve("fixtures").resolve("two-squares.png")).get();
        switch (name) {
            case "threshold" -> {
                SwingUtilities.invokeAndWait(() -> {
                    w.docks().showThreshold();
                    w.threshold().autoThreshold("Otsu", true);
                });
                Thread.sleep(800);
            }
            case "bc" -> {
                engine.submit(ActionRequest.session(Origin.MENU, Operations.NEW_IMAGE,
                        newImage("synthetic-16bit", "16-bit", "Ramp", 512, 512, 1, 1, 1), null)).get();
                SwingUtilities.invokeAndWait(() -> {
                    w.docks().showDisplay();
                    w.display().setRange(12000, 41000);
                });
                Thread.sleep(800);
            }
            case "compare" -> {
                Map<String, Object> blur = new LinkedHashMap<>();
                blur.put("sigma", 2.0);
                blur.put("scaled", false);
                blur.put("stack", false);
                SwingUtilities.invokeAndWait(() -> ctx.submit(ActionRequest.command(Origin.MENU, "ij1:Process>Filters>Gaussian Blur...",
                        blur, ctx.target()), "Gaussian Blur..."));
                DocumentView v = w.documents().focusedView();
                waitUntil(() -> v.hasBefore(), 20);
                SwingUtilities.invokeAndWait(() -> v.setCompare(true));
                Thread.sleep(500);
            }
            case "stack" -> {
                // The real engine's NEW_IMAGE has no channels/frames and no composite type, so a genuine hyperstack is opened
                // from the cached fixture (FX-005 hela-cells.tif); without it a plain 10-slice stack is created.
                Path hela = repo.resolve("fixtures").resolve("cache").resolve("FX-005").resolve("extracted").resolve("hela-cells.tif");
                if (Files.isRegularFile(hela)) {
                    engine.open(hela).get();
                } else {
                    var r = engine.submit(ActionRequest.session(Origin.MENU, Operations.NEW_IMAGE,
                            newImage("stack-10", "8-bit", "Noise", 256, 256, 1, 10, 1), null)).get();
                    System.out.println("[fidelis] NEW_IMAGE stack -> " + r.state() + (r.error() == null ? "" : " " + r.error().message()));
                }
                var info = engine.document(engine.focused().orElseThrow()).orElseThrow();
                System.out.println("[fidelis] stack scenario document " + info.title() + " c=" + info.nChannels() + " z=" + info.nSlices()
                        + " t=" + info.nFrames() + " " + info.bitDepth() + "-bit");
                if (info.nChannels() > 1) {
                    for (int c = 1; c <= info.nChannels(); c++) {
                        Map<String, Object> dr = new LinkedHashMap<>();
                        dr.put("min", 0.0);
                        dr.put("max", 30000.0 / c);
                        dr.put("channel", c);
                        var r = engine.submit(ActionRequest.direct(Origin.PANEL, Operations.SET_DISPLAY_RANGE, dr, TargetScope.of(info.id()))).get();
                        System.out.println("[fidelis] SET_DISPLAY_RANGE channel " + c + " -> " + r.state());
                    }
                }
                waitUntil(() -> w.documents().focusedView() != null, 10);
                DocumentView v = w.documents().focusedView();
                SwingUtilities.invokeAndWait(() -> {
                    if (info.nChannels() > 1) v.moveSlider('c', 2);
                    if (info.nSlices() > 1) v.moveSlider('z', Math.min(3, info.nSlices()));
                    w.docks().showDisplay();
                });
                Thread.sleep(1000);
                System.out.println("[fidelis] stack subtitle: " + v.subtitleText());
            }
            default -> throw new IllegalArgumentException("unknown scenario " + name);
        }
    }

    /** Structural z-order check: popups over the heavyweight canvas must be separate native windows. */
    private static void reportPopupRealisation(Workspace w) {
        DocumentView v = w.documents().focusedView();
        if (v == null) return;
        javax.swing.JPopupMenu pm = new javax.swing.JPopupMenu();
        pm.add(new javax.swing.JMenuItem("probe item"));
        pm.show(v.canvas(), 30, 30);
        java.awt.Window win = SwingUtilities.getWindowAncestor(pm);
        System.out.println("[fidelis] popup over canvas realised as: " + (win == null ? "lightweight (inside the frame)" : win.getClass().getName())
                + "; showing=" + pm.isShowing());
        pm.setVisible(false);
    }

    private static boolean isBlack(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y += 7)
            for (int x = 0; x < img.getWidth(); x += 7)
                if ((img.getRGB(x, y) & 0xffffff) != 0) return false;
        return true;
    }

    static BufferedImage robotCapture(Workspace w) throws Exception {
        Rectangle r = new Rectangle(w.frame().getLocationOnScreen(), w.frame().getSize());
        Robot robot = new Robot();
        return robot.createScreenCapture(r);
    }
}
