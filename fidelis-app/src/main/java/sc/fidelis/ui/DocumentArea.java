package sc.fidelis.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;

import com.formdev.flatlaf.FlatClientProperties;

import ij.ImagePlus;
import sc.fidelis.engine.api.DocumentId;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.EngineEvent;

/**
 * The centre region: a tabbed set of {@link DocumentView}s, or an honest empty state. A tab selection
 * focuses the document in the engine (the IJ1 current image follows, EXP-01 section 6 item 5).
 */
public final class DocumentArea extends JPanel {
    private static final String TABS = "tabs";
    private static final String EMPTY = "empty";

    private final UiContext ctx;
    private final JTabbedPane tabs = new JTabbedPane();
    private final CardLayout cards = new CardLayout();
    private final Map<DocumentId, DocumentView> views = new LinkedHashMap<>();
    private final Set<ImagePlus> dirty = new HashSet<>();
    private boolean suppress;

    DocumentArea(UiContext ctx) {
        this.ctx = ctx;
        setLayout(cards);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSABLE, true);
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_TOOLTIPTEXT, "Close this image");
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_CLOSE_CALLBACK,
                (BiConsumer<JTabbedPane, Integer>) (pane, index) -> {
                    Component c = pane.getComponentAt(index);
                    if (c instanceof DocumentView v) ctx.closeDocument(v.id());
                });
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_AREA_ALIGNMENT, FlatClientProperties.TABBED_PANE_ALIGN_LEADING);
        tabs.addChangeListener(e -> {
            if (suppress) return;
            Component c = tabs.getSelectedComponent();
            if (c instanceof DocumentView v) ctx.focusDocument(v.id());
            ctx.frameTitleChanged();
        });
        add(tabs, TABS);
        add(emptyState(), EMPTY);
        cards.show(this, EMPTY);

        // Native IJ1 changes (slice keys, tools) arrive as ImageListener callbacks, possibly off the EDT.
        ImagePlus.addImageListener(new ij.ImageListener() {
            @Override
            public void imageOpened(ImagePlus imp) {
            }

            @Override
            public void imageClosed(ImagePlus imp) {
            }

            @Override
            public void imageUpdated(ImagePlus imp) {
                synchronized (dirty) {
                    if (!dirty.add(imp)) return;
                }
                SwingUtilities.invokeLater(() -> flush(imp));
            }
        });

        setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport s) {
                return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport s) {
                try {
                    Transferable t = s.getTransferable();
                    for (File f : (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor)) ctx.openPath(f.toPath());
                    return true;
                } catch (Exception e) {
                    ctx.notice("Drop failed: " + e, true);
                    return false;
                }
            }
        });
    }

    private void flush(ImagePlus imp) {
        synchronized (dirty) {
            dirty.remove(imp);
        }
        for (DocumentView v : views.values()) {
            if (v.imagePlus() == imp) {
                v.syncSliders();
                v.canvas().repaint();
            }
        }
    }

    private JComponent emptyState() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Theme.CANVAS_SURROUND);
        JLabel l = new JLabel("No image open");
        l.setForeground(new java.awt.Color(0xd8d8d8));
        l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
        l.setAlignmentX(0.5f);
        JLabel hint = new JLabel("File > Open..., or drop an image file here");
        hint.setForeground(new java.awt.Color(0xb0b0b0));
        hint.setAlignmentX(0.5f);
        JButton open = new JButton("Open image...");
        open.setAlignmentX(0.5f);
        open.addActionListener(e -> ctx.openFileDialog());
        p.add(Box.createVerticalGlue());
        p.add(l);
        p.add(Box.createVerticalStrut(6));
        p.add(hint);
        p.add(Box.createVerticalStrut(10));
        p.add(open);
        p.add(Box.createVerticalGlue());
        return p;
    }

    // ---- model sync ---------------------------------------------------------------------------

    /** Reconcile tabs with the engine's documents (call on {@code DocumentsChanged}). */
    void sync() {
        List<DocumentInfo> infos = ctx.engine.documents();
        Set<DocumentId> live = new HashSet<>();
        suppress = true;
        try {
            for (DocumentInfo info : infos) {
                live.add(info.id());
                DocumentView v = views.get(info.id());
                if (v == null) {
                    v = new DocumentView(ctx, info);
                    views.put(info.id(), v);
                    tabs.addTab(tabTitle(info), v);
                    tabs.setSelectedComponent(v);
                } else {
                    v.refresh(info);
                    int i = tabs.indexOfComponent(v);
                    if (i >= 0) {
                        tabs.setTitleAt(i, tabTitle(info));
                        tabs.setToolTipTextAt(i, tooltip(info));
                    }
                }
            }
            List<DocumentId> gone = new ArrayList<>();
            for (DocumentId id : views.keySet()) if (!live.contains(id)) gone.add(id);
            for (DocumentId id : gone) {
                DocumentView v = views.remove(id);
                v.detach();
                tabs.remove(v);
            }
        } finally {
            suppress = false;
        }
        cards.show(this, views.isEmpty() ? EMPTY : TABS);
        ctx.engine.focused().ifPresent(this::select);
        ctx.frameTitleChanged();
    }

    private static String tabTitle(DocumentInfo info) {
        return info.title() + (info.changed() ? " *" : "") + (info.virtualStack() ? " V" : "");
    }

    private static String tooltip(DocumentInfo info) {
        return info.title() + " (id " + info.imagePlusId() + ")" + (info.sourcePath() != null ? "\n" + info.sourcePath() : "");
    }

    /** Select the tab of a document without echoing the selection back to the engine. */
    void select(DocumentId id) {
        DocumentView v = views.get(id);
        if (v == null) return;
        if (tabs.getSelectedComponent() == v) return;
        suppress = true;
        try {
            tabs.setSelectedComponent(v);
        } finally {
            suppress = false;
        }
        ctx.frameTitleChanged();
    }

    void handle(EngineEvent e) {
        if (e instanceof EngineEvent.DocumentsChanged) sync();
        else if (e instanceof EngineEvent.DocumentFocusChanged f && f.focused() != null) select(f.focused());
        else if (e instanceof EngineEvent.StateDigestEvent d) {
            for (var ch : d.digest().changed()) {
                DocumentView v = views.get(ch.id());
                if (v != null) {
                    v.canvas().repaint();
                    ctx.engine.document(ch.id()).ifPresent(v::refresh);
                }
            }
        }
    }

    // ---- comparison snapshots (FR-VIEW-007) -------------------------------------------------------

    /** Largest image the UI keeps a before-copy of. */
    static final long MAX_SNAPSHOT_BYTES = 64L * 1024 * 1024;

    /** A real pixel copy taken before an action (imp null when no copy was taken). */
    record Snapshot(DocumentId doc, ImagePlus imp, String label) {}

    private final Map<DocumentId, Integer> inFlight = new java.util.HashMap<>();
    private volatile String snapshotNote = "no command submitted yet";

    /**
     * Take the before-copy for a COMMAND of consequence AC3 or higher, on the submitting side and before the
     * request is queued, so the engine cannot have changed pixels yet. A copy is taken only when no other
     * command on that document is still in flight (otherwise the pixel state is not well defined).
     */
    Snapshot beforeAction(sc.fidelis.engine.api.ActionRequest req, String label) {
        if (req.kind() != sc.fidelis.engine.api.Enums.ActionKind.COMMAND) return null;
        Snapshot[] out = new Snapshot[1];
        if (SwingUtilities.isEventDispatchThread()) out[0] = take(req, label);
        else {
            try {
                SwingUtilities.invokeAndWait(() -> out[0] = take(req, label));
            } catch (Exception e) {
                return null;
            }
        }
        return out[0];
    }

    private Snapshot take(sc.fidelis.engine.api.ActionRequest req, String label) {
        DocumentId id = req.target() != null ? req.target().document() : ctx.engine.focused().orElse(null);
        if (id == null) return null;
        boolean first = inFlight.merge(id, 1, Integer::sum) == 1;
        ImagePlus imp = ctx.engine.imagePlus(id);
        boolean significant = ctx.engine.schema(req.commandId())
                .map(s -> s.consequence().ordinal() >= sc.fidelis.engine.api.Enums.ConsequenceClass.AC3_REVERSIBLE_DATA.ordinal())
                .orElse(false);
        if (imp == null || !first || !significant || views.get(id) == null) {
            snapshotNote = "no snapshot for '" + label + "': " + (imp == null ? "no image" : !first ? "another command in flight (" + inFlight.get(id) + ")"
                    : !significant ? "not significant (" + req.commandId() + ")" : "no view");
            return new Snapshot(id, null, label);
        }
        long bytes = (long) imp.getWidth() * imp.getHeight() * Math.max(1, imp.getStackSize())
                * (imp.getBitDepth() == 24 ? 4 : Math.max(1, imp.getBitDepth() / 8));
        if (bytes > MAX_SNAPSHOT_BYTES || imp.getStack().isVirtual()) {
            snapshotNote = "no snapshot for '" + label + "': " + bytes + " bytes" + (imp.getStack().isVirtual() ? ", virtual stack" : "");
            return new Snapshot(id, null, label);
        }
        snapshotNote = "snapshot taken for '" + label + "'";
        ImagePlus dup = imp.duplicate();
        if (dup == null) return new Snapshot(id, null, label);
        if (!imp.isComposite() && imp.getBitDepth() != 24) dup.setDisplayRange(imp.getDisplayRangeMin(), imp.getDisplayRangeMax());
        dup.setTitle("[before] " + imp.getTitle());
        dup.setProperty(sc.fidelis.engine.api.HostCanvas.TRANSIENT_PROPERTY, Boolean.TRUE); // the engine must not adopt it
        return new Snapshot(id, dup, label);
    }

    /** Keep the before-copy only when the action completed and changed this document's pixels. */
    void afterAction(Snapshot s, sc.fidelis.engine.api.ActionResult r) {
        if (s == null) return;
        inFlight.merge(s.doc(), -1, Integer::sum);
        if (inFlight.getOrDefault(s.doc(), 0) <= 0) inFlight.remove(s.doc());
        if (s.imp() == null) return;
        boolean changed = r != null && r.state() == sc.fidelis.engine.api.Enums.OperationState.COMPLETED && r.digest() != null
                && r.digest().changed().stream().anyMatch(c -> c.id().equals(s.doc()) && c.pixelsChanged());
        DocumentView v = views.get(s.doc());
        snapshotNote = "after '" + s.label() + "': " + (r == null ? "failed exceptionally" : "state=" + r.state() + " changed="
                + (r.digest() == null ? "none" : r.digest().changed())) + (changed && v != null ? "; before-copy kept" : "; before-copy dropped");
        if (changed && v != null) v.setBefore(s.imp(), s.label());
        else s.imp().flush();
    }

    /** What happened to the last before-copy (why none was taken, or the result it was judged on); for tests and logs. */
    String snapshotDiagnostics() {
        return snapshotNote + "; in flight: " + inFlight;
    }

    public DocumentView view(DocumentId id) {
        return views.get(id);
    }

    public DocumentView focusedView() {
        Component c = tabs.getSelectedComponent();
        return c instanceof DocumentView v ? v : null;
    }

    public List<DocumentView> allViews() {
        return new ArrayList<>(views.values());
    }

    /** "Put Behind": the next document tab. */
    void nextTab() {
        int n = tabs.getTabCount();
        if (n > 1) tabs.setSelectedIndex((tabs.getSelectedIndex() + 1) % n);
    }

    @Override
    public java.awt.Dimension getMinimumSize() {
        return new java.awt.Dimension(200, 150);
    }

    static JPanel padded(JComponent c, int pad) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(pad, pad, pad, pad));
        p.add(c);
        return p;
    }
}
