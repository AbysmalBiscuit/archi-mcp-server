package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelComponent;
import com.archimatetool.model.IDiagramModelGroup;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.NavigateViewResultDto;
import net.vheerden.archi.mcp.response.dto.OpenViewResultDto;

/**
 * Tests for {@link ViewNavigator} — the collaborator behind the {@code open-view} and
 * {@code navigate-view} tools.
 */
public class ViewNavigatorTest {

    private IArchimateModel model;
    private IArchimateDiagramModel view;
    private FakeDiagramEditors editors;

    @Before
    public void setUp() {
        model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setName("Nav Test");
        model.setDefaults();

        view = IArchimateFactory.eINSTANCE.createArchimateDiagramModel();
        view.setId("view-1");
        view.setName("Layered Overview");
        model.getFolder(FolderType.DIAGRAMS).getElements().add(view);

        IDiagramModelGroup group = IArchimateFactory.eINSTANCE.createDiagramModelGroup();
        group.setId("group-1");
        group.setBounds(10, 10, 400, 300);
        view.getChildren().add(group);

        IArchimateElement actor = IArchimateFactory.eINSTANCE.createBusinessActor();
        actor.setId("elem-1");
        actor.setName("Customer");
        IDiagramModelArchimateObject nested = IArchimateFactory.eINSTANCE.createDiagramModelArchimateObject();
        nested.setId("vo-1");
        nested.setArchimateElement(actor);
        nested.setBounds(20, 20, 120, 55);
        group.getChildren().add(nested);

        editors = new FakeDiagramEditors();
    }

    // ---- open-view ----

    @Test
    public void shouldOpenTheViewEditor() {
        OpenViewResultDto result = navigator().openView("view-1", true);

        assertEquals("view-1", result.viewId());
        assertEquals("Layered Overview", result.viewName());
        assertFalse(result.alreadyOpen());
        assertTrue(result.activated());
        assertEquals(List.of("view-1"), editors.opened);
        assertTrue(editors.lastActivate);
    }

    @Test
    public void shouldReportAnEditorThatWasAlreadyOpen() {
        editors.alreadyOpen = true;

        OpenViewResultDto result = navigator().openView("view-1", true);

        assertTrue(result.alreadyOpen());
    }

    @Test
    public void shouldOpenWithoutStealingFocus_whenActivateIsFalse() {
        OpenViewResultDto result = navigator().openView("view-1", false);

        assertFalse(result.activated());
        assertFalse(editors.lastActivate);
    }

