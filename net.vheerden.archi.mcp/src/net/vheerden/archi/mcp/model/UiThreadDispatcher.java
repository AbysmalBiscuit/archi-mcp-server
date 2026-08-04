package net.vheerden.archi.mcp.model;

import java.util.concurrent.Callable;

import net.vheerden.archi.mcp.model.exceptions.MutationException;

/**
 * Runs work on the SWT UI thread and returns its result.
 *
 * <p>Model-layer collaborators that must touch Archi's UI take one of these rather than reaching
 * for {@code Display} themselves, which keeps them exercisable headlessly against a
 * straight-through implementation.</p>
 *
 * <p>The method is generic, so implementations are classes rather than lambdas.</p>
 */
public interface UiThreadDispatcher {

    /**
     * @param work the work to run on the UI thread
     * @return whatever {@code work} returned
     * @throws MutationException if no display is available or the work threw
     */
    <T> T onUiThread(Callable<T> work) throws MutationException;
}
