package net.vheerden.archi.mcp.model;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.model.ModelChecker;
import com.archimatetool.model.IArchimateModel;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.ErrorCode;
import net.vheerden.archi.mcp.response.dto.SaveModelResultDto;

/**
 * Writes the active ArchiMate model to its file on disk.
 *
 * <p>Host Archi's {@code saveModel} is dialog-happy: it opens a modal Save As prompt when the
 * model has no file, and a modal error dialog when its model checker rejects the model. Either
 * would block the calling MCP request on the UI thread until a human clicks something. Both
 * conditions are therefore detected here <em>first</em> and reported as structured errors, so an
 * agent-initiated save either writes the file or returns an answer — it never parks on a dialog.</p>
 *
 * <p><strong>Layer 3 (Model Boundary):</strong> imports {@code com.archimatetool.editor.model.*}
 * and {@code com.archimatetool.model.*}. No handler may import these types.</p>
 */
public class ModelPersistence {

    private static final Logger logger = LoggerFactory.getLogger(ModelPersistence.class);

    /**
     * The two host-Archi persistence calls this class needs, isolated behind an interface so the
     * save logic is testable without a running workbench.
     */
    public interface ModelStore {

        /** @return whether {@code model} has changes not yet written to its file */
        boolean isDirty(IArchimateModel model);

        /**
         * Writes {@code model} to its file.
         *
         * @return whether the write happened
         * @throws IOException if the write fails
         */
        boolean save(IArchimateModel model) throws IOException;
    }

    /**
     * Reports the problems Archi's model checker finds. An empty list means host Archi will
     * accept the model; anything else is what its error dialog would have shown.
     */
    @FunctionalInterface
    public interface ModelValidator {
        List<String> problems(IArchimateModel model);
    }

    private final Supplier<IArchimateModel> modelSupplier;
    private final UiThreadDispatcher uiThread;
    private final ModelStore store;
    private final ModelValidator validator;

    /**
     * Creates a persistence collaborator over host Archi's editor model manager.
     *
     * @param modelSupplier supplies the currently active model, or null when none is open
     * @param uiThread      dispatches the write onto the SWT UI thread
     */
    public ModelPersistence(Supplier<IArchimateModel> modelSupplier, UiThreadDispatcher uiThread) {
        this(modelSupplier, uiThread, new EditorModelManagerStore(), ModelPersistence::checkWithArchi);
    }

    /**
     * Test constructor taking explicit collaborators.
     *
     * @param store     stands in for the editor model manager
     * @param validator stands in for Archi's model checker
     */
    ModelPersistence(Supplier<IArchimateModel> modelSupplier, UiThreadDispatcher uiThread,
            ModelStore store, ModelValidator validator) {
        this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier must not be null");
        this.uiThread = Objects.requireNonNull(uiThread, "uiThread must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    /**
     * Writes the active model to its file.
     *
     * @return what the save did, including whether a write was needed at all
     * @throws NoModelLoadedException if no model is open
     * @throws ModelAccessException   if the model has no file, fails Archi's checks, or the
     *                                write itself fails
     */
    public SaveModelResultDto save() {
        IArchimateModel model = modelSupplier.get();
        if (model == null) {
            throw new NoModelLoadedException();
        }

        File file = model.getFile();
        if (file == null) {
            throw new ModelAccessException(
                    "Model '" + model.getName() + "' has never been saved",
                    ErrorCode.MODEL_NEVER_SAVED,
                    "The model exists only in memory, so there is no file to write to. Choosing "
                            + "one opens a Save As dialog that only the human can answer.",
                    "Ask the human to save the model once in Archi (File > Save As); "
                            + "save-model works on every call after that.",
                    null);
        }

        String path = file.getAbsolutePath();
        if (!store.isDirty(model)) {
            logger.info("Save skipped — model '{}' has no unsaved changes ({})", model.getName(), path);
            return new SaveModelResultDto(false, false, model.getName(), path);
        }

        List<String> problems = validator.problems(model);
        if (!problems.isEmpty()) {
            throw new ModelAccessException(
                    "Model checks failed — Archi will not write this model",
                    ErrorCode.MODEL_CHECK_FAILED,
                    String.join("; ", problems),
                    "Fix the reported problems — most often a concept filed under a folder of the "
                            + "wrong layer — then call save-model again.",
                    null);
        }

        boolean written;
        try {
            written = Boolean.TRUE.equals(uiThread.onUiThread(() -> store.save(model)));
        } catch (MutationException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            throw new ModelAccessException(
                    "Failed to save model '" + model.getName() + "': " + cause.getMessage(),
                    ErrorCode.MUTATION_FAILED,
                    path,
                    "Check that the file is writable and not open in another program, then retry.",
                    null);
        }

        if (!written) {
            throw new ModelAccessException(
                    "Archi declined to save model '" + model.getName() + "'",
                    ErrorCode.MUTATION_FAILED,
                    path,
                    "Save the model manually in Archi to see why it was refused.",
                    null);
        }

        logger.info("Saved model '{}' to {}", model.getName(), path);
        return new SaveModelResultDto(true, true, model.getName(), path);
    }

    private static List<String> checkWithArchi(IArchimateModel model) {
        ModelChecker checker = new ModelChecker(model);
        return checker.checkAll() ? List.of() : checker.getErrorMessages();
    }

    /**
     * Resolves {@link IEditorModelManager#INSTANCE} per call rather than at construction: the
     * singleton's class initialiser builds a live editor model manager, and a dispatcher may be
     * constructed long before — or entirely without — a running workbench.
     */
    private static class EditorModelManagerStore implements ModelStore {

        @Override
        public boolean isDirty(IArchimateModel model) {
            return IEditorModelManager.INSTANCE.isModelDirty(model);
        }

        @Override
        public boolean save(IArchimateModel model) throws IOException {
            return IEditorModelManager.INSTANCE.saveModel(model);
        }
    }
}
