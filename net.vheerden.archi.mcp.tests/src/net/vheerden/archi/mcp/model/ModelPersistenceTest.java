package net.vheerden.archi.mcp.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.Before;
import org.junit.Test;

import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.SaveModelResultDto;

/**
 * Tests for {@link ModelPersistence} — the save collaborator behind the {@code save-model} tool.
 */
public class ModelPersistenceTest {

    private IArchimateModel model;
    private FakeModelStore store;
    private List<String> problems;
    private int uiThreadCalls;

    @Before
    public void setUp() {
        model = IArchimateFactory.eINSTANCE.createArchimateModel();
        model.setName("Persistence Test");
        model.setId("model-persist");
        model.setDefaults();
        model.setFile(new File("C:/models/persistence-test.archimate"));
        store = new FakeModelStore();
        store.dirty = true;
        problems = new ArrayList<>();
        uiThreadCalls = 0;
    }

    @Test
    public void shouldSaveDirtyModelAndReportTheFile() {
        SaveModelResultDto result = persistence().save();

        assertTrue("a dirty model should be written", result.saved());
        assertTrue("wasDirty should report the pre-call state", result.wasDirty());
        assertEquals("Persistence Test", result.modelName());
        assertEquals(model.getFile().getAbsolutePath(), result.filePath());
        assertEquals("the model must be saved exactly once", 1, store.saveCalls);
    }

    @Test
    public void shouldSaveOnTheUiThread() {
        persistence().save();

        assertEquals("saveModel must be dispatched through the UI-thread runner", 1, uiThreadCalls);
    }

    @Test
    public void shouldNotWrite_whenModelIsClean() {
        store.dirty = false;

        SaveModelResultDto result = persistence().save();

        assertFalse("a clean model needs no write", result.saved());
        assertFalse(result.wasDirty());
        assertEquals("no write should reach Archi", 0, store.saveCalls);
        assertEquals(model.getFile().getAbsolutePath(), result.filePath());
    }

    @Test
    public void shouldFailWithNoModelLoaded_whenNoModelIsActive() {
        ModelPersistence persistence = new ModelPersistence(
                () -> null, fakeUiThread(), store, m -> problems);

        try {
            persistence.save();
            fail("expected NoModelLoadedException");
        } catch (NoModelLoadedException expected) {
            // the tool surfaces this as MODEL_NOT_LOADED
        }
    }

    @Test
    public void shouldRefuse_whenModelHasNeverBeenSaved() {
        model.setFile(null);

        try {
            persistence().save();
            fail("expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.MODEL_NEVER_SAVED, e.getErrorCode());
            assertEquals("no write may be attempted without a file", 0, store.saveCalls);
        }
    }

    @Test
    public void shouldRefuse_whenModelChecksFail() {
        problems.add("Object is in wrong folder type: Customer");

        try {
            persistence().save();
            fail("expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.MODEL_CHECK_FAILED, e.getErrorCode());
            assertTrue("the reported problems must reach the agent",
                    e.getDetails().contains("Object is in wrong folder type: Customer"));
            assertEquals("Archi must not be asked to write a model it would reject",
                    0, store.saveCalls);
        }
    }

    @Test
    public void shouldReportMutationFailed_whenArchiThrowsIoException() {
        store.failure = new IOException("disk full");

        try {
            persistence().save();
            fail("expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.MUTATION_FAILED, e.getErrorCode());
            assertTrue("the underlying cause must be reported: " + e.getMessage(),
                    e.getMessage().contains("disk full"));
        }
    }

    @Test
    public void shouldReportMutationFailed_whenArchiDeclinesTheSave() {
        store.saveReturns = false;

        try {
            persistence().save();
            fail("expected ModelAccessException");
        } catch (ModelAccessException e) {
            assertEquals(ErrorCode.MUTATION_FAILED, e.getErrorCode());
        }
    }

    // ---- Helpers ----

    private ModelPersistence persistence() {
        return new ModelPersistence(() -> model, fakeUiThread(), store, m -> problems);
    }

    private UiThreadDispatcher fakeUiThread() {
        return new UiThreadDispatcher() {
            @Override
            public <T> T onUiThread(Callable<T> work) throws MutationException {
                uiThreadCalls++;
                try {
                    return work.call();
                } catch (Exception e) {
                    throw new MutationException("Mutation failed on UI thread", e);
                }
            }
        };
    }

    /** Stands in for host Archi's {@code IEditorModelManager} without a running workbench. */
    private static class FakeModelStore implements ModelPersistence.ModelStore {

        boolean dirty;
        boolean saveReturns = true;
        IOException failure;
        int saveCalls;

        @Override
        public boolean isDirty(IArchimateModel model) {
            return dirty;
        }

        @Override
        public boolean save(IArchimateModel model) throws IOException {
            saveCalls++;
            if (failure != null) {
                throw failure;
            }
            return saveReturns;
        }
    }
}
