package sc.fidelis.testkit;

import java.util.Set;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Gives every test class the same starting state in the shared test JVM (issue #4): before a class runs,
 * {@link IsolationState#reset} closes what earlier classes left behind (documents, IJ1 images, stale ROI
 * Manager frames, Results rows, leaked dialogs, batch mode) and restores the result-affecting globals to their
 * values at context start; after a class, engine listeners it registered and never removed (a disposed
 * workspace) are removed. Every leak found is printed with the class that left it, so it is reported, not hidden.
 *
 * <p>Registered for all test classes through {@code META-INF/services/org.junit.jupiter.api.extension.Extension};
 * {@code tools/test.py} enables JUnit's extension autodetection. This class loads no {@code ij.*} class: the
 * IJ1 work lives in {@link IsolationState}, which is touched only once {@link FijiFixture} has a live context.
 */
public final class SessionIsolation implements BeforeAllCallback, AfterAllCallback {
    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(SessionIsolation.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        String name = context.getRequiredTestClass().getSimpleName();
        if (FijiFixture.started()) IsolationState.reset(name);
        context.getStore(NS).put("listeners", FijiFixture.started() ? IsolationState.listeners() : Set.of());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (!FijiFixture.started()) return;
        Set<?> before = context.getStore(NS).get("listeners", Set.class);
        IsolationState.removeListenersAddedSince(before == null ? Set.of() : before, context.getRequiredTestClass().getSimpleName());
    }
}
