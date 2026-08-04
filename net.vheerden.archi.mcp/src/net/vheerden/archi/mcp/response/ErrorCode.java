package net.vheerden.archi.mcp.response;

/**
 * Enumeration of structured error codes for MCP tool responses.
 *
 * <p>Used in {@link ErrorResponse} to provide machine-readable error
 * classification. Each code maps to a specific error category that
 * LLM clients can use to determine appropriate recovery actions.</p>
 */
public enum ErrorCode {

    ELEMENT_NOT_FOUND,
    FOLDER_NOT_FOUND,
    MODEL_NOT_LOADED,
    INVALID_PARAMETER,
    INVALID_CURSOR,
    VIEW_NOT_FOUND,
    INTERNAL_ERROR,
    BATCH_ALREADY_ACTIVE,
    BATCH_NOT_ACTIVE,
    MUTATION_FAILED,
    INVALID_ELEMENT_TYPE,
    INVALID_RELATIONSHIP_TYPE,
    RELATIONSHIP_NOT_ALLOWED,
    SOURCE_ELEMENT_NOT_FOUND,
    TARGET_ELEMENT_NOT_FOUND,
    POTENTIAL_DUPLICATES,
    BULK_VALIDATION_FAILED,
    PROPOSAL_NOT_FOUND,
    PROPOSAL_STALE,
    APPROVAL_NOT_ACTIVE,
    ELEMENT_ALREADY_ON_VIEW,
    RELATIONSHIP_NOT_FOUND,
    VIEW_OBJECT_NOT_FOUND,
    CONNECTION_ALREADY_ON_VIEW,
    RELATIONSHIP_MISMATCH,
    FORMAT_NOT_AVAILABLE,
    FOLDER_NOT_EMPTY,
    CANNOT_DELETE_DEFAULT_FOLDER,
    CIRCULAR_FOLDER_REFERENCE,
    CANNOT_MOVE_DEFAULT_FOLDER,
    CANNOT_CREATE_ROOT_FOLDER,
    ALREADY_IN_TARGET_FOLDER,
    OBJECT_NOT_FOUND,
    INVALID_MOVE_TARGET,
    FOLDER_LAYER_MISMATCH,
    /** Supplied imagePath does not resolve to bytes in the model archive. */
    IMAGE_NOT_FOUND,
    /** The open model has no file on disk, so there is nothing to save it to. */
    MODEL_NEVER_SAVED,
    /** Archi's own model checker rejects the model; host Archi would refuse to write it. */
    MODEL_CHECK_FAILED,
    /** Approval mode is on, so the human — not the agent — decides when the model reaches disk. */
    SAVE_GATED_BY_APPROVAL,
    /** No Archi workbench is running, so there are no diagram editors to open or navigate. */
    VIEW_EDITOR_UNAVAILABLE
}
