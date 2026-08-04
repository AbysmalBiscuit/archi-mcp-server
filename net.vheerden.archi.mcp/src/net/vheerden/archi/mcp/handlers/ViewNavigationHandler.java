package net.vheerden.archi.mcp.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.ArchiModelAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.model.NoModelLoadedException;
import net.vheerden.archi.mcp.model.ViewNavigator;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.NavigateViewResultDto;
import net.vheerden.archi.mcp.response.dto.OpenViewResultDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for the diagram-editor navigation MCP tools.
 *
 * <p>{@code open-view} puts a view on the human's screen; {@code navigate-view} moves around
 * inside it — selecting objects, zooming, scrolling. Neither changes the model, so neither is
 * gated by approval mode: they change what is <em>shown</em>, not what is stored.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import any EMF, GEF, SWT, or
 * ArchimateTool model types. Editor interaction goes through {@link ViewNavigator}.</p>
 */
public class ViewNavigationHandler {

    private static final Logger logger = LoggerFactory.getLogger(ViewNavigationHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    private final SessionManager sessionManager;

    /**
     * Creates a ViewNavigationHandler with its required dependencies.
     *
     * @param accessor       the model accessor
     * @param formatter      the response formatter for building JSON envelopes
     * @param registry       the command registry for tool registration
     * @param sessionManager the session manager, nullable in tests
     */
    public ViewNavigationHandler(ArchiModelAccessor accessor, ResponseFormatter formatter,
            CommandRegistry registry, SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager;
    }

    /**
     * Registers the open-view and navigate-view tools with the command registry.
     */
    public void registerTools() {
        registry.registerTool(buildOpenViewSpec());
        registry.registerTool(buildNavigateViewSpec());
    }

    // ---- Tool specifications ----

    private McpServerFeatures.SyncToolSpecification buildOpenViewSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", Map.of(
                "type", "string",
                "description", "Identifier of the view to open (from get-views)."));
        properties.put("activate", Map.of(
                "type", "boolean",
                "description", "Bring the editor to the front and give it focus (default: true). "
                        + "Pass false to open the view in the background without interrupting "
                        + "what the human is doing."));

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("open-view")
                .description("[Views] Open a view's diagram editor in Archi so the human can see "
                        + "it. Changes nothing in the model — this is how an agent points at its "
                        + "own work: open the view it just composed or edited. Reports whether an "
                        + "editor for the view was already open. Use navigate-view to select "
                        + "objects, zoom, or scroll once it is open.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleOpenView)
                .build();
    }

    private McpServerFeatures.SyncToolSpecification buildNavigateViewSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("viewId", Map.of(
                "type", "string",
                "description", "Identifier of the view to navigate (from get-views). "
                        + "Opened first if it is not already open."));
        properties.put("selectObjectIds", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Visual objects to select and scroll into sight. Accepts a "
                        + "viewObjectId or viewConnectionId from get-view-contents, or an element "
                        + "id — an element id selects every visual instance of it on this view. "
                        + "An id that is not on the view is rejected with VIEW_OBJECT_NOT_FOUND."));
        properties.put("zoom", Map.of(
                "type", "number",
                "description", "Absolute zoom factor, where 1.0 is 100%. Clamped to the editor's "
                        + "own zoom range; the response reports what it settled on. Mutually "
                        + "exclusive with zoomMode.",
                "exclusiveMinimum", 0));
        properties.put("zoomMode", Map.of(
                "type", "string",
                "description", "Zoom preset: 'fit' (whole diagram), 'fit-width', 'fit-height', or "
                        + "'actual' (100%). Mutually exclusive with zoom.",
                "enum", List.of("fit", "fit-width", "fit-height", "actual")));
        properties.put("scrollX", Map.of(
                "type", "integer",
                "description", "Horizontal scroll target in diagram coordinates — the same "
                        + "coordinate space get-view-contents reports element positions in."));
        properties.put("scrollY", Map.of(
                "type", "integer",
                "description", "Vertical scroll target in diagram coordinates."));

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", properties, List.of("viewId"), null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("navigate-view")
                .description("[Views] Move around inside a view in Archi's editor: select and "
                        + "reveal objects, set the zoom, and/or scroll to a coordinate. Opens the "
                        + "view first if needed. Changes nothing in the model — it changes what "
                        + "the human is looking at, so use it to draw attention to the elements "
                        + "you just created or rearranged. With no navigation arguments it simply "
                        + "reports the editor's current zoom and scroll position.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleNavigateView)
                .build();
    }

    // ---- Handler methods ----

    McpSchema.CallToolResult handleOpenView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling open-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            ViewNavigator navigator = requireNavigator();

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            boolean activate = HandlerUtils.optionalBooleanParam(args, "activate", true);

            OpenViewResultDto result = navigator.openView(viewId, activate);

            List<String> nextSteps = List.of(
                    "Use navigate-view to select objects, zoom, or scroll within this view",
                    "Use get-view-contents to read what is on the view");

            Map<String, Object> envelope = formatter.formatSuccess(
                    result, nextSteps, accessor.getModelVersion(), 1, 1, false);
            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling open-view", e);
            return HandlerUtils.buildInternalError(formatter,
                    "Unexpected error opening view: " + e.getMessage());
        }
    }

    McpSchema.CallToolResult handleNavigateView(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling navigate-view request");
        try {
            HandlerUtils.requireModelLoaded(accessor);
            ViewNavigator navigator = requireNavigator();

            Map<String, Object> args = request.arguments();
            String viewId = HandlerUtils.requireStringParam(args, "viewId");
            List<String> objectIds = optionalStringListParam(args, "selectObjectIds");
            Double zoom = optionalDoubleParam(args, "zoom");
            String zoomMode = HandlerUtils.optionalStringParam(args, "zoomMode");
            Integer scrollX = HandlerUtils.optionalIntegerParam(args, "scrollX");
            Integer scrollY = HandlerUtils.optionalIntegerParam(args, "scrollY");

            NavigateViewResultDto result = navigator.navigateView(
                    viewId, objectIds, zoom, zoomMode, scrollX, scrollY);

            List<String> nextSteps = List.of(
                    "Use export-view to capture what the view now looks like",
                    "Use assess-layout to evaluate the view's layout quality");

            Map<String, Object> envelope = formatter.formatSuccess(
                    result, nextSteps, accessor.getModelVersion(), 1, 1, false);
            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling navigate-view", e);
            return HandlerUtils.buildInternalError(formatter,
                    "Unexpected error navigating view: " + e.getMessage());
        }
    }

    private ViewNavigator requireNavigator() {
        MutationDispatcher dispatcher = accessor.getMutationDispatcher();
        ViewNavigator navigator = (dispatcher != null) ? dispatcher.getViewNavigator() : null;
        if (navigator == null) {
            throw new IllegalStateException("No view navigator available");
        }
        return navigator;
    }

    private static List<String> optionalStringListParam(Map<String, Object> args, String paramName) {
        if (args == null) {
            return null;
        }
        Object value = args.get(paramName);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof String str) || str.isBlank()) {
                throw new ModelAccessException(
                        paramName + "[" + i + "] must be a non-empty string",
                        ErrorCode.INVALID_PARAMETER);
            }
            result.add(str);
        }
        return result;
    }

    private static Double optionalDoubleParam(Map<String, Object> args, String paramName) {
        if (args == null) {
            return null;
        }
        Object value = args.get(paramName);
        return (value instanceof Number n) ? n.doubleValue() : null;
    }
}
