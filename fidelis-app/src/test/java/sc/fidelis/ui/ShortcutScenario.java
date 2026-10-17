package sc.fidelis.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import ij.IJ;
import ij.ImagePlus;
import ij.Menus;
import ij.gui.ImageCanvas;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.Engine;
import sc.fidelis.engine.api.Enums.EngineState;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.HistoryRecord;
import sc.fidelis.engine.api.TargetScope;
import sc.fidelis.testkit.IjState;
import sc.fidelis.testkit.ProductEngine;

/**
 * Keyboard-shortcut parity (issue #16, DEC-031): the shortcuts a Fiji user knows do the same thing on a
 * hosted canvas. The expected binding of every Ctrl shortcut is read from Fiji itself, twice: from
 * {@code ij.Menus.getShortcuts()} (IJ_Props) and from the live IJ1 menu bar that the workspace mirrors;
 * nothing is hard-coded or re-mapped here. Each key stroke is dispatched to the focused canvas through the
 * normal AWT pipeline and the effect is asserted on the engine (history, Results, ROIs, documents) or on
 * the viewport. The table printed as {@code [shortcut] ...} lines is the parity table for the PR.
 */
final class ShortcutScenario {
    private static Workspace ws;
    private static Engine engine;
    private static boolean blackBefore;
    private static int measurementsBefore;
    private static int precisionBefore;
    static final List<String> table = new ArrayList<>();

    private ShortcutScenario() {}

