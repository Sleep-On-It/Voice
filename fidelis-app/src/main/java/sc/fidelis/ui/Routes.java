package sc.fidelis.ui;

import java.util.Map;

import javax.swing.JOptionPane;

import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.ActionRequest.Operations;
import sc.fidelis.engine.api.Enums.Origin;

/**
 * The deliberately short list of Fiji menu commands that the workspace rebinds to its own behaviour
 * (docs D12 section 5.3, DEC-030 as revised). Everything not listed here runs the real IJ1 command
 * through the engine. Labels are unchanged; only the implementation of these items differs because
 * the IJ1 version would act on hidden windows or the hidden Fiji main window.
 *
 * <ul>
 *   <li>File: Open..., Close (session operations with the workspace's own file chooser and prompt)</li>
 *   <li>Edit: Undo (the engine's multi-step undo instead of IJ1's single slot)</li>
 *   <li>Image &gt; Zoom: In, Out, Original Scale, View 100%, To Selection, Scale to Fit, Set..., Maximize
 *       (the host drives the viewport; native zoom would re-pack the hidden window)</li>
 *   <li>Image &gt; Adjust &gt; Threshold... and Brightness/Contrast...: open the Threshold and B&amp;C dock tabs instead of the
 *       IJ1 ThresholdAdjuster and ContrastAdjuster windows</li>
 *   <li>Analyze &gt; Tools &gt; ROI Manager...: opens the ROI list dock instead of the IJ1 frame</li>
 *   <li>Plugins &gt; Macros &gt; Record...: opens the History and macro dock instead of the IJ1 Recorder</li>
 *   <li>Plugins &gt; Utilities &gt; Find Commands...: focuses the command bar</li>
 *   <li>Window: Show All, Main Window, Put Behind, Cascade, Tile (these manage IJ1 windows)</li>
 * </ul>
 */
final class Routes {
    private Routes() {}

    /** @return true when the command was handled by the workspace. */
    static boolean handle(UiContext ctx, String commandId, Origin origin) {
        String id = commandId.trim();
        DocumentView v = ctx.documents().focusedView();
        switch (id) {
            case "ij1:File>Open..." -> ctx.openFileDialog();
            case "ij1:File>Close" -> {
                if (ctx.focusedId() != null) ctx.closeDocument(ctx.focusedId());
                else ctx.notice("No image is open", false);
            }
            case "ij1:Edit>Undo" -> ctx.submit(
                    ActionRequest.direct(origin, Operations.UNDO, Map.of(), ctx.target()), "Undo");
            // The engine keeps one ROI list per document and loads it into IJ1's ROI Manager only around
            // Analyze Particles; the IJ1 command would add to the raw singleton, invisible to the document's
            // list. The ROI panel's Add button already uses ROI_LIST_ADD, so the menu item and its Fiji
            // shortcut (Ctrl+T) do the same (#16 shortcut parity).
            case "ij1:Edit>Selection>Add to Manager" -> {
                if (ctx.target() == null) ctx.notice("No image is open", false);
                else ctx.submit(ActionRequest.direct(origin, Operations.ROI_LIST_ADD, Map.of(), ctx.target()), "Add to Manager");
            }
            case "ij1:Image>Zoom>In [+]" -> zoom(ctx, v, () -> v.zoomStep(true, null));
            case "ij1:Image>Zoom>Out [-]" -> zoom(ctx, v, () -> v.zoomStep(false, null));
            case "ij1:Image>Zoom>Original Scale" -> zoom(ctx, v, v == null ? null : v::originalScale);
            case "ij1:Image>Zoom>View 100%" -> zoom(ctx, v, v == null ? null : v::actualPixels);
            case "ij1:Image>Zoom>To Selection" -> zoom(ctx, v, v == null ? null : v::zoomToSelection);
            case "ij1:Image>Zoom>Scale to Fit" -> zoom(ctx, v, v == null ? null : v::fit);
            case "ij1:Image>Zoom>Set..." -> zoom(ctx, v, () -> {
                String s = JOptionPane.showInputDialog(ctx.frame, "Zoom (percent):", String.valueOf(Math.round(v.magnification() * 100)));
                if (s == null) return;
                try {
                    v.setMagnificationPercent(Double.parseDouble(s.trim().replace("%", "")));
                } catch (NumberFormatException ex) {
                    ctx.notice("Not a number: " + s, true);
                }
            });
            case "ij1:Image>Zoom>Maximize" -> ctx.docks().collapseAll();
            case "ij1:Image>Adjust>Threshold..." -> ctx.docks().showThreshold();
            case "ij1:Image>Adjust>Brightness/Contrast..." -> ctx.docks().showDisplay();
            case "ij1:Analyze>Tools>ROI Manager..." -> ctx.docks().showRoi();
            case "ij1:Plugins>Macros>Record..." -> ctx.docks().showHistory();
            case "ij1:File>New>Script...", "ij1:Plugins>New>Macro", "ij1:Plugins>Macros>Edit..." -> ctx.docks().showEditor();
            case "ij1:Plugins>Utilities>Find Commands...", "ij1:Window>Show All", "ij1:Window>Main Window [enter]" -> ctx.focusCommandBar();
            case "ij1:Window>Put Behind [tab]" -> ctx.documents().nextTab();
            case "ij1:Window>Cascade", "ij1:Window>Tile" ->
                    ctx.notice("Documents are tabs in this workspace; Tile and Cascade are not needed", false);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void zoom(UiContext ctx, DocumentView v, Runnable r) {
        if (v == null || r == null) ctx.notice("Zoom needs an open image", false);
        else r.run();
    }
}
