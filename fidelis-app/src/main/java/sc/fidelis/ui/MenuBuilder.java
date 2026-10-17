package sc.fidelis.ui;

import java.util.ArrayList;
import java.util.List;

import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import ij.Menus;
import sc.fidelis.engine.api.DocumentInfo;
import sc.fidelis.engine.api.Enums.Origin;

/**
 * Builds the Swing menu bar from the live Fiji menu hierarchy ({@code Menus.getMenuBar()}) plus one
 * additive <em>Workspace</em> menu before Help (docs D12 M-01, M-02, M-06). Every command item
 * dispatches through {@link UiContext#runCommand}; see {@link Routes} for the short rebinding list.
 */
final class MenuBuilder {
    private final UiContext ctx;
    private final Workspace workspace;
    private final List<JMenu> scientificMenus = new ArrayList<>();
    private int windowStaticItems;

    MenuBuilder(UiContext ctx, Workspace workspace) {
        this.ctx = ctx;
        this.workspace = workspace;
    }

    JMenuBar build() {
        JMenuBar bar = new JMenuBar();
        List<MenuModel.Node> top = MenuModel.read(Menus.getMenuBar());
        boolean inserted = false;
        for (MenuModel.Node n : top) {
            if (!inserted && "Help".equals(n.label())) {
                bar.add(workspaceMenu());
                inserted = true;
            }
            JMenu m = toMenu(n);
            scientificMenus.add(m);
            bar.add(m);
            if ("Window".equals(n.label())) {
                windowStaticItems = m.getItemCount();
                m.addMenuListener(new MenuListener() {
                    @Override
                    public void menuSelected(MenuEvent e) {
                        refreshWindowMenu(m);
                    }

                    @Override
                    public void menuDeselected(MenuEvent e) {
                    }

                    @Override
                    public void menuCanceled(MenuEvent e) {
                    }
                });
            }
        }
        if (!inserted) bar.add(workspaceMenu());
        if (top.isEmpty()) {
            JMenu empty = new JMenu("File");
            empty.add(item("Open...", "ij1:File>Open...", null));
            scientificMenus.add(empty);
            bar.add(empty);
        }
        return bar;
    }

    private JMenu toMenu(MenuModel.Node node) {
        JMenu menu = new JMenu(node.label());
        for (MenuModel.Node c : node.children()) {
            if (c.isSeparator()) {
                menu.addSeparator();
            } else if (c.isMenu()) {
                menu.add(toMenu(c));
            } else {
                JMenuItem it = c.checkbox() ? new JCheckBoxMenuItem(c.label()) : new JMenuItem(c.label());
                String id = c.commandId();
                if (c.accelerator() != null) {
                    it.setAccelerator(c.accelerator());
                    ctx.registerAccelerator(c.accelerator(), id);
                }
                it.setToolTipText(id.substring(4));
                // Swing fires the item with the key event's modifiers when its accelerator triggered it
                it.addActionListener(e -> ctx.runCommand(id,
                        (e.getModifiers() & java.awt.event.ActionEvent.CTRL_MASK) != 0 ? Origin.SHORTCUT : Origin.MENU));
                menu.add(it);
            }
        }
        return menu;
    }

    private JMenuItem item(String label, String id, javax.swing.KeyStroke ks) {
        JMenuItem it = new JMenuItem(label);
        if (ks != null) it.setAccelerator(ks);
        it.addActionListener(e -> ctx.runCommand(id, Origin.MENU));
        return it;
    }

