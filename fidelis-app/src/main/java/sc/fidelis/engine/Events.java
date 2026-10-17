package sc.fidelis.engine;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongFunction;

import javax.swing.SwingUtilities;

import sc.fidelis.engine.api.EngineEvent;
import sc.fidelis.engine.api.EngineListener;

/**
 * Event bus: a monotonic sequence number is allocated under a lock together with the
 * {@code invokeLater} hand-off, so listeners see events on the EDT in sequence order (D09 §3).
 */
final class Events {
    private final List<EngineListener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private long seq;

    void add(EngineListener l) {
        if (l != null) listeners.add(l);
    }

    void remove(EngineListener l) {
        listeners.remove(l);
    }

    List<EngineListener> snapshot() {
        return List.copyOf(listeners);
    }

    void emit(LongFunction<EngineEvent> factory) {
        synchronized (lock) {
            EngineEvent e = factory.apply(++seq);
            SwingUtilities.invokeLater(() -> {
                for (EngineListener l : listeners) {
                    try {
                        l.onEvent(e);
                    } catch (Throwable t) {
                        System.err.println("[fidelis-engine] listener failed on " + e.getClass().getSimpleName() + ": " + t);
                    }
                }
            });
        }
    }
}
