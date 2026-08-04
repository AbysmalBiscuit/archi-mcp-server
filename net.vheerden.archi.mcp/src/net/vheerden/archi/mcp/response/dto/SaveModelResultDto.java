package net.vheerden.archi.mcp.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for the {@code save-model} command.
 *
 * <p>Reports whether the call actually wrote the model to disk. A clean model
 * returns {@code saved = false} with {@code wasDirty = false}: nothing needed
 * writing, which is a success, not an error.</p>
 *
 * @param saved     whether this call wrote the model to its file
 * @param wasDirty  whether the model had unsaved changes when the call arrived
 * @param modelName the model's name
 * @param filePath  absolute path of the file the model is stored in
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveModelResultDto(
    boolean saved,
    boolean wasDirty,
    String modelName,
    String filePath
) {
}
