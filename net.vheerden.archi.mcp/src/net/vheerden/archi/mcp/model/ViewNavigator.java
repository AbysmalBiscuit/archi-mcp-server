package net.vheerden.archi.mcp.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelComponent;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IDiagramModelArchimateComponent;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.NavigateViewResultDto;
import net.vheerden.archi.mcp.response.dto.OpenViewResultDto;

/**
 * Drives Archi's diagram editors: opens a view and moves the camera within it.
 *
 * <p>Nothing here changes the model. It changes what the human is looking at, which is how an
 * agent points at its own work: open the view it just composed, select the objects it touched,
 * and frame them.</p>
 *
 * <p><strong>Layer 3 (Model Boundary):</strong> imports {@code com.archimatetool.model.*}. The
 * workbench and GEF calls live behind {@link DiagramEditors} so the resolution and validation
 * logic here stays exercisable headlessly.</p>
 */
public class ViewNavigator {

    private static final Logger logger = LoggerFactory.getLogger(ViewNavigator.class);

    /** Zoom presets addressable by name, mapping onto GEF's fit modes. */
    public static final Set<String> ZOOM_MODES = Set.of("fit", "fit-width", "fit-height", "actual");

    /** The Archi/GEF editor interactions this class needs. */
    public interface DiagramEditors {

        /** @return whether a workbench with diagram editors is running */
        boolean available();

        /**
         * Opens the editor for {@code view}.
         *
         * @param activate whether to bring the editor to the front
         * @return whether an editor for this view was already open
         */
        boolean open(IDiagramModel view, boolean activate);

        /** Selects {@code objects} in the view's editor and scrolls the first one into sight. */
        void select(IDiagramModel view, List<IDiagramModelComponent> objects);

        /**
         * Applies zoom and/or scroll to the view's editor.
         *
         * @param zoom     absolute zoom factor, or null to leave the zoom alone
         * @param zoomMode one of {@link #ZOOM_MODES}, or null
         * @param scrollX  horizontal scroll target, or null to leave it alone
         * @param scrollY  vertical scroll target, or null to leave it alone
         * @return the viewport the editor settled on
         */
        Viewport navigate(IDiagramModel view, Double zoom, String zoomMode,
                Integer scrollX, Integer scrollY);
    }

    /**
     * Where an editor's camera ended up.
     *
     * @param zoom    zoom factor, where 1.0 is 100%
     * @param scrollX horizontal scroll position in diagram coordinates
     * @param scrollY vertical scroll position in diagram coordinates
     */
    public record Viewport(double zoom, int scrollX, int scrollY) {
    }

    private final Supplier<IArchimateModel> modelSupplier;
    private final UiThreadDispatcher uiThread;
    private final DiagramEditors editors;

    /**
     * Creates a navigator over host Archi's diagram editors.
     *
     * @param modelSupplier supplies the currently active model, or null when none is open
     * @param uiThread      dispatches editor calls onto the SWT UI thread
     */
    public ViewNavigator(Supplier<IArchimateModel> modelSupplier, UiThreadDispatcher uiThread) {
        this(modelSupplier, uiThread, new WorkbenchDiagramEditors());
    }

    /**
     * Creates a navigator over explicit editors.
     *
     * @param editors stands in for the workbench in tests
     */
    public ViewNavigator(Supplier<IArchimateModel> modelSupplier, UiThreadDispatcher uiThread,
            DiagramEditors editors) {
        this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier must not be null");
        this.uiThread = Objects.requireNonNull(uiThread, "uiThread must not be null");
        this.editors = Objects.requireNonNull(editors, "editors must not be null");
    }

    /**
     * Opens a view's diagram editor in Archi.
     *
     * @param viewId   the view to open
     * @param activate whether to bring the editor to the front
     * @return what the open did
     * @throws NoModelLoadedException if no model is open
     * @throws ModelAccessException   if the view is unknown or no editor UI is available
     */
    public OpenViewResultDto openView(String viewId, boolean activate) {
        IDiagramModel view = requireView(viewId);
        requireEditors();

        boolean alreadyOpen = onUiThread(() -> editors.open(view, activate));
        logger.info("Opened view '{}' ({}), activate={}", view.getName(), viewId, activate);
        return new OpenViewResultDto(viewId, view.getName(), alreadyOpen, activate);
    }

    /**
     * Moves around inside a view: selects objects, sets the zoom, and/or scrolls. Opens the view
     * first if it is not already open. With no navigation arguments it simply reports where the
     * editor's camera currently is.
     *
     * @param viewId    the view to navigate
     * @param objectIds visual-object or ArchiMate-concept ids to select and reveal, may be null
     * @param zoom      absolute zoom factor, may be null; mutually exclusive with {@code zoomMode}
     * @param zoomMode  one of {@link #ZOOM_MODES}, may be null
     * @param scrollX   horizontal scroll target in diagram coordinates, may be null
     * @param scrollY   vertical scroll target in diagram coordinates, may be null
     * @return the resulting selection and viewport
     * @throws NoModelLoadedException if no model is open
     * @throws ModelAccessException   if the view or any object is unknown, the arguments conflict,
     *                                or no editor UI is available
     */
    public NavigateViewResultDto navigateView(String viewId, List<String> objectIds,
            Double zoom, String zoomMode, Integer scrollX, Integer scrollY) {
        IDiagramModel view = requireView(viewId);
        requireEditors();
        validateZoomArguments(zoom, zoomMode);

        List<IDiagramModelComponent> targets = resolveTargets(view, objectIds);

        Viewport viewport = onUiThread(() -> {
            editors.open(view, true);
            if (!targets.isEmpty()) {
                editors.select(view, targets);
            }
            return editors.navigate(view, zoom, zoomMode, scrollX, scrollY);
        });

        List<String> selectedIds = new ArrayList<>();
        for (IDiagramModelComponent target : targets) {
            selectedIds.add(target.getId());
        }
        logger.info("Navigated view '{}' ({}): {} object(s) selected, zoom={}",
                view.getName(), viewId, selectedIds.size(), viewport.zoom());

        return new NavigateViewResultDto(viewId, view.getName(), selectedIds,
                viewport.zoom(), viewport.scrollX(), viewport.scrollY());
    }

