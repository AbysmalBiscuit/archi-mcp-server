package net.vheerden.archi.mcp.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import net.vheerden.archi.mcp.model.BaseTestAccessor;
import net.vheerden.archi.mcp.model.ModelAccessException;
import net.vheerden.archi.mcp.model.MutationDispatcher;
import net.vheerden.archi.mcp.registry.CommandRegistry;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.ResponseFormatter;
import net.vheerden.archi.mcp.response.dto.SaveModelResultDto;

/**
 * Tests for {@link PersistenceHandler} — the {@code save-model} MCP tool.
 */
public class PersistenceHandlerTest {

    private ObjectMapper objectMapper;
    private CommandRegistry registry;
    private ResponseFormatter formatter;
    private StubDispatcher dispatcher;
    private PersistenceHandler handler;

    @Before
    public void setUp() {
        objectMapper = new ObjectMapper();
        registry = new CommandRegistry();
        formatter = new ResponseFormatter();
        dispatcher = new StubDispatcher();
        dispatcher.setApprovalModeProvider(() -> false);
        handler = new PersistenceHandler(new StubAccessor(true, dispatcher), formatter, registry, null);
        handler.registerTools();
    }

    // ---- Registration ----

    @Test
    public void shouldRegisterSaveModelTool() {
        assertEquals(1, registry.getToolSpecifications().size());
        assertEquals("save-model", registry.getToolSpecifications().get(0).tool().name());
    }

    @Test
    public void shouldTakeNoRequiredParameters() {
        McpSchema.JsonSchema schema = registry.getToolSpecifications().get(0).tool().inputSchema();
        assertEquals("object", schema.type());
        assertTrue("save-model should require nothing",
                schema.required() == null || schema.required().isEmpty());
    }

    // ---- Happy path ----

    @Test
    public void shouldReportTheWrittenFile() throws Exception {
        dispatcher.result = new SaveModelResultDto(true, true, "Corp EA", "C:/models/corp.archimate");

        Map<String, Object> envelope = callAndParse();

        assertEquals(1, dispatcher.saveCalls);
        Map<String, Object> result = getResult(envelope);
        assertEquals(Boolean.TRUE, result.get("saved"));
        assertEquals(Boolean.TRUE, result.get("wasDirty"));
        assertEquals("Corp EA", result.get("modelName"));
        assertEquals("C:/models/corp.archimate", result.get("filePath"));
    }

    @Test
    public void shouldSucceed_whenThereWasNothingToSave() throws Exception {
        dispatcher.result = new SaveModelResultDto(false, false, "Corp EA", "C:/models/corp.archimate");

        McpSchema.CallToolResult raw = callTool();
        assertFalse("a clean model is not an error", raw.isError());

        Map<String, Object> result = getResult(parseResult(raw));
        assertEquals(Boolean.FALSE, result.get("saved"));
        assertEquals(Boolean.FALSE, result.get("wasDirty"));
    }

    // ---- Guards ----

    @Test
    public void shouldRefuse_whenApprovalModeIsOn() throws Exception {
        dispatcher.setApprovalModeProvider(() -> true);

        McpSchema.CallToolResult raw = callTool();

        assertTrue("a gated save is an error", raw.isError());
        assertEquals("the model must not reach disk while the human owns the gate",
                0, dispatcher.saveCalls);
        Map<String, Object> error = getError(parseResult(raw));
        assertEquals("SAVE_GATED_BY_APPROVAL", error.get("code"));
        assertNotNull("the agent needs a way forward", error.get("suggestedCorrection"));
    }

    @Test
    public void shouldReturnModelNotLoaded_whenNoModelIsOpen() throws Exception {
        PersistenceHandler noModel = new PersistenceHandler(
                new StubAccessor(false, dispatcher), formatter, new CommandRegistry(), null);

        McpSchema.CallToolResult raw = noModel.handleSaveModel(null, request());

        assertTrue(raw.isError());
        assertEquals("MODEL_NOT_LOADED", getError(parseResult(raw)).get("code"));
    }

    @Test
    public void shouldSurfaceNeverSavedRefusal() throws Exception {
        dispatcher.failure = new ModelAccessException(
                "Model 'Corp EA' has never been saved", ErrorCode.MODEL_NEVER_SAVED,
                "no file", "Ask the human to save it once", null);

        McpSchema.CallToolResult raw = callTool();

        assertTrue(raw.isError());
        Map<String, Object> error = getError(parseResult(raw));
        assertEquals("MODEL_NEVER_SAVED", error.get("code"));
        assertEquals("Ask the human to save it once", error.get("suggestedCorrection"));
    }

    @Test
    public void shouldSurfaceModelCheckFailure() throws Exception {
        dispatcher.failure = new ModelAccessException(
                "Model checks failed", ErrorCode.MODEL_CHECK_FAILED,
                "Object is in wrong folder type: Customer", "Fix the reported problems", null);

        McpSchema.CallToolResult raw = callTool();

        assertTrue(raw.isError());
        Map<String, Object> error = getError(parseResult(raw));
        assertEquals("MODEL_CHECK_FAILED", error.get("code"));
        assertEquals("Object is in wrong folder type: Customer", error.get("details"));
    }

    // ---- Helpers ----

    private McpSchema.CallToolRequest request() {
        return McpSchema.CallToolRequest.builder().name("save-model").arguments(Map.of()).build();
    }

    private McpSchema.CallToolResult callTool() {
        return handler.handleSaveModel(null, request());
    }

    private Map<String, Object> callAndParse() throws Exception {
        return parseResult(callTool());
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

        SaveModelResultDto result =
                new SaveModelResultDto(true, true, "Corp EA", "C:/models/corp.archimate");
        RuntimeException failure;
        int saveCalls;

        StubDispatcher() {
            super(() -> null);
        }

        @Override
        public SaveModelResultDto saveModel() {
            saveCalls++;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
