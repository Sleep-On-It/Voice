package sc.fidelis.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import sc.fidelis.testkit.FijiFixture;

/**
 * Macro editor dock (issue #19): the macro generated from a real J-12 run is edited and run as CODE with
 * the capability confirmation (nothing runs before it, declining runs nothing), reproduces the J-12 numbers
 * and is recorded with its observed child steps; the console shows Log lines and Cancel stops a run. The
 * bodies live in {@link MacroEditorScenario} so no ij.* class loads before the ImageJ1 patcher has run.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkspaceMacroEditorTest {
    @BeforeAll
    static void start() throws Exception {
        FijiFixture.imageJ();
        MacroEditorScenario.start();
    }

    @AfterAll
    static void stop() throws Exception {
        MacroEditorScenario.stop();
    }

    @Test
    @Order(1)
    void exportedJ12MacroRunsInTheEditorAfterConfirmation() throws Exception {
        MacroEditorScenario.exportedJ12MacroRunsInTheEditorAfterConfirmation();
    }

    @Test
    @Order(2)
    void consoleShowsLogLinesAndCancelStopsTheMacro() throws Exception {
        MacroEditorScenario.consoleShowsLogLinesAndCancelStopsTheMacro();
    }
}