    @Test
    public void shouldFailWithViewNotFound_whenViewIdIsUnknown() {
        try {
            navigator().openView("no-such-view", true);
            fail("expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_NOT_FOUND, e.getErrorCode());
        }
    }

    @Test
    public void shouldFailWithNoModelLoaded_whenNoModelIsActive() {
        ViewNavigator navigator = new ViewNavigator(() -> null, directUiThread(), editors);

        try {
            navigator.openView("view-1", true);
            fail("expected NoModelLoadedException");
        } catch (NoModelLoadedException expected) {
            // surfaced as MODEL_NOT_LOADED by the tool
        }
    }

    @Test
    public void shouldFail_whenNoWorkbenchIsAvailable() {
        editors.available = false;

        try {
            navigator().openView("view-1", true);
            fail("expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_EDITOR_UNAVAILABLE, e.getErrorCode());
        }
    }

    // ---- navigate-view ----

    @Test
    public void shouldSelectAVisualObjectById() {
        NavigateViewResultDto result = navigator()
                .navigateView("view-1", List.of("vo-1"), null, null, null, null);

        assertEquals(List.of("vo-1"), result.selectedObjectIds());
        assertEquals(List.of("vo-1"), idsOf(editors.selected));
    }

    @Test
    public void shouldSelectEveryVisualInstanceOfAnElement() {
        NavigateViewResultDto result = navigator()
                .navigateView("view-1", List.of("elem-1"), null, null, null, null);

        assertEquals("an element id resolves to its visual on the view",
                List.of("vo-1"), result.selectedObjectIds());
    }

    @Test
    public void shouldSelectNestedAndTopLevelObjectsTogether() {
        NavigateViewResultDto result = navigator()
                .navigateView("view-1", List.of("group-1", "vo-1"), null, null, null, null);

        assertEquals(List.of("group-1", "vo-1"), result.selectedObjectIds());
    }

    @Test
    public void shouldFailWithViewObjectNotFound_whenAnIdIsNotOnTheView() {
        try {
            navigator().navigateView("view-1", List.of("vo-1", "ghost"), null, null, null, null);
            fail("expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.VIEW_OBJECT_NOT_FOUND, e.getErrorCode());
            assertTrue("the offending id must be named: " + e.getMessage(),
                    e.getMessage().contains("ghost"));
            assertTrue("nothing should be selected on a rejected request", editors.selected.isEmpty());
        }
    }

    @Test
    public void shouldApplyAnAbsoluteZoom() {
        editors.viewport = new ViewNavigator.Viewport(1.5, 0, 0);

        NavigateViewResultDto result = navigator()
                .navigateView("view-1", null, 1.5, null, null, null);

        assertEquals(1.5, result.zoom(), 0.0001);
        assertEquals(Double.valueOf(1.5), editors.lastZoom);
    }

    @Test
    public void shouldApplyAZoomMode() {
        NavigateViewResultDto result = navigator()
                .navigateView("view-1", null, null, "fit", null, null);

        assertEquals("fit", editors.lastZoomMode);
        assertEquals(1.0, result.zoom(), 0.0001);
    }

    @Test
    public void shouldRejectAnUnknownZoomMode() {
        try {
            navigator().navigateView("view-1", null, null, "teleport", null, null);
            fail("expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void shouldRejectZoomAndZoomModeTogether() {
        try {
            navigator().navigateView("view-1", null, 2.0, "fit", null, null);
            fail("expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void shouldRejectANonPositiveZoom() {
        try {
            navigator().navigateView("view-1", null, 0.0, null, null, null);
            fail("expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.INVALID_PARAMETER, e.getErrorCode());
        }
    }

    @Test
    public void shouldScrollToACoordinate() {
        editors.viewport = new ViewNavigator.Viewport(1.0, 240, 360);

        NavigateViewResultDto result = navigator()
                .navigateView("view-1", null, null, null, 240, 360);

        assertEquals(Integer.valueOf(240), editors.lastScrollX);
        assertEquals(Integer.valueOf(360), editors.lastScrollY);
        assertEquals(240, result.scrollX());
        assertEquals(360, result.scrollY());
    }

    @Test
    public void shouldReportTheViewport_whenNothingIsRequested() {
        editors.viewport = new ViewNavigator.Viewport(0.75, 10, 20);

        NavigateViewResultDto result = navigator()
                .navigateView("view-1", null, null, null, null, null);

        assertTrue(result.selectedObjectIds().isEmpty());
        assertEquals(0.75, result.zoom(), 0.0001);
        assertEquals(10, result.scrollX());
    }

    @Test
    public void shouldOpenTheViewBeforeNavigating() {
        navigator().navigateView("view-1", List.of("vo-1"), null, null, null, null);

        assertEquals("navigation must land on an open editor", List.of("view-1"), editors.opened);
    }

    // ---- Helpers ----

    private ViewNavigator navigator() {
        return new ViewNavigator(() -> model, directUiThread(), editors);
    }

    private static UiThreadDispatcher directUiThread() {
        return new UiThreadDispatcher() {
            @Override
            public <T> T onUiThread(Callable<T> work) throws MutationException {
                try {
                    return work.call();
                } catch (Exception e) {
                    throw new MutationException("failed on UI thread", e);
                }
            }
        };
    }

    private static List<String> idsOf(List<IDiagramModelComponent> components) {
        List<String> ids = new ArrayList<>();
        for (IDiagramModelComponent component : components) {
            ids.add(component.getId());
        }
        return ids;
    }

    /** Stands in for Archi's diagram editors without a running workbench. */
    private static class FakeDiagramEditors implements ViewNavigator.DiagramEditors {

        boolean available = true;
        boolean alreadyOpen;
        boolean lastActivate;
        List<String> opened = new ArrayList<>();
        List<IDiagramModelComponent> selected = new ArrayList<>();
        ViewNavigator.Viewport viewport = new ViewNavigator.Viewport(1.0, 0, 0);
        Double lastZoom;
        String lastZoomMode;
        Integer lastScrollX;
        Integer lastScrollY;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean open(IDiagramModel view, boolean activate) {
            opened.add(view.getId());
            lastActivate = activate;
            return alreadyOpen;
        }

        @Override
        public void select(IDiagramModel view, List<IDiagramModelComponent> objects) {
            selected.addAll(objects);
        }

        @Override
        public ViewNavigator.Viewport navigate(IDiagramModel view, Double zoom, String zoomMode,
                Integer scrollX, Integer scrollY) {
            lastZoom = zoom;
            lastZoomMode = zoomMode;
            lastScrollX = scrollX;
            lastScrollY = scrollY;
            return viewport;
        }
    }
}
