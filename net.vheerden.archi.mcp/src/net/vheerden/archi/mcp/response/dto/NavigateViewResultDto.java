package net.vheerden.archi.mcp.response.dto;

import java.util.List;

/**
 * Data Transfer Object for the {@code navigate-view} command.
 *
 * <p>Reports the viewport the editor actually settled on, which may differ from what was asked
 * for: a zoom request is clamped to the editor's own zoom range, and a scroll request is bounded
 * by the diagram's extent.</p>
 *
 * @param viewId            the navigated view's identifier
 * @param viewName          the navigated view's name
 * @param selectedObjectIds visual-object identifiers left selected, in request order
 * @param zoom              the resulting zoom factor, where 1.0 is 100%
 * @param scrollX           the resulting horizontal scroll position, in diagram coordinates
 * @param scrollY           the resulting vertical scroll position, in diagram coordinates
 */
public record NavigateViewResultDto(
    String viewId,
    String viewName,
    List<String> selectedObjectIds,
    double zoom,
    int scrollX,
    int scrollY
) {
}