    static void start() throws Exception {
        engine = ProductEngine.get();
        blackBefore = ij.Prefs.blackBackground;
        measurementsBefore = ij.plugin.filter.Analyzer.getMeasurements();
        precisionBefore = ij.plugin.filter.Analyzer.getPrecision();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            ws = Workspace.create(engine, null, new Dimension(1400, 880));
            ws.frame().setVisible(true);
        });
        waitFor("engine ready", () -> engine.state() == EngineState.READY, 60);
        edt(() -> {
            ws.frame().validate();
            ws.docks().applyDefaultDividers();
        });
    }

    /** Leave the shared JVM as found (same contract as {@link LayoutScenario#stop()}). */
    static void stop() throws Exception {
        edt(() -> {
            if (ws != null) ws.frame().dispose();
        });
        var open = new ArrayList<>(engine.documents());
        if (!open.isEmpty())
            engine.submit(ActionRequest.command(Origin.MENU, "ij1:Analyze>Clear Results", Map.of(), TargetScope.of(open.get(0).id()))).get(30, TimeUnit.SECONDS);
        for (var d : open) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("discard", true);
            engine.submit(ActionRequest.session(Origin.MENU, Operations.CLOSE, p, TargetScope.of(d.id()))).get(30, TimeUnit.SECONDS);
        }
        IjState.reset();
        ij.Prefs.blackBackground = blackBefore;
        ij.plugin.filter.Analyzer.setMeasurements(measurementsBefore);
        ij.plugin.filter.Analyzer.setPrecision(precisionBefore);
        System.out.println("[shortcut] after cleanup: documents=" + engine.documents().size() + " results=" + engine.results().size());
    }

    // ---- helpers ------------------------------------------------------------------------------------

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

    private static long pixelHash(ImagePlus imp) {
        long h = 1;
        for (int i = 1; i <= imp.getStackSize(); i++) {
            Object p = imp.getStack().getPixels(i);
            h = 31 * h + (p instanceof byte[] b ? Arrays.hashCode(b) : p instanceof short[] s ? Arrays.hashCode(s)
                    : p instanceof float[] f ? Arrays.hashCode(f) : p instanceof int[] n ? Arrays.hashCode(n) : 0);
        }
        return h;
    }

    private static boolean focused;

    /**
     * Deliver a key event the way the toolkit does: posted to the event queue when the canvas owns the keyboard
     * focus (then Swing sees it as the current event and menu accelerators carry its modifiers), otherwise
     * dispatched to the canvas directly (same listeners and KeyboardFocusManager path, no focus requirement).
     */
    private static void key(Component c, int id, int code, char ch, int mods) throws Exception {
        KeyEvent ev = new KeyEvent(c, id, System.currentTimeMillis(), mods, code, ch);
        if (focused) java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(ev);
        else edt(() -> c.dispatchEvent(ev));
        edt(() -> { }); // runs after the key event has been dispatched
    }

    private static void press(Component c, int code, int mods) throws Exception {
        key(c, KeyEvent.KEY_PRESSED, code, KeyEvent.CHAR_UNDEFINED, mods);
        key(c, KeyEvent.KEY_RELEASED, code, KeyEvent.CHAR_UNDEFINED, mods);
    }

    private static void fire(ImageCanvas c, int id, int x, int y, int mods) throws Exception {
        int ex = id == MouseEvent.MOUSE_RELEASED || id == MouseEvent.MOUSE_MOVED ? mods & ~InputEvent.BUTTON1_DOWN_MASK : mods | InputEvent.BUTTON1_DOWN_MASK;
        MouseEvent ev = new MouseEvent(c, id, System.currentTimeMillis(), ex, x, y, x, y, 1, false,
                id == MouseEvent.MOUSE_MOVED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1);
        edt(() -> c.dispatchEvent(ev));
    }

    private static void drag(ImageCanvas c, int x0, int y0, int x1, int y1) throws Exception {
        fire(c, MouseEvent.MOUSE_MOVED, x0, y0, 0);
        fire(c, MouseEvent.MOUSE_PRESSED, x0, y0, 0);
        for (int i = 1; i <= 6; i++) fire(c, MouseEvent.MOUSE_DRAGGED, x0 + (x1 - x0) * i / 6, y0 + (y1 - y0) * i / 6, 0);
        fire(c, MouseEvent.MOUSE_RELEASED, x1, y1, 0);
    }

    private static int ctrl(boolean shift) {
        return InputEvent.CTRL_DOWN_MASK | (shift ? InputEvent.SHIFT_DOWN_MASK : 0);
    }

    private static String keysText(int code, boolean shift) {
        return "Ctrl+" + (shift ? "Shift+" : "") + KeyEvent.getKeyText(code);
    }

    /**
     * Fiji's own binding for a Ctrl[+Shift] shortcut, from IJ_Props through {@code ij.Menus.getShortcuts()}
     * (shifted keys are stored as key code + 200 there) and from the menu bar the workspace mirrors. The two must
     * agree, and the workspace must have registered the same command for the key stroke.
     */
    private static String fijiBinding(int code, boolean shift) {
        @SuppressWarnings("unchecked")
        Map<Integer, String> shortcuts = (Map<Integer, String>) Menus.getShortcuts();
        String fromProps = shortcuts.get(shift ? code + 200 : code);
        assertNotNull(fromProps, keysText(code, shift) + " is a Fiji shortcut (IJ_Props); table: " + new java.util.TreeMap<>(shortcuts));
        KeyStroke ks = KeyStroke.getKeyStroke(code, ctrl(shift));
        String fromMenu = null;
        for (MenuModel.Node n : MenuModel.leaves(MenuModel.read(Menus.getMenuBar())))
            if (ks.equals(n.accelerator())) fromMenu = n.commandId();
        assertNotNull(fromMenu, keysText(code, shift) + " appears as an accelerator in Fiji's menu bar");
        assertEquals(fromProps, fromMenu.substring(fromMenu.lastIndexOf('>') + 1), "IJ_Props and the menu bar agree on " + keysText(code, shift));
        assertEquals(fromMenu, ws.context().commandForShortcut(ks), "the workspace registered Fiji's accelerator " + keysText(code, shift));
        return fromMenu;
    }

    private static HistoryRecord lastRecord(int sizeBefore, String commandId) throws Exception {
        waitFor("history record for " + commandId, () -> engine.history().size() > sizeBefore
                && engine.history().stream().skip(sizeBefore).anyMatch(r -> commandId.equals(r.commandId())), 20);
        return engine.history().stream().skip(sizeBefore).filter(r -> commandId.equals(r.commandId())).reduce((a, b) -> b).orElseThrow();
    }

    private static void row(String keys, String fiji, String fidelis, String verified) {
        String line = "| `" + keys + "` | " + fiji + " | " + fidelis + " | " + verified + " |";
        table.add(line);
        System.out.println("[shortcut] " + line);
    }

    // ---- the scenario ---------------------------------------------------------------------------------

    static void fijiShortcutsDoTheSameOnAHostedCanvas() throws Exception {
        Path repo = Path.of(System.getProperty("fidelis.repo.dir", "."));
        Path blobs = repo.resolve("fixtures").resolve("cache").resolve("FX-003").resolve("blobs.gif");
        assertTrue(Files.isRegularFile(blobs), "fixture not cached (run verification/fetch_fixtures.py): " + blobs);
        int viewsBefore = ws.documents().allViews().size();
        engine.open(blobs).get(30, TimeUnit.SECONDS);
        waitFor("blobs view", () -> ws.documents().allViews().size() > viewsBefore && ws.documents().focusedView() != null
                && "blobs.gif".equals(ws.documents().focusedView().imagePlus().getTitle()), 20);
        DocumentView v = ws.documents().focusedView();
        ImageCanvas canvas = v.canvas();
        ImagePlus imp = v.imagePlus();
        DocumentId id = v.id();
        waitFor("canvas laid out", () -> canvas.isShowing() && canvas.getWidth() > 0, 20);
        edt(() -> {
            ws.frame().toFront();
            canvas.requestFocusInWindow();
        });
        try {
            waitFor("canvas owns the keyboard focus", () -> java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == canvas, 5);
            focused = true;
        } catch (AssertionError e) {
            focused = false; // another window is active on this desktop: dispatch directly, the origin is then not asserted
        }
        System.out.println("[shortcut] canvas focused: " + focused + " (focus owner " + java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() + ")");

        // Ctrl+M: Measure the whole image -> one Results row, recorded against this document
        String measure = fijiBinding(KeyEvent.VK_M, false);
        int rows = engine.results().size();
        int hist = engine.history().size();
        press(canvas, KeyEvent.VK_M, ctrl(false));
        HistoryRecord rec = lastRecord(hist, measure);
        waitFor("Results row from Measure", () -> engine.results().size() == rows + 1, 20);
        assertEquals(id, rec.target() == null ? null : rec.target().document(), "Measure ran on the focused document");
        if (focused) assertEquals(Origin.SHORTCUT, rec.origin(), "an accelerator activation is recorded as a shortcut, not a menu click");
        row(keysText(KeyEvent.VK_M, false), "Measure", "`" + measure + "` via the engine (origin " + rec.origin() + ")",
                "one Results row added; history target = focused document");

        // Ctrl+T: Add to Manager, after a native rectangle selection on the canvas
        String addToManager = fijiBinding(KeyEvent.VK_T, false);
        drag(canvas, 10, 10, 70, 60);
        waitFor("rectangle selection", () -> engine.activeRoi(id).isPresent(), 20);
        int rois = engine.rois(id).size();
        hist = engine.history().size();
        press(canvas, KeyEvent.VK_T, ctrl(false));
        waitFor("ROI added", () -> engine.rois(id).size() == rois + 1, 20);
        HistoryRecord addRec = engine.history().get(engine.history().size() - 1);
        row(keysText(KeyEvent.VK_T, false), "Add to Manager", "`" + addToManager + "` -> engine ROI_LIST_ADD (Routes; the ROI panel's Add) recorded as "
                + addRec.commandId() + " / " + addRec.origin(), "document ROI list " + rois + " -> " + engine.rois(id).size());

        // Ctrl+Shift+A: Select None
        String selectNone = fijiBinding(KeyEvent.VK_A, true);
        hist = engine.history().size();
        press(canvas, KeyEvent.VK_A, ctrl(true));
        lastRecord(hist, selectNone);
        waitFor("selection cleared", () -> engine.activeRoi(id).isEmpty(), 20);
        row(keysText(KeyEvent.VK_A, true), "Select None", "`" + selectNone + "` via the engine", "active selection cleared");

        // Ctrl+Shift+T: Threshold... opens the Threshold panel (Fidelis routes the IJ1 dialog to its dock, Routes)
        String threshold = fijiBinding(KeyEvent.VK_T, true);
        press(canvas, KeyEvent.VK_T, ctrl(true));
        waitFor("Threshold panel shown", () -> ws.docks().tools.getSelectedComponent() == ws.threshold() && ws.threshold().isShowing(), 20);
        row(keysText(KeyEvent.VK_T, true), "Threshold...", "`" + threshold + "` -> Threshold panel (same dock as the menu item)", "Threshold tab selected and showing");

        // Ctrl+Z: Undo a blur that was run through the UI
        String undo = fijiBinding(KeyEvent.VK_Z, false);
        long before = pixelHash(imp);
        Map<String, Object> blur = new LinkedHashMap<>();
        blur.put("sigma", 2.0);
        blur.put("scaled", false);
        blur.put("stack", false);
        @SuppressWarnings("unchecked")
        java.util.concurrent.CompletableFuture<sc.fidelis.engine.api.ActionResult>[] f = new java.util.concurrent.CompletableFuture[1];
        edt(() -> f[0] = ws.context().submit(ActionRequest.command(Origin.MENU, "ij1:Process>Filters>Gaussian Blur...", blur, TargetScope.of(id)), "Gaussian Blur..."));
        f[0].get(30, TimeUnit.SECONDS);
        assertNotEquals(before, pixelHash(imp), "the blur changed pixels");
        hist = engine.history().size();
        press(canvas, KeyEvent.VK_Z, ctrl(false));
        waitFor("pixels restored by Undo", () -> pixelHash(imp) == before, 20);
        assertTrue(engine.history().size() > hist, "Undo is recorded in the history");
        row(keysText(KeyEvent.VK_Z, false), "Undo", "`" + undo + "` -> engine UNDO (Routes)", "pixels back to the pre-blur hash");

        // Ctrl+D / Ctrl+Shift+D: Duplicate... (whichever Fiji binds) -> the Parameters form for the same command
        boolean dupShift = !"Duplicate...".equals(((Map<?, ?>) Menus.getShortcuts()).get(KeyEvent.VK_D));
        String duplicate = fijiBinding(KeyEvent.VK_D, dupShift);
        int docs = engine.documents().size();
        press(canvas, KeyEvent.VK_D, ctrl(dupShift));
        waitFor("Duplicate form or document", () -> (ws.parameters().currentSchema() != null && duplicate.equals(ws.parameters().currentSchema().commandId()))
                || engine.documents().size() == docs + 1, 20);
        boolean form = ws.parameters().currentSchema() != null && duplicate.equals(ws.parameters().currentSchema().commandId());
        row(keysText(KeyEvent.VK_D, dupShift), "Duplicate...", "`" + duplicate + "`" + (form ? " -> Parameters form (the command has parameters)" : " via the engine"),
                form ? "Parameters panel shows the Duplicate form" : "a new document appeared");

        // + / - : zoom steps on the viewport ladder (Fiji: ImageJ.keyPressed zoom in/out)
        edt(() -> canvas.requestFocusInWindow());
        double mag0 = canvas.getMagnification();
        key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_EQUALS, '=', 0);
        key(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_EQUALS, '=', 0);
        waitFor("zoom in", () -> canvas.getMagnification() > mag0, 10);
        double mag1 = canvas.getMagnification();
        key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_MINUS, '-', 0);
        key(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_MINUS, '-', 0);
        waitFor("zoom out", () -> canvas.getMagnification() < mag1, 10);
        row("+ / -", "Zoom in / out (ImageJ key handler)", "DocumentView zoom ladder on the hosted canvas", mag0 + " -> " + mag1 + " -> " + canvas.getMagnification());

        // Space + drag: pan (Fiji's own IJ.spaceBarDown() path inside ImageCanvas), no selection is created
        for (int i = 0; i < 6 && canvas.getSrcRect().width >= imp.getWidth(); i++) {
            key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_EQUALS, '=', 0);
            key(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_EQUALS, '=', 0);
            Thread.sleep(50);
        }
        waitFor("zoomed past the viewport", () -> canvas.getSrcRect().width < imp.getWidth(), 10);
        Rectangle src0 = new Rectangle(canvas.getSrcRect());
        key(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, ' ', 0);
        waitFor("space held (workspace and IJ1 agree)", () -> v.spaceDown() && IJ.spaceBarDown(), 5);
        drag(canvas, 120, 120, 40, 40);
        waitFor("viewport panned", () -> !canvas.getSrcRect().equals(src0), 10);
        assertTrue(engine.activeRoi(id).isEmpty() && imp.getRoi() == null, "panning with space creates no selection");
        key(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, ' ', 0);
        waitFor("space released", () -> !v.spaceDown() && !IJ.spaceBarDown(), 5);
        row("Space + drag", "Pan (IJ.spaceBarDown in ImageCanvas)", "forwarded to IJ1's own canvas handler", "srcRect " + src0 + " -> " + canvas.getSrcRect() + ", no selection");

        assertFalse(table.isEmpty());
        System.out.println("[shortcut] table rows: " + table.size());
    }
}
