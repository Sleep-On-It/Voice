package sc.fidelis.engine;

import java.util.List;

import ij.gui.Roi;
import sc.fidelis.engine.api.ActionRequest;
import sc.fidelis.engine.api.MeasurementRow;

/**
 * One undone action available to REDO. Most actions are redone by running their request again. A ROI-list copy
 * ({@code ROI_LIST_ADD} with {@code fromDocument}) cannot be: its source is the mask segment_cells closes again, so the
 * redo re-establishes what the undo recorded instead, without the source: the objects the action had added ({@code
 * roisAdded}, clones; {@code replace} says whether they replaced the list) and the Results rows the undo removed
 * ({@code rowsRemoved}, the rows of {@code resultsAction}) and put back ({@code rowsRestored}, the rows that action had
 * cleared). Those three are null when the undo restored no such state.
 */
record Redo(ActionRequest request, List<Roi> roisAdded, boolean replace, List<MeasurementRow> rowsRemoved,
            List<MeasurementRow> rowsRestored, String resultsAction) {

    /** A redo that runs the request again. */
    static Redo of(ActionRequest request) {
        return new Redo(request, null, false, null, null, null);
    }

    /** True when the redo re-establishes recorded state instead of re-running the request. */
    boolean replaysState() {
        return roisAdded != null;
    }
}
