package net.vheerden.archi.mcp.model;

import java.util.List;

import org.eclipse.draw2d.geometry.Point;
import org.eclipse.gef.editparts.ZoomManager;
import org.eclipse.ui.PlatformUI;

import com.archimatetool.editor.diagram.IDiagramModelEditor;
import com.archimatetool.editor.ui.services.EditorManager;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelComponent;

/**
 * {@link ViewNavigator.DiagramEditors} backed by the running Archi workbench.
 *
 * <p>Every method here must be called on the SWT UI thread — {@link ViewNavigator} owns that hop.
 * The methods are deliberately forgiving: a view whose editor has no graphical viewer yet (or was
 * closed underneath us) leaves the camera where it is rather than throwing, because a navigation
 * request is a hint about what to look at, not a mutation to guarantee.</p>
 *
 * <p><strong>Layer 3 (Model Boundary):</strong> imports GEF, draw2d, and the Archi editor UI.</p>
 */
class WorkbenchDiagramEditors implements ViewNavigator.DiagramEditors {

    private static final ViewNavigator.Viewport UNKNOWN = new ViewNavigator.Viewport(1.0, 0, 0);

    @Override
    public boolean available() {
        return PlatformUI.isWorkbenchRunning();
    }

    @Override
    public boolean open(IDiagramModel view, boolean activate) {
        boolean alreadyOpen = EditorManager.getDiagramEditorReferences(view).length > 0;
        EditorManager.openDiagramEditor(view, activate);
        return alreadyOpen;
    }

    @Override
    public void select(IDiagramModel view, List<IDiagramModelComponent> objects) {
        IDiagramModelEditor editor = EditorManager.openDiagramEditor(view, true);
        if (editor != null) {
            editor.selectObjects(objects.toArray());
        }
    }

    @Override
    public ViewNavigator.Viewport navigate(IDiagramModel view, Double zoom, String zoomMode,
            Integer scrollX, Integer scrollY) {
        IDiagramModelEditor editor = EditorManager.openDiagramEditor(view, true);
        if (editor == null) {
            return UNKNOWN;
        }
        ZoomManager zoomManager = editor.getAdapter(ZoomManager.class);
        if (zoomManager == null) {
            return UNKNOWN;
        }

        if (zoomMode != null) {
            applyZoomMode(zoomManager, zoomMode);
        } else if (zoom != null) {
            zoomManager.setZoom(clamp(zoom, zoomManager.getMinZoom(), zoomManager.getMaxZoom()));
        }

        if (scrollX != null || scrollY != null) {
            Point current = zoomManager.getViewport().getViewLocation();
            zoomManager.setViewLocation(new Point(
                    scrollX != null ? scrollX : current.x,
                    scrollY != null ? scrollY : current.y));
        }

        Point settled = zoomManager.getViewport().getViewLocation();
        return new ViewNavigator.Viewport(zoomManager.getZoom(), settled.x, settled.y);
    }

    private static void applyZoomMode(ZoomManager zoomManager, String zoomMode) {
        switch (zoomMode) {
            case "fit" -> zoomManager.setZoomAsText(ZoomManager.FIT_ALL);
            case "fit-width" -> zoomManager.setZoomAsText(ZoomManager.FIT_WIDTH);
            case "fit-height" -> zoomManager.setZoomAsText(ZoomManager.FIT_HEIGHT);
            case "actual" -> zoomManager.setZoom(1.0);
            default -> throw new IllegalArgumentException("Unknown zoomMode: " + zoomMode);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
