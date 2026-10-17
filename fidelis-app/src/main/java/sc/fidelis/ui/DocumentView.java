package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JToggleButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Enums.Origin;
import sc.fidelis.engine.api.HostCanvas;
import sc.fidelis.engine.api.TargetScope;

/**
 * One open document: a header (title, IJ1 subtitle, zoom controls, C/Z/T sliders) above the engine's
 * real {@code HostCanvas}, which is re-parented here from its hidden {@code ImageWindow}.
 *
 * <p>The host drives the viewport exactly as EXP-01 prescribes: {@code setDrawingSize}, then
 * {@code setSourceRect}, then {@code setMagnification} (that order), then the bounds. The canvas is
 * the source of truth for magnification and source rectangle, because IJ1's own hand tool, Space-drag
 * and wheel scrolling change them natively. Native {@code zoomIn/zoomOut/unzoom} are never called by
 * the host (they re-pack the hidden window); the ladder comes from
 * {@code ImageCanvas.getHigherZoomLevel/getLowerZoomLevel}.
 */
public final class DocumentView extends JPanel {
    /** AUTO and FIT re-fit on resize; MANUAL keeps magnification and centre. */
    enum Mode { AUTO, FIT, MANUAL }

    private final UiContext ctx;
    private final DocumentId id;
    private ImagePlus imp;
    private ImageWindow win;
    private ImageCanvas canvas;
    private final HostPanel host = new HostPanel();
    private final JTextField titleField = flatField(true);
    private final JTextField subtitleField = flatField(false);
    private final JLabel zoomLabel = new JLabel("100%");
    private final JPanel dimPanel = new JPanel();
    private final Map<Character, JSlider> sliders = new LinkedHashMap<>();
    private final Map<Character, JLabel> sliderLabels = new LinkedHashMap<>();
    private boolean adjustingFromImage;
    private Mode mode = Mode.AUTO;
    private double initialMagnification = 1.0;
    private double lastMagSeen = Double.NaN;
    private boolean spaceDown;
    private final Timer poll;
    private int dimsKey = -1;

    // ---- before/after comparison (FR-VIEW-007) ----
    private final JPanel centre = new JPanel(new BorderLayout());
    private final JToggleButton compareButton = new JToggleButton("Compare");
    private final JLabel beforeTitle = new JLabel();
    private final JLabel afterTitle = new JLabel();
    private final JPanel beforePanel = new JPanel(new BorderLayout());
    private final JPanel afterPanel = new JPanel(new BorderLayout());
    private final BeforeHost beforeHost = new BeforeHost();
    private ImagePlus beforeImp;
    private String beforeLabel;
    private ImageWindow beforeWin;
    private ImageCanvas beforeCanvas;
    private boolean compareOn;
    private Rectangle lastSrcSeen = new Rectangle();

