package net.vheerden.archi.mcp.response.dto;

/**
 * Data Transfer Object for the {@code open-view} command.
 *
 * @param viewId      the opened view's identifier
 * @param viewName    the opened view's name
 * @param alreadyOpen whether an editor for this view was already open before the call
 * @param activated   whether the editor was brought to the front
 */
public record OpenViewResultDto(
    String viewId,
    String viewName,
    boolean alreadyOpen,
    boolean activated
) {
}