    /** Window menu: Fiji's fixed items, then the open documents, then tracked Fiji windows. */
    private void refreshWindowMenu(JMenu menu) {
        while (menu.getItemCount() > windowStaticItems) menu.remove(menu.getItemCount() - 1);
        List<DocumentInfo> docs = ctx.engine.documents();
        if (!docs.isEmpty()) menu.addSeparator();
        for (DocumentInfo d : docs) {
            JMenuItem it = new JMenuItem(d.title());
            it.setToolTipText("id " + d.imagePlusId());
            it.addActionListener(e -> ctx.focusDocument(d.id()));
            menu.add(it);
        }
        List<LegacyWindows.Entry> es = ctx.legacyWindows().entries();
        if (!es.isEmpty()) menu.addSeparator();
        for (LegacyWindows.Entry en : es) {
            JMenuItem it = new JMenuItem(en.title() + "  (Fiji window)");
            it.addActionListener(e -> ctx.legacyWindows().show(en.title()));
            menu.add(it);
        }
    }

    private JMenu workspaceMenu() {
        JMenu m = new JMenu("Workspace");
        JMenu theme = new JMenu("Theme");
        ButtonGroup g = new ButtonGroup();
        JRadioButtonMenuItem light = new JRadioButtonMenuItem("Light", !Theme.isDark());
        JRadioButtonMenuItem dark = new JRadioButtonMenuItem("Dark", Theme.isDark());
        light.addActionListener(e -> Theme.setDark(false));
        dark.addActionListener(e -> Theme.setDark(true));
        g.add(light);
        g.add(dark);
        theme.add(light);
        theme.add(dark);
        m.add(theme);
        m.addSeparator();
        JCheckBoxMenuItem right = new JCheckBoxMenuItem("Right dock", true);
        right.addActionListener(e -> ctx.docks().setRightVisible(right.isSelected()));
        JCheckBoxMenuItem bottom = new JCheckBoxMenuItem("Bottom dock", true);
        bottom.addActionListener(e -> ctx.docks().setBottomCollapsed(!bottom.isSelected()));
        JCheckBoxMenuItem labels = new JCheckBoxMenuItem("Labelled tools", false);
        labels.addActionListener(e -> workspace.tools().setLabelled(labels.isSelected()));
        m.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                right.setSelected(ctx.docks().isRightVisible());
                bottom.setSelected(!ctx.docks().isBottomCollapsed());
                labels.setSelected(workspace.tools().isLabelled());
                light.setSelected(!Theme.isDark());
                dark.setSelected(Theme.isDark());
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
        m.add(right);
        m.add(bottom);
        m.add(labels);
        m.addSeparator();
        m.add(panelItem("Parameters", () -> ctx.docks().showParameters()));
        m.add(panelItem("Brightness/Contrast", () -> ctx.docks().showDisplay()));
        m.add(panelItem("Threshold", () -> ctx.docks().showThreshold()));
        m.add(panelItem("ROI list", () -> ctx.docks().showRoi()));
        m.add(panelItem("Results", () -> ctx.docks().showResults()));
        m.add(panelItem("History & macro", () -> ctx.docks().showHistory()));
        JMenuItem editor = panelItem("Macro editor", () -> ctx.docks().showEditor());
        editor.setAccelerator(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_E,
                java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.ALT_DOWN_MASK)); // DEC-031: product-only keys are Ctrl+Alt
        m.add(editor);
        m.add(panelItem("Log", () -> ctx.docks().showLog()));
        m.add(panelItem("Activity", () -> ctx.docks().showActivity()));
        m.addSeparator();
        m.add(panelItem("Reset layout", () -> workspace.resetLayout()));
        m.add(panelItem("Focus command bar", () -> ctx.focusCommandBar()));
        m.add(panelItem("Fiji windows...", () -> ctx.status().windowsButton().doClick()));
        return m;
    }

    private static JMenuItem panelItem(String label, Runnable r) {
        JMenuItem it = new JMenuItem(label);
        it.addActionListener(e -> r.run());
        return it;
    }

    /** Scientific menus stay visible; they are inert until the engine is ready (UI-19). */
    void setScientificEnabled(boolean enabled) {
        for (JMenu m : scientificMenus) m.setEnabled(enabled);
    }

    List<JMenu> scientificMenus() {
        return scientificMenus;
    }
}
