package net.vheerden.archi.mcp.handlers;

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
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ErrorResponse;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.SaveModelResultDto;
import net.vheerden.archi.mcp.session.SessionManager;

/**
 * Handler for the model-persistence MCP tool.
 *
 * <p>Exposes host Archi's save as {@code save-model} so an agent can commit its own work to disk
 * instead of leaving it in memory for the human to notice and save by hand.</p>
 *
 * <p><strong>Approval mode is the boundary.</strong> While the human's gate is on, agent changes
 * queue for review and the human decides when the model reaches disk — so a gated save is refused
 * rather than queued. Turning the gate off is the human granting direct-apply, and that grant
 * covers the write too.</p>
 *
 * <p><strong>Architecture boundary:</strong> This class MUST NOT import any EMF, GEF, SWT, or
 * ArchimateTool model types. Saving goes through {@link MutationDispatcher}.</p>
 */
public class PersistenceHandler {

    private static final Logger logger = LoggerFactory.getLogger(PersistenceHandler.class);

    private final ArchiModelAccessor accessor;
    private final ResponseFormatter formatter;
    private final CommandRegistry registry;
    private final SessionManager sessionManager;

    /**
     * Creates a PersistenceHandler with its required dependencies.
     *
     * @param accessor       the model accessor
     * @param formatter      the response formatter for building JSON envelopes
     * @param registry       the command registry for tool registration
     * @param sessionManager the session manager, nullable in tests
     */
    public PersistenceHandler(ArchiModelAccessor accessor, ResponseFormatter formatter,
            CommandRegistry registry, SessionManager sessionManager) {
        this.accessor = Objects.requireNonNull(accessor, "accessor must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.sessionManager = sessionManager;
    }

    /**
     * Registers the save-model tool with the command registry.
     */
    public void registerTools() {
        registry.registerTool(buildSaveModelSpec());
    }

    // ---- Tool specification ----

    private McpServerFeatures.SyncToolSpecification buildSaveModelSpec() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object", Map.of(), null, null, null, null);

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("save-model")
                .description("[Persistence] Write the open ArchiMate model to its file on disk. "
                        + "Every other mutation tool changes the model in memory only — until "
                        + "save-model runs, the work is unsaved and a crash or a close-without-save "
                        + "loses it. Call it once at the end of a unit of work, not after every "
                        + "edit. Saving does not change the model and does not consume an undo "
                        + "step; undo still rolls back saved changes, and re-saving persists the "
                        + "rollback. Returns saved=false when the model was already clean, which "
                        + "is a success. Refused while the human's Approval Mode is on — the human "
                        + "owns the commit point then — and on a model that has never been saved, "
                        + "because choosing its file is the human's call.")
                .inputSchema(inputSchema)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::handleSaveModel)
                .build();
    }

    // ---- Handler method ----

    McpSchema.CallToolResult handleSaveModel(
            McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        logger.info("Handling save-model request");
        try {
            HandlerUtils.requireModelLoaded(accessor);

            MutationDispatcher dispatcher = accessor.getMutationDispatcher();
            if (dispatcher == null) {
                return HandlerUtils.buildInternalError(formatter,
                        "No mutation dispatcher available — cannot save the model");
            }

            String sessionId = HandlerUtils.extractSessionId(sessionManager, exchange);
            if (dispatcher.isApprovalRequired(sessionId)) {
                ErrorResponse error = new ErrorResponse(
                        ErrorCode.SAVE_GATED_BY_APPROVAL,
                        "Cannot save — approval mode is on.",
                        "While the gate is on the human owns the commit point: changes queue for "
                                + "their review and they decide what reaches disk.",
                        "Ask the human to review the pending changes and save in Archi (Ctrl+S), "
                                + "or to turn off MCP Server > Approval Mode.",
                        null);
                return HandlerUtils.buildResult(
                        formatter.toJsonString(formatter.formatError(error)), true);
            }

            SaveModelResultDto result = dispatcher.saveModel();

            List<String> nextSteps = result.saved()
                    ? List.of("Keep working; call save-model again to persist later changes",
                            "Use undo to roll back a saved change, then save-model to persist the rollback")
                    : List.of("Nothing to do — the file on disk already matches the model");

            Map<String, Object> envelope = formatter.formatSuccess(
                    result, nextSteps, accessor.getModelVersion(), 1, 1, false);

            return HandlerUtils.buildResult(formatter.toJsonString(envelope), false);

        } catch (NoModelLoadedException e) {
            return HandlerUtils.buildModelNotLoadedError(formatter, e);
        } catch (ModelAccessException e) {
            return HandlerUtils.buildModelAccessError(formatter, e);
        } catch (Exception e) {
            logger.error("Unexpected error handling save-model", e);
            return HandlerUtils.buildInternalError(formatter,
                    "Unexpected error during save: " + e.getMessage());
        }
    }
}