    // ---- Resolution + validation ----

    private IDiagramModel requireView(String viewId) {
        IArchimateModel model = modelSupplier.get();
        if (model == null) {
            throw new NoModelLoadedException();
        }
        for (IDiagramModel candidate : model.getDiagramModels()) {
            if (candidate.getId().equals(viewId)) {
                return candidate;
            }
        }
        throw new ModelAccessException(
                "View not found: " + viewId,
                ErrorCode.VIEW_NOT_FOUND,
                null,
                "Use get-views to list the view ids in this model.",
                null);
    }

    private void requireEditors() {
        if (!editors.available()) {
            throw new ModelAccessException(
                    "No Archi editor UI is available",
                    ErrorCode.VIEW_EDITOR_UNAVAILABLE,
                    "Opening and navigating views needs the Archi workbench; the server is running "
                            + "without one.",
                    "Run the MCP server inside a running Archi desktop application.",
                    null);
        }
    }

    private void validateZoomArguments(Double zoom, String zoomMode) {
        if (zoom != null && zoomMode != null) {
            throw new ModelAccessException(
                    "Pass either zoom or zoomMode, not both",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Use zoomMode for a fit preset, or zoom for an explicit factor.",
                    null);
        }
        if (zoom != null && zoom <= 0) {
            throw new ModelAccessException(
                    "zoom must be greater than 0 (1.0 = 100%)",
                    ErrorCode.INVALID_PARAMETER,
                    null,
                    "Pass a positive factor, e.g. 0.5 for 50% or 2 for 200%.",
                    null);
        }
        if (zoomMode != null && !ZOOM_MODES.contains(zoomMode)) {
            throw new ModelAccessException(
                    "Unknown zoomMode: " + zoomMode,
                    ErrorCode.INVALID_PARAMETER,
                    "Supported modes: fit, fit-width, fit-height, actual",
                    "Pass one of the supported zoomMode values, or use zoom for an explicit factor.",
                    null);
        }
    }

    /**
     * Resolves each requested id against the view. An id matches either a visual object or
     * connection directly, or an ArchiMate concept, in which case every visual instance of that
     * concept on this view is selected. Order follows the request; duplicates collapse.
     */
    private List<IDiagramModelComponent> resolveTargets(IDiagramModel view, List<String> objectIds) {
        if (objectIds == null || objectIds.isEmpty()) {
            return List.of();
        }
        List<IDiagramModelComponent> components = new ArrayList<>();
        collectComponents(view, components);

        Set<IDiagramModelComponent> resolved = new LinkedHashSet<>();
        for (String id : objectIds) {
            List<IDiagramModelComponent> matches = matchesFor(components, id);
            if (matches.isEmpty()) {
                throw new ModelAccessException(
                        "Not on view '" + view.getName() + "': " + id,
                        ErrorCode.VIEW_OBJECT_NOT_FOUND,
                        null,
                        "Pass a viewObjectId / viewConnectionId from get-view-contents, or the id "
                                + "of an element placed on this view.",
                        null);
            }
            resolved.addAll(matches);
        }
        return new ArrayList<>(resolved);
    }

    private static List<IDiagramModelComponent> matchesFor(
            List<IDiagramModelComponent> components, String id) {
        List<IDiagramModelComponent> matches = new ArrayList<>();
        for (IDiagramModelComponent component : components) {
            if (id.equals(component.getId())) {
                matches.add(component);
            }
        }
        if (!matches.isEmpty()) {
            return matches;
        }
        for (IDiagramModelComponent component : components) {
            if (component instanceof IDiagramModelArchimateComponent archimate) {
                IArchimateConcept concept = archimate.getArchimateConcept();
                if (concept != null && id.equals(concept.getId())) {
                    matches.add(component);
                }
            }
        }
        return matches;
    }

    /** Walks the view's containment tree, collecting every visual object and connection. */
    private static void collectComponents(IDiagramModelContainer container,
            List<IDiagramModelComponent> collected) {
        for (IDiagramModelObject child : container.getChildren()) {
            collected.add(child);
            collectConnections(child, collected);
            if (child instanceof IDiagramModelContainer nested) {
                collectComponents(nested, collected);
            }
        }
    }

    private static void collectConnections(IConnectable source,
            List<IDiagramModelComponent> collected) {
        for (IDiagramModelConnection connection : source.getSourceConnections()) {
            collected.add(connection);
            collectConnections(connection, collected);
        }
    }

    private <T> T onUiThread(java.util.concurrent.Callable<T> work) {
        try {
            return uiThread.onUiThread(work);
        } catch (MutationException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            throw new ModelAccessException(
                    "View navigation failed: " + cause.getMessage(),
                    ErrorCode.INTERNAL_ERROR,
                    null,
                    "Retry; if it persists, check that the view is not in a broken editor state.",
                    null);
        }
    }
}
