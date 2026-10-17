package sc.fidelis.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import sc.fidelis.testkit.FijiFixture;

/**
 * Fiji keyboard-shortcut parity on a hosted canvas (issue #16, DEC-031): Ctrl+M Measure, Ctrl+T Add to
 * Manager, Ctrl+Shift+A Select None, Ctrl+Shift+T Threshold, Ctrl+Z Undo, Duplicate, + / - zoom and
 * space pan. The expected bindings come from Fiji itself (IJ_Props via {@code ij.Menus} and the live menu
 * bar); the body lives in {@link ShortcutScenario} so no ij.* class loads before the ImageJ1 patcher has run.
 */
class WorkspaceShortcutTest {
    @BeforeAll
    static void start() throws Exception {
        FijiFixture.imageJ();
        ShortcutScenario.start();
    }

    @AfterAll
    static void stop() throws Exception {
        ShortcutScenario.stop();
    }

    @Test
    void fijiShortcutsDoTheSameOnAHostedCanvas() throws Exception {
        ShortcutScenario.fijiShortcutsDoTheSameOnAHostedCanvas();
    }
}
