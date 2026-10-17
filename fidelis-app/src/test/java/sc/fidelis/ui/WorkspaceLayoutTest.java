package sc.fidelis.ui;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import sc.fidelis.testkit.FijiFixture;

/**
 * Demo layout (issue #15): after the real J-12 journey through the workspace, the canvas with its ROIs, the
 * Results (count and at least six columns), the ROI list and the History are showing at once, without
 * overlap, at 1500x940 and at 1920x1080; dividers resize and "Reset layout" restores the defaults. The bodies
 * live in {@link LayoutScenario} so no ij.* class loads before the ImageJ1 patcher has run.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class) // the J-12 state of the first test is what the others lay out
class WorkspaceLayoutTest {
    @BeforeAll
    static void start() throws Exception {
        FijiFixture.imageJ();
        LayoutScenario.start();
    }

    @AfterAll
    static void stop() throws Exception {
        LayoutScenario.stop();
    }

    @Test
    @Order(1)
    void demoPanelsVisibleTogetherAtBothResolutions() throws Exception {
        LayoutScenario.demoPanelsVisibleTogetherAtBothResolutions();
    }

    @Test
    @Order(2)
    void darkThemeKeepsTheDemoLayout() throws Exception {
        LayoutScenario.darkThemeKeepsTheDemoLayout();
    }

    @Test
    @Order(3)
    void dividersResizeAndResetRestoresDefaults() throws Exception {
        LayoutScenario.dividersResizeAndResetRestoresDefaults();
    }
}
