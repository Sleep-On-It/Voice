package sc.fidelis.engine;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import ij.ImagePlus;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import sc.fidelis.engine.api.DocumentId;

/** Engine-side state of one registered image document. */
final class Doc {
    final DocumentId id;
    final ImagePlus imp;
    volatile ImageWindow win;
    final Path sourcePath;
    final String sourceSha256;

    /** Engine-owned ROI list (mirrors the ROI Manager singleton around actions). Guarded by itself. */
    final List<Roi> roiList = new ArrayList<>();

    /** Snapshot undo stack, newest first. Guarded by itself. */
    final Deque<Snapshots.Snapshot> undo = new ArrayDeque<>();
    /** Actions undone and available to REDO (their request, plus recorded state for a ROI copy), newest first. Guarded by {@code undo}. */
    final Deque<Redo> redo = new ArrayDeque<>();

    Doc(DocumentId id, ImagePlus imp, Path sourcePath, String sourceSha256) {
        this.id = id;
        this.imp = imp;
        this.sourcePath = sourcePath;
        this.sourceSha256 = sourceSha256;
    }
}
