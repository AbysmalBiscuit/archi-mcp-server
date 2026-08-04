package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelComponent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.model.UiThreadDispatcher;
import net.vheerden.archi.mcp.model.ViewNavigator;
import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.NavigateViewResultDto;
import net.vheerden.archi.mcp.response.dto.OpenViewResultDto;

/**
 * Tests for {@link ViewNavigationHandler} — the {@code open-view} and {@code navigate-view} tools.
 */
public class ViewNavigationHandlerTest {

    private ObjectMapper objectMapper;
    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private StubNavigator navigator;
    private ViewNavigationHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        navigator = new StubNavigator();
        handler = new ViewNavigationHandler(
                new StubAccessor(true, new StubDispatcher(navigator)), formatter, registry, null);
        handler.registerTools();
    }

    // ---- Registration ----

    @Test
    public void shouldRegisterBothTools() {
        assertEquals(2, registry.getToolSpecifications().size());
        assertTrue(toolNames().contains("open-view"));
        assertTrue(toolNames().contains("navigate-view"));
    }

    @Test
    public void shouldRequireViewIdOnBothTools() {
        assertTrue(schemaOf("open-view").required().contains("viewId"));
        assertTrue(schemaOf("navigate-view").required().contains("viewId"));
    }

    @Test
    public void shouldOfferNavigationParameters() {
        Map<String, Object> props = schemaOf("navigate-view").properties();
        assertTrue(props.containsKey("selectObjectIds"));
        assertTrue(props.containsKey("zoom"));
        assertTrue(props.containsKey("zoomMode"));
        assertTrue(props.containsKey("scrollX"));
        assertTrue(props.containsKey("scrollY"));
    }

    // ---- open-view ----

    @Test
    public void shouldOpenTheRequestedView() throws Exception {
        Map<String, Object> result = getResult(
                parseResult(handler.handleOpenView(null, request("open-view", Map.of("viewId", "view-1")))));

        assertEquals("view-1", navigator.openedViewId);
        assertTrue("activate defaults to true", navigator.openedActivate);
        assertEquals("view-1", result.get("viewId"));
        assertEquals("Layered Overview", result.get("viewName"));
    }

    @Test
    public void shouldHonourActivateFalse() throws Exception {
        handler.handleOpenView(null,
                request("open-view", Map.of("viewId", "view-1", "activate", false)));

        assertFalse(navigator.openedActivate);
    }

    @Test
    public void shouldRejectOpenWithoutViewId() throws Exception {
        McpSchema.CallToolResult raw = handler.handleOpenView(null, request("open-view", Map.of()));

        assertTrue(raw.isError());
        assertEquals("INVALID_PARAMETER", getError(parseResult(raw)).get("code"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenNoModelIsOpen() throws Exception {
        ViewNavigationHandler noModel = new ViewNavigationHandler(
                new StubAccessor(false, new StubDispatcher(navigator)), formatter,
                new CommandRegistry(), null);

        McpSchema.CallToolResult raw = noModel.handleOpenView(null,
                request("open-view", Map.of("viewId", "view-1")));

        assertTrue(raw.isError());
        assertEquals("MODEL_NOT_LOADED", getError(parseResult(raw)).get("code"));
    }

    @Test
    public void shouldSurfaceViewNotFound() throws Exception {
        navigator.failure = new ModelAccessException(
                "View not found: ghost", ErrorCode.VIEW_NOT_FOUND, null, "Use get-views", null);

        McpSchema.CallToolResult raw = handler.handleOpenView(null,
                request("open-view", Map.of("viewId", "ghost")));

        assertTrue(raw.isError());
        assertEquals("VIEW_NOT_FOUND", getError(parseResult(raw)).get("code"));
    }

    // ---- navigate-view ----

    @Test
    public void shouldPassNavigationParametersThrough() throws Exception {
        Map<String, Object> args = Map.of(
                "viewId", "view-1",
                "selectObjectIds", List.of("vo-1", "vo-2"),
                "zoom", 1.5,
                "scrollX", 240,
                "scrollY", 360);

        Map<String, Object> result = getResult(
                parseResult(handler.handleNavigateView(null, request("navigate-view", args))));

        assertEquals("view-1", navigator.navigatedViewId);
        assertEquals(List.of("vo-1", "vo-2"), navigator.navigatedObjectIds);
        assertEquals(Double.valueOf(1.5), navigator.navigatedZoom);
        assertEquals(Integer.valueOf(240), navigator.navigatedScrollX);
        assertEquals(Integer.valueOf(360), navigator.navigatedScrollY);
        assertEquals(1.5, (Double) result.get("zoom"), 0.0001);
    }

    @Test
    public void shouldPassZoomModeThrough() throws Exception {
        handler.handleNavigateView(null, request("navigate-view",
                Map.of("viewId", "view-1", "zoomMode", "fit")));

        assertEquals("fit", navigator.navigatedZoomMode);
    }

    @Test
    public void shouldSurfaceInvalidNavigationParameters() throws Exception {
        navigator.failure = new ModelAccessException(
                "Pass zoom or zoomMode, not both", ErrorCode.INVALID_PARAMETER, null, "Pick one", null);

        McpSchema.CallToolResult raw = handler.handleNavigateView(null,
                request("navigate-view", Map.of("viewId", "view-1", "zoom", 2.0)));

        assertTrue(raw.isError());
        assertEquals("INVALID_PARAMETER", getError(parseResult(raw)).get("code"));
    }

    @Test
    public void shouldSurfaceMissingWorkbench() throws Exception {
        navigator.failure = new ModelAccessException(
                "No Archi editor UI available", ErrorCode.VIEW_EDITOR_UNAVAILABLE, null,
                "Run this against a running Archi", null);

        McpSchema.CallToolResult raw = handler.handleOpenView(null,
                request("open-view", Map.of("viewId", "view-1")));

        assertTrue(raw.isError());
        assertEquals("VIEW_EDITOR_UNAVAILABLE", getError(parseResult(raw)).get("code"));
    }

    // ---- Helpers ----

    private List<String> toolNames() {
        return registry.getToolSpecifications().stream().map(s -> s.tool().name()).toList();
    }

    private McpSchema.JsonSchema schemaOf(String toolName) {
        return registry.getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals(toolName))
                .findFirst().orElseThrow().tool().inputSchema();
    }

    private McpSchema.CallToolRequest request(String name, Map<String, Object> args) {
        return McpSchema.CallToolRequest.builder().name(name).arguments(args).build();
    }

    private Map<String, Object> parseResult(McpSchema.CallToolResult result) throws Exception {
        String content = ((McpSchema.TextContent) result.content().get(0)).text();
        return objectMapper.readValue(content, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getResult(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("result");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getError(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("error");
    }

    // ---- Test doubles ----

    private static class StubAccessor extends BaseTestAccessor {

        private final MutationDispatcher dispatcher;

        StubAccessor(boolean modelLoaded, MutationDispatcher dispatcher) {
            super(modelLoaded);
            this.dispatcher = dispatcher;
        }

        @Override
        public MutationDispatcher getMutationDispatcher() {
            return dispatcher;
        }

        @Override
        public String getModelVersion() {
            return "v1";
        }
    }

    private static class StubDispatcher extends MutationDispatcher {

        private final ViewNavigator navigator;

        StubDispatcher(ViewNavigator navigator) {
            super(() -> null);
            this.navigator = navigator;
        }

        @Override
        public ViewNavigator getViewNavigator() {
            return navigator;
        }
    }

    private static class StubNavigator extends ViewNavigator {

        String openedViewId;
        boolean openedActivate;
        String navigatedViewId;
        List<String> navigatedObjectIds;
        Double navigatedZoom;
        String navigatedZoomMode;
        Integer navigatedScrollX;
        Integer navigatedScrollY;
        RuntimeException failure;

        StubNavigator() {
            super(() -> null, DIRECT_UI_THREAD, NO_EDITORS);
        }

        @Override
        public OpenViewResultDto openView(String viewId, boolean activate) {
            openedViewId = viewId;
            openedActivate = activate;
            if (failure != null) {
                throw failure;
            }
            return new OpenViewResultDto(viewId, "Layered Overview", false, activate);
        }

        @Override
        public NavigateViewResultDto navigateView(String viewId, List<String> objectIds,
                Double zoom, String zoomMode, Integer scrollX, Integer scrollY) {
            navigatedViewId = viewId;
            navigatedObjectIds = objectIds;
            navigatedZoom = zoom;
            navigatedZoomMode = zoomMode;
            navigatedScrollX = scrollX;
            navigatedScrollY = scrollY;
            if (failure != null) {
                throw failure;
            }
            return new NavigateViewResultDto(viewId, "Layered Overview",
                    objectIds == null ? List.of() : objectIds,
                    zoom == null ? 1.0 : zoom,
                    scrollX == null ? 0 : scrollX,
                    scrollY == null ? 0 : scrollY);
        }
    }

    private static final UiThreadDispatcher DIRECT_UI_THREAD = new UiThreadDispatcher() {
        @Override
        public <T> T onUiThread(Callable<T> work) throws MutationException {
            try {
                return work.call();
            } catch (Exception e) {
                throw new MutationException("failed on UI thread", e);
            }
        }
    };

    private static final ViewNavigator.DiagramEditors NO_EDITORS = new ViewNavigator.DiagramEditors() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean open(IDiagramModel view, boolean activate) {
            return false;
        }

        @Override
        public void select(IDiagramModel view, List<IDiagramModelComponent> objects) {
            // never reached — the stub overrides both navigator entry points
        }

        @Override
        public ViewNavigator.Viewport navigate(IDiagramModel view, Double zoom, String zoomMode,
                Integer scrollX, Integer scrollY) {
            return new ViewNavigator.Viewport(1.0, 0, 0);
        }
    };
}
