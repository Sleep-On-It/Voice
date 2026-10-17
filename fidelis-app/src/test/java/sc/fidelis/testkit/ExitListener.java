package sc.fidelis.testkit;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Ends the test JVM deterministically. In a GUI-capable Fiji JVM, {@code System.exit} can deadlock
 * in the SciJava shutdown hook (probe P85), which the JUnit console launcher calls after the run.
 * This listener counts failures, disposes the context with a time bound when the launcher session
 * closes, and halts with 0 (all passed) or 1 (failures).
 *
 * <p>Registered through {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}.
 */
public final class ExitListener implements LauncherSessionListener, TestExecutionListener {
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger finished = new AtomicInteger();

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        // before discovery loads any test class (and, through the verifier, ij.* classes): see FijiFixture.patchEarly
        FijiFixture.patchEarly();
        session.getLauncher().registerTestExecutionListeners(this);
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (id.isTest()) {
            finished.incrementAndGet();
            if (result.getStatus() == TestExecutionResult.Status.FAILED) failed.incrementAndGet();
        }
        if (result.getStatus() == TestExecutionResult.Status.FAILED) {
            // the launcher's failure summary is never printed (this listener halts the JVM first)
            System.out.println("[fidelis-tests] FAILED " + id.getDisplayName());
            result.getThrowable().ifPresent(t -> t.printStackTrace(System.out));
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        System.out.printf("[fidelis-tests] %d tests finished, %d failed%n", finished.get(), failed.get());
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        System.out.flush();
        FijiFixture.disposeBounded(10, TimeUnit.SECONDS);
        System.out.println("[fidelis-tests] halting JVM with exit " + (failed.get() == 0 ? 0 : 1));
        System.out.flush();
        Runtime.getRuntime().halt(failed.get() == 0 ? 0 : 1);
    }
}