    DocumentView(UiContext ctx, DocumentInfo info) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.id = info.id();
        setOpaque(false);

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        JPanel texts = new JPanel();
        texts.setLayout(new javax.swing.BoxLayout(texts, javax.swing.BoxLayout.Y_AXIS));
        texts.setOpaque(false);
        texts.add(titleField);
        texts.add(subtitleField);
        header.add(texts, BorderLayout.CENTER);
        header.add(zoomControls(), BorderLayout.EAST);
        dimPanel.setLayout(new javax.swing.BoxLayout(dimPanel, javax.swing.BoxLayout.Y_AXIS));
        dimPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 2, 8));
        JPanel north = new JPanel(new BorderLayout());
        north.add(header, BorderLayout.NORTH);
        north.add(dimPanel, BorderLayout.CENTER);
        north.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.panelBorder()));
        add(north, BorderLayout.NORTH);
        centre.add(host, BorderLayout.CENTER);
        add(centre, BorderLayout.CENTER);

        attach();
        refresh(info);
        poll = new Timer(250, e -> pollCanvas());
    }

    // ---- hosting ----------------------------------------------------------------------------

    private void attach() {
        win = ctx.engine.hostWindow(id);
        imp = ctx.engine.imagePlus(id);
        canvas = win.getCanvas();
        Container parent = canvas.getParent();
        if (parent != null) parent.remove(canvas);
        win.setLayout(null); // EXP-01 H2 trap (b): a hidden window must not re-pack around a missing canvas
        host.add(canvas);

        // IJ1 registers ij.ImageJ as the canvas key listener. Zoom keys and Ctrl-shortcuts are taken by the
        // workspace (the host owns zoom; Ctrl-shortcuts run through the engine via the menu accelerators).
        final KeyListener[] original = canvas.getKeyListeners();
        for (KeyListener l : original) canvas.removeKeyListener(l);
        canvas.addKeyListener(new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (consume(e)) return;
                for (KeyListener l : original) l.keyTyped(e);
            }

            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) spaceDown = true;
                if (consume(e)) return;
                for (KeyListener l : original) l.keyPressed(e);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) spaceDown = false;
                if (consume(e)) return;
                for (KeyListener l : original) l.keyReleased(e);
            }

            private boolean consume(KeyEvent e) {
                if (e.isControlDown() || e.isMetaDown()) return true; // menu accelerators handle these
                char ch = e.getKeyChar();
                boolean pressedOrTyped = e.getID() != KeyEvent.KEY_RELEASED;
                if (ch == '+' || ch == '=' || e.getKeyCode() == KeyEvent.VK_ADD) {
                    if (e.getID() == KeyEvent.KEY_PRESSED) zoomStep(true, null);
                    return true;
                }
                if (ch == '-' || e.getKeyCode() == KeyEvent.VK_SUBTRACT) {
                    if (e.getID() == KeyEvent.KEY_PRESSED) zoomStep(false, null);
                    return true;
                }
                if ((ch == 'l' || e.getKeyCode() == KeyEvent.VK_ENTER) && pressedOrTyped) {
                    if (e.getID() == KeyEvent.KEY_PRESSED) ctx.focusCommandBar();
                    return true;
                }
                return false;
            }
        });
        canvas.setFocusTraversalKeysEnabled(false);

        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                canvas.requestFocusInWindow();
                ctx.focusDocument(id);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                ctx.status().setReadout("");
            }
        });
        canvas.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateReadout(e.getX(), e.getY());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                updateReadout(e.getX(), e.getY());
            }
        });
        canvas.addMouseWheelListener(this::onWheel);
        canvas.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                recentre();
            }
        });
    }

    /** Detach the canvas so the engine can dispose the document. */
    void detach() {
        poll.stop();
        disposeBefore();
        if (beforeImp != null) beforeImp.flush();
        beforeImp = null;
        host.remove(canvas);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        poll.start();
    }

    @Override
    public void removeNotify() {
        poll.stop();
        super.removeNotify();
    }

    public DocumentId id() {
        return id;
    }

    public ImageCanvas canvas() {
        return canvas;
    }

    public ImagePlus imagePlus() {
        return imp;
    }

    /** The surround panel that holds the heavyweight canvas (used for screenshots and tests). */
    public Container hostPanel() {
        return host;
    }

    // ---- header -----------------------------------------------------------------------------

    private static JTextField flatField(boolean bold) {
        JTextField f = new JTextField();
        f.setEditable(false);
        f.setBorder(null);
        f.setOpaque(false);
        f.setFocusable(true);
        if (bold) f.setFont(f.getFont().deriveFont(Font.BOLD));
        else f.setForeground(Theme.muted());
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        return f;
    }

    private JPanel zoomControls() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        p.setOpaque(false);
        zoomLabel.setFont(zoomLabel.getFont().deriveFont(Font.BOLD));
        zoomLabel.setPreferredSize(new Dimension(52, 18));
        zoomLabel.setHorizontalAlignment(JLabel.RIGHT);
        zoomLabel.setToolTipText("Magnification");
        p.add(zoomLabel);
        p.add(smallButton("−", "Zoom out (-)", () -> zoomStep(false, null)));
        p.add(smallButton("+", "Zoom in (+)", () -> zoomStep(true, null)));
        p.add(smallButton("Fit", "Scale to fit the viewport", this::fit));
        p.add(smallButton("1:1", "View 100% (one image pixel per screen pixel)", this::actualPixels));
        compareButton.setMargin(new java.awt.Insets(1, 6, 1, 6));
        compareButton.setFocusable(false);
        compareButton.setEnabled(false);
        compareButton.setToolTipText("Compare with the image before the last data-changing action (none yet)");
        compareButton.addActionListener(e -> setCompare(compareButton.isSelected()));
        p.add(compareButton);
        return p;
    }

    private static JButton smallButton(String text, String tip, Runnable r) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setMargin(new java.awt.Insets(1, 5, 1, 5));
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    /** Re-read document metadata into the header and the C/Z/T sliders. */
    public void refresh(DocumentInfo info) {
        if (info == null) return;
        String t = info.title() + (info.changed() ? " *" : "") + (info.virtualStack() ? " V" : "");
        titleField.setText(t + "   (id " + info.imagePlusId() + ")");
        String sub;
        try {
            sub = win.createSubtitle();
        } catch (RuntimeException ex) {
            sub = info.width() + "x" + info.height() + " pixels; " + info.type();
        }
        subtitleField.setText(sub);
        subtitleField.setToolTipText(sub);
        titleField.setCaretPosition(0);
        subtitleField.setCaretPosition(0);
        rebuildDims(info);
        syncSliders();
        canvas.repaint();
    }

    private void rebuildDims(DocumentInfo info) {
        int key = info.nChannels() * 1_000_000 + info.nSlices() * 1_000 + info.nFrames();
        if (key == dimsKey) return;
        dimsKey = key;
        dimPanel.removeAll();
        sliders.clear();
        sliderLabels.clear();
        addSlider('c', "Channel", info.nChannels());
        addSlider('z', "Slice", info.nSlices());
        addSlider('t', "Frame", info.nFrames());
        dimPanel.revalidate();
        dimPanel.repaint();
    }

    private void addSlider(char dim, String name, int n) {
        if (n <= 1) return;
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        JLabel label = new JLabel(dim + " 1/" + n);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setPreferredSize(new Dimension(64, 18));
        label.setToolTipText(name);
        JSlider s = new JSlider(1, n, 1);
        s.setFocusable(true);
        s.setToolTipText(name);
        s.addChangeListener(e -> {
            label.setText(dim + " " + s.getValue() + "/" + n);
            if (adjustingFromImage || s.getValueIsAdjusting()) return;
            commitPosition();
        });
        row.add(label, BorderLayout.WEST);
        row.add(s, BorderLayout.CENTER);
        dimPanel.add(row);
        sliders.put(dim, s);
        sliderLabels.put(dim, label);
    }

    private void commitPosition() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("channel", sliders.containsKey('c') ? sliders.get('c').getValue() : imp.getChannel());
        p.put("slice", sliders.containsKey('z') ? sliders.get('z').getValue() : imp.getSlice());
        p.put("frame", sliders.containsKey('t') ? sliders.get('t').getValue() : imp.getFrame());
        ctx.submit(ActionRequest.direct(Origin.PANEL, Operations.SET_POSITION, p, TargetScope.of(id)), "Set position");
    }

    /** Called when IJ1 changed the position natively (keys, wheel) or after an engine action. */
    public void syncSliders() {
        adjustingFromImage = true;
        try {
            setSlider('c', imp.getChannel());
            setSlider('z', imp.getSlice());
            setSlider('t', imp.getFrame());
        } finally {
            adjustingFromImage = false;
        }
    }

    private void setSlider(char dim, int v) {
        JSlider s = sliders.get(dim);
        if (s != null && s.getValue() != v && !s.getValueIsAdjusting()) s.setValue(v);
    }

    // ---- read-out (FR-VIEW-003) -------------------------------------------------------------

    private static final Method VALUE_AS_STRING = valueMethod();

    private static Method valueMethod() {
        try {
            Method m = ImagePlus.class.getDeclaredMethod("getValueAsString", int.class, int.class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private void updateReadout(int sx, int sy) {
        int x = canvas.offScreenX(sx);
        int y = canvas.offScreenY(sy);
        if (x < 0 || y < 0 || x >= imp.getWidth() || y >= imp.getHeight()) {
            ctx.status().setReadout("");
            return;
        }
        Roi roi = imp.getRoi();
        if (roi != null && roi.getState() != Roi.NORMAL) return; // IJ1 shows ROI geometry while a gesture runs
        String s = imp.getLocationAsString(x, y);
        String value = "";
        if (VALUE_AS_STRING != null) {
            try {
                value = (String) VALUE_AS_STRING.invoke(imp, x, y);
            } catch (ReflectiveOperationException | RuntimeException e) {
                value = ", value=" + java.util.Arrays.toString(imp.getPixel(x, y));
            }
        }
        ctx.status().setReadout((s + value).trim());
    }

    // ---- viewport ---------------------------------------------------------------------------

    private final class HostPanel extends JPanel {
        HostPanel() {
            super(null);
            setBackground(Theme.CANVAS_SURROUND);
            setOpaque(true);
        }

        @Override
        public void doLayout() {
            onHostResized();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(320, 240);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setBackground(Theme.CANVAS_SURROUND);
        }
    }

    private int vw() {
        return Math.max(1, host.getWidth());
    }

    private int vh() {
        return Math.max(1, host.getHeight());
    }

    private void onHostResized() {
        if (canvas == null || host.getWidth() <= 0 || host.getHeight() <= 0) return;
        switch (mode) {
            case AUTO -> {
                initialMagnification = initialMagnification();
                layoutViewport(initialMagnification, imp.getWidth() / 2.0, imp.getHeight() / 2.0, vw() / 2.0, vh() / 2.0);
            }
            case FIT -> layoutViewport(fitMagnification(), imp.getWidth() / 2.0, imp.getHeight() / 2.0, vw() / 2.0, vh() / 2.0);
            default -> {
                Rectangle src = canvas.getSrcRect();
                layoutViewport(canvas.getMagnification(), src.x + src.width / 2.0, src.y + src.height / 2.0, vw() / 2.0, vh() / 2.0);
            }
        }
    }

    /** IJ1 resized the canvas itself (native zoom tool): keep it centred in the surround. */
    private void recentre() {
        if (canvas == null) return;
        int x = (vw() - canvas.getWidth()) / 2;
        int y = (vh() - canvas.getHeight()) / 2;
        if (canvas.getX() != x || canvas.getY() != y) canvas.setLocation(x, y);
        updateZoomLabel();
    }

    double fitMagnification() {
        double fit = Math.min(vw() / (double) imp.getWidth(), vh() / (double) imp.getHeight());
        return Math.max(1.0 / 72.0, Math.min(32.0, fit));
    }

    /** Highest ladder level that fits the viewport, but at least 1:1 up to 4x for small images. */
    double initialMagnification() {
        double fit = Math.min(vw() / (double) imp.getWidth(), vh() / (double) imp.getHeight());
        double limit = Math.min(fit, 4.0);
        double m = 1.0;
        if (limit >= 1.0) {
            while (ImageCanvas.getHigherZoomLevel(m) <= limit + 1e-9 && ImageCanvas.getHigherZoomLevel(m) > m)
                m = ImageCanvas.getHigherZoomLevel(m);
        } else {
            while (m > limit + 1e-9 && ImageCanvas.getLowerZoomLevel(m) < m) m = ImageCanvas.getLowerZoomLevel(m);
        }
        return m;
    }

    /**
     * Apply a viewport so that image point (ax, ay) sits at host point (hx, hy) where possible.
     * Order is mandatory: setDrawingSize, setSourceRect, setMagnification, bounds (EXP-01 section 5.3 item 5).
     */
    void layoutViewport(double mag, double ax, double ay, double hx, double hy) {
        mag = layoutCanvas(canvas, imp, vw(), vh(), mag, ax, ay, hx, hy);
        lastMagSeen = mag;
        lastSrcSeen = new Rectangle(canvas.getSrcRect());
        updateZoomLabel();
        mirrorBefore();
    }

    /** The viewport algorithm for any hosted canvas; returns the clamped magnification applied. */
    private static double layoutCanvas(ImageCanvas canvas, ImagePlus imp, int vw, int vh,
                                       double mag, double ax, double ay, double hx, double hy) {
        int iw = imp.getWidth();
        int ih = imp.getHeight();
        mag = Math.max(1.0 / 72.0, Math.min(32.0, mag));
        int dw = (int) Math.min(vw, Math.max(1, Math.round(iw * mag)));
        int dh = (int) Math.min(vh, Math.max(1, Math.round(ih * mag)));
        int cx = (vw - dw) / 2;
        int cy = (vh - dh) / 2;
        int sw = (int) Math.max(1, Math.min(iw, Math.ceil(dw / mag - 1e-9)));
        int sh = (int) Math.max(1, Math.min(ih, Math.ceil(dh / mag - 1e-9)));
        double sx = ax - (hx - cx) / mag;
        double sy = ay - (hy - cy) / mag;
        int ix = (int) Math.max(0, Math.min(iw - sw, Math.round(sx)));
        int iy = (int) Math.max(0, Math.min(ih - sh, Math.round(sy)));
        canvas.setDrawingSize(dw, dh);
        canvas.setSourceRect(new Rectangle(ix, iy, sw, sh));
        canvas.setMagnification(mag);
        canvas.setBounds(cx, cy, dw, dh);
        canvas.repaint();
        return mag;
    }

    // ---- before/after comparison -----------------------------------------------------------------

    private final class BeforeHost extends JPanel {
        BeforeHost() {
            super(null);
            setBackground(Theme.CANVAS_SURROUND);
        }

        @Override
        public void doLayout() {
            mirrorBefore();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setBackground(Theme.CANVAS_SURROUND);
        }
    }

    /** Called with a real pixel copy taken before a data-changing action completed. */
    void setBefore(ImagePlus snapshot, String label) {
        boolean rebuild = compareOn;
        if (rebuild) disposeBefore();
        if (beforeImp != null && beforeImp != snapshot) beforeImp.flush();
        beforeImp = snapshot;
        beforeLabel = label;
        compareButton.setEnabled(true);
        compareButton.setToolTipText("Compare with the image before: " + label);
        if (rebuild) {
            buildBefore();
            updateCompareLabels();
            mirrorBefore();
        }
    }

    boolean hasBefore() {
        return beforeImp != null;
    }

    boolean isCompareOn() {
        return compareOn;
    }

    /** Both canvases while comparing, else only the document canvas. */
    java.util.List<ImageCanvas> visibleCanvases() {
        java.util.List<ImageCanvas> l = new java.util.ArrayList<>();
        l.add(canvas);
        if (compareOn && beforeCanvas != null) l.add(beforeCanvas);
        return l;
    }

    ImageCanvas compareCanvas() {
        return compareOn ? beforeCanvas : null;
    }

    ImagePlus beforeImagePlus() {
        return beforeImp;
    }

    void setCompare(boolean on) {
        if (on && beforeImp == null) {
            ctx.notice("There is no earlier state to compare with yet: run a data-changing command first", false);
            compareButton.setSelected(false);
            return;
        }
        if (on == compareOn) return;
        compareOn = on;
        compareButton.setSelected(on);
        centre.removeAll();
        if (on) {
            beforePanel.removeAll();
            beforePanel.add(beforeTitle, BorderLayout.NORTH);
            beforePanel.add(beforeHost, BorderLayout.CENTER);
            afterPanel.removeAll();
            afterPanel.add(afterTitle, BorderLayout.NORTH);
            afterPanel.add(host, BorderLayout.CENTER);
            beforeTitle.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            afterTitle.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            beforeTitle.setFont(beforeTitle.getFont().deriveFont(Font.BOLD));
            afterTitle.setFont(afterTitle.getFont().deriveFont(Font.BOLD));
            buildBefore();
            updateCompareLabels();
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, beforePanel, afterPanel);
            split.setResizeWeight(0.5);
            split.setContinuousLayout(true);
            split.setBorder(null);
            centre.add(split, BorderLayout.CENTER);
            SwingUtilities.invokeLater(() -> split.setDividerLocation(0.5));
        } else {
            disposeBefore();
            afterPanel.remove(host);
            centre.add(host, BorderLayout.CENTER);
        }
        centre.revalidate();
        centre.repaint();
    }

    private void updateCompareLabels() {
        beforeTitle.setText("Before: " + beforeLabel);
        afterTitle.setText("After: " + beforeLabel);
    }

    /** A real copy, hosted by its own HostCanvas in a hidden window (read-only: no mouse or key handling). */
    private void buildBefore() {
        HostCanvas hc = new HostCanvas(beforeImp);
        ImageWindow bw = new ImageWindow(beforeImp, hc);
        bw.setVisible(false);
        bw.setLayout(null);
        bw.remove(hc);
        for (java.awt.event.MouseListener l : hc.getMouseListeners()) hc.removeMouseListener(l);
        for (java.awt.event.MouseMotionListener l : hc.getMouseMotionListeners()) hc.removeMouseMotionListener(l);
        for (KeyListener l : hc.getKeyListeners()) hc.removeKeyListener(l);
        beforeHost.add(hc);
        beforeWin = bw;
        beforeCanvas = hc;
        ctx.legacyWindows().ignore(bw);
        ctx.engine.focus(id); // the new window must not steal IJ1's current image
        beforeHost.revalidate();
    }

    private void disposeBefore() {
        if (beforeCanvas != null) beforeHost.remove(beforeCanvas);
        if (beforeWin != null) {
            beforeWin.setVisible(false);
            WindowManager.removeWindow(beforeWin);
            beforeWin.dispose();
        }
        beforeWin = null;
        beforeCanvas = null;
    }

    /** Linked zoom and pan: the before canvas always shows the same magnification and centre. */
    private void mirrorBefore() {
        if (!compareOn || beforeCanvas == null || beforeHost.getWidth() <= 0 || beforeHost.getHeight() <= 0) return;
        Rectangle src = canvas.getSrcRect();
        layoutCanvas(beforeCanvas, beforeImp, beforeHost.getWidth(), beforeHost.getHeight(), canvas.getMagnification(),
                src.x + src.width / 2.0, src.y + src.height / 2.0, beforeHost.getWidth() / 2.0, beforeHost.getHeight() / 2.0);
    }

    /** One ladder step; anchored at {@code cursor} (canvas coordinates) or the centre of the visible region. */
    public void zoomStep(boolean in, Point cursor) {
        double mag = canvas.getMagnification();
        double nm = in ? ImageCanvas.getHigherZoomLevel(mag) : ImageCanvas.getLowerZoomLevel(mag);
        if (nm == mag) return;
        double ax;
        double ay;
        double hx;
        double hy;
        boolean overImage = cursor != null && cursor.x >= 0 && cursor.y >= 0
                && cursor.x < canvas.getWidth() && cursor.y < canvas.getHeight();
        if (overImage) {
            ax = canvas.offScreenXD(cursor.x);
            ay = canvas.offScreenYD(cursor.y);
            hx = canvas.getX() + cursor.x;
            hy = canvas.getY() + cursor.y;
        } else {
            Rectangle src = canvas.getSrcRect();
            ax = src.x + src.width / 2.0;
            ay = src.y + src.height / 2.0;
            hx = canvas.getX() + canvas.getWidth() / 2.0;
            hy = canvas.getY() + canvas.getHeight() / 2.0;
        }
        mode = Mode.MANUAL;
        layoutViewport(nm, ax, ay, hx, hy);
    }

    public void fit() {
        mode = Mode.FIT;
        onHostResized();
    }

    public void actualPixels() {
        mode = Mode.MANUAL;
        Rectangle src = canvas.getSrcRect();
        layoutViewport(1.0, src.x + src.width / 2.0, src.y + src.height / 2.0, vw() / 2.0, vh() / 2.0);
    }

    /** Image menu "Original Scale": the magnification the document had when first shown. */
    public void originalScale() {
        mode = Mode.AUTO;
        onHostResized();
    }

    /** Image menu "Set...": arbitrary magnification in percent, centred on the visible region. */
    public void setMagnificationPercent(double percent) {
        mode = Mode.MANUAL;
        Rectangle src = canvas.getSrcRect();
        layoutViewport(percent / 100.0, src.x + src.width / 2.0, src.y + src.height / 2.0, vw() / 2.0, vh() / 2.0);
    }

    /** Image menu "To Selection": highest ladder level at which the ROI bounds fit, centred on the ROI. */
    public void zoomToSelection() {
        Roi roi = imp.getRoi();
        if (roi == null) {
            ctx.notice("To Selection needs a selection", false);
            return;
        }
        Rectangle b = roi.getBounds();
        double fit = Math.min(vw() / (double) Math.max(1, b.width), vh() / (double) Math.max(1, b.height));
        double m = 1.0 / 72.0;
        while (ImageCanvas.getHigherZoomLevel(m) <= fit + 1e-9 && ImageCanvas.getHigherZoomLevel(m) > m) m = ImageCanvas.getHigherZoomLevel(m);
        mode = Mode.MANUAL;
        layoutViewport(m, b.x + b.width / 2.0, b.y + b.height / 2.0, vw() / 2.0, vh() / 2.0);
    }

    private void onWheel(MouseWheelEvent e) {
        if (e.isControlDown() || e.isShiftDown()) {
            zoomStep(e.getWheelRotation() < 0, e.getPoint());
            e.consume();
        } else {
            // IJ1's own scroll (single image) or slice change (stack), handled by the hidden window
            win.mouseWheelMoved(e);
        }
    }

    private void pollCanvas() {
        if (canvas == null || !isShowing()) return;
        double m = canvas.getMagnification();
        if (m != lastMagSeen) {
            // changed natively (zoom tool, IJ1 keys): the host stops auto-fitting
            if (!Double.isNaN(lastMagSeen)) mode = Mode.MANUAL;
            lastMagSeen = m;
            updateZoomLabel();
        }
        if (compareOn && !lastSrcSeen.equals(canvas.getSrcRect())) {
            // panned natively (hand tool, Space-drag, wheel): follow in the before pane
            lastSrcSeen = new Rectangle(canvas.getSrcRect());
            mirrorBefore();
        }
        syncSliders();
        refreshSubtitle();
    }

    private void refreshSubtitle() {
        try {
            String sub = win.createSubtitle();
            if (!sub.equals(subtitleField.getText())) {
                subtitleField.setText(sub);
                subtitleField.setToolTipText(sub);
                subtitleField.setCaretPosition(0);
            }
        } catch (RuntimeException ignored) {
            // the window may be mid-update
        }
    }

    /** Test and dev hook: move a C/Z/T slider as the user would (commits SET_POSITION). */
    public void moveSlider(char dim, int value) {
        JSlider s = sliders.get(dim);
        if (s != null) s.setValue(value);
    }

    public String subtitleText() {
        return subtitleField.getText();
    }

    /** Zoom as IJ1's title rule: no decimals above 100 % or when integral, else one decimal. */
    static String zoomText(double mag) {
        double pct = mag * 100.0;
        if (pct > 100.0 || pct == Math.rint(pct)) return String.format(Locale.ROOT, "%d%%", Math.round(pct));
        return String.format(Locale.ROOT, "%.1f%%", pct);
    }

    private void updateZoomLabel() {
        zoomLabel.setText(zoomText(canvas.getMagnification()));
    }

    // ---- accessors for tests and the screenshot ----------------------------------------------

    /** The zoom read-out as shown in the target strip (for example {@code 200%}). */
    String zoomLabelText() {
        return zoomLabel.getText();
    }

    public double magnification() {
        return canvas.getMagnification();
    }

    public Mode mode() {
        return mode;
    }

    public boolean spaceDown() {
        return spaceDown;
    }

    /** Selected-tab visibility helper. */
    boolean isDisplayedNow() {
        return SwingUtilities.getWindowAncestor(this) != null && isShowing();
    }

    Color surround() {
        return host.getBackground();
    }
}
