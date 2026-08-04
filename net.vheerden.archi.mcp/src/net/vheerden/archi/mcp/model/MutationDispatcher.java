package net.vheerden.archi.mcp.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.gef.commands.Command;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.archimatetool.model.IArchimateModel;

import net.vheerden.archi.mcp.model.exceptions.MutationException;
import net.vheerden.archi.mcp.response.dto.BatchStatusDto;
import net.vheerden.archi.mcp.response.dto.BatchSummaryDto;
import net.vheerden.archi.mcp.response.dto.PendingProposalView;
import net.vheerden.archi.mcp.response.dto.ProposalDto;
import net.vheerden.archi.mcp.response.dto.SaveModelResultDto;

/**
 * Dispatches mutation commands to the ArchiMate model via CommandStack.
 *
 * <p><strong>CRITICAL:</strong> ALL model mutations MUST go through
 * {@code CommandStack.execute(Command)}. Direct EMF modification corrupts
 * the model. Reference: forum.archimatetool.com topic 1285.</p>
 *
 * <p><strong>Threading model:</strong> Validation happens on the Jetty thread.
 * The minimal Command is dispatched via {@code Display.syncExec()} to the UI
 * thread for CommandStack.execute. Results are passed back via
 * {@link AtomicReference}.</p>
 *
 * <p>Manages per-session state via {@link MutationContext}, supporting
 * GUI-attached (immediate), batch (queued), and approval (proposed)
 * operational modes.</p>
 *
 * <p><strong>Layer 3 (Model Boundary):</strong> This class imports
 * {@code org.eclipse.gef.commands.*}, {@code org.eclipse.swt.widgets.Display},
 * and {@code com.archimatetool.model.*}. No handler may import these types.</p>
 */
public class MutationDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(MutationDispatcher.class);

    private final Supplier<IArchimateModel> modelSupplier;
    private final ConcurrentHashMap<String, MutationContext> batchSessions = new ConcurrentHashMap<>();
    private Runnable onImmediateDispatchCallback;

    /**
     * Time-to-live for abandoned pending proposals (Design §4 D5; 30 min).
     * Proposals older than this are swept on {@link #listAllPending} and on propose so the per-session
     * queue does not fill to the {@link MutationContext#MAX_PENDING_PROPOSALS hard cap}. Expiry is
     * surfaced (logged + queue-changed), never destructive to the model.
     */
    static final Duration PROPOSAL_TTL = Duration.ofMinutes(30);

    /**
     * The staleness guard — one {@code CommandStack} listener over the active model, re-resolving and
     * fingerprinting a proposal's targets so approve rejects-stale rather than misapplying a frozen command.
     */
    private final ProposalStalenessGuard stalenessGuard;

    /** Writes the active model to disk — see {@link #saveModel()}. */
    private final ModelPersistence modelPersistence;

    /** Drives Archi's diagram editors — see {@link #getViewNavigator()}. */
    private final ViewNavigator viewNavigator;

    /** The shared "stored request → fresh command + preconditions" rebuilder used by the approve path. */
    private final ProposalBuilder proposalBuilder = new ProposalBuilder();

    /**
     * Listeners notified whenever the pending-approval queue changes. The dispatcher
     * fires these after every queue-mutating point so the Pending Approvals dock view can rebuild.
     * {@link CopyOnWriteArrayList} so add/remove/iterate are lock-free and a listener registering
     * or unregistering during a fire never throws {@code ConcurrentModificationException}.
     */
    private final CopyOnWriteArrayList<ApprovalQueueListener> queueListeners = new CopyOnWriteArrayList<>();

    /**
     * The global, human-owned approval-mode source. Defaults to a fail-safe
     * GATED provider so an unwired dispatcher refuses to apply silently — the server/UI
     * bootstrap replaces it with a lambda reading the human-owned {@code ApprovalMode}.
     *
     * <p>{@code volatile}: written once from the UI/bootstrap thread via
     * {@link #setApprovalModeProvider}, read on every Jetty request thread — the keyword
     * supplies the happens-before edge so request threads always see the wired provider.</p>
     */
    private volatile ApprovalModeProvider approvalModeProvider = () -> true;

    public MutationDispatcher(Supplier<IArchimateModel> modelSupplier) {
        this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier must not be null");
        this.stalenessGuard = new ProposalStalenessGuard(modelSupplier);
        UiThreadDispatcher uiThread = new UiThreadDispatcher() {
            @Override
            public <T> T onUiThread(java.util.concurrent.Callable<T> work) throws MutationException {
                return dispatchOnUiThread(work);
            }
        };
        this.modelPersistence = new ModelPersistence(modelSupplier, uiThread);
        this.viewNavigator = new ViewNavigator(modelSupplier, uiThread);
    }

    // ---- Staleness-guard lifecycle (driven by the accessor's model events) ----

    /**
     * Registers the staleness guard's single {@code CommandStack} listener for the now-active model.
     * Called by the accessor when a model becomes active / on model switch. Idempotent.
     */
    public void onModelActive(IArchimateModel model) {
        stalenessGuard.onModelActive(model);
    }

    /** Removes the staleness guard's stack listener (active model closed, or accessor disposed). */
    public void onModelInactive() {
        stalenessGuard.onModelInactive();
    }

    /**
     * Captures the propose-time staleness snapshot (stack sequence + per-target fingerprints + names) for
     * the given target ids. Called by the accessor at each propose site; the result is stored on the
     * {@link PendingProposal} and compared at approve. Empty/null targets ⇒ an always-fresh capture.
     */
    StalenessCapture captureStaleness(Set<String> targetIds) {
        return stalenessGuard.capture(targetIds);
    }

    /**
     * Injects the global approval-mode source. Called once at server/UI bootstrap
     * with a lambda reading the human-owned {@code ApprovalMode} holder; tests pass a constant.
     *
     * <p>This sets the <em>source</em> of the read-only bit, not the bit itself — there is no
     * MCP-callable path to flip approval mode (D1: the only robust guard is non-existence).</p>
     *
     * @param provider the read-only approval-mode source (must not be null)
     */
    public void setApprovalModeProvider(ApprovalModeProvider provider) {
        this.approvalModeProvider = Objects.requireNonNull(provider, "approvalModeProvider must not be null");
    }

    /**
     * Sets a callback invoked after each immediate command dispatch.
     * Used by ArchiModelAccessorImpl to increment the version counter
     * when proposals are approved and dispatched immediately.
     *
     * @param callback the callback to invoke, or null to clear
     */
    void setOnImmediateDispatchCallback(Runnable callback) {
        this.onImmediateDispatchCallback = callback;
    }

    // ---- Persistence ----

    /**
     * Writes the active model to its file on disk.
     *
     * <p>Saving is not a {@code CommandStack} operation and leaves the model untouched; it lives
     * here because this class already owns the active-model supplier and the UI-thread hop that
     * host Archi's save requires.</p>
     *
     * @return what the save did, including whether a write was needed at all
     * @throws NoModelLoadedException if no model is open
     * @throws ModelAccessException   if the model has no file, fails Archi's checks, or the
     *                                write itself fails
     */
    public SaveModelResultDto saveModel() {
        return modelPersistence.save();
    }

    // ---- Editor navigation ----

    /**
     * Returns the collaborator that opens views and moves the camera within them.
     *
     * <p>Like {@link #saveModel()} this is not a {@code CommandStack} operation and changes no
     * model state; it lives behind this class because handlers reach the active model and the
     * UI-thread hop through here.</p>
     *
     * @return the view navigator, never null
     */
    public ViewNavigator getViewNavigator() {
        return viewNavigator;
    }

    // ---- Approval-queue change notification ----

    /**
     * Registers a listener notified whenever the pending-approval queue changes. Idempotent-safe
     * for callers that re-register; the Pending Approvals view adds itself on open and removes on
     * dispose.
     *
     * @param listener the listener to add (ignored if null)
     */
    public void addQueueListener(ApprovalQueueListener listener) {
        if (listener != null) {
            queueListeners.addIfAbsent(listener);
        }
    }

    /**
     * Removes a previously registered queue listener.
     *
     * @param listener the listener to remove (no-op if null or not registered)
     */
    public void removeQueueListener(ApprovalQueueListener listener) {
        if (listener != null) {
            queueListeners.remove(listener);
        }
    }

    /**
     * Notifies all queue listeners that the pending-approval queue changed. Fired <em>outside</em>
     * any session lock and after the queue mutation has completed. Each callback is guarded so a
     * throwing listener cannot break the mutation path it fired from (the model layer must never be
     * destabilised by a misbehaving UI observer).
     */
    private void fireQueueChanged() {
        for (ApprovalQueueListener listener : queueListeners) {
            try {
                listener.onQueueChanged();
            } catch (RuntimeException e) {
                logger.warn("Approval-queue listener threw during onQueueChanged (ignored)", e);
            }
        }
    }

    // ---- Immediate dispatch (GUI-attached mode) ----

    /**
     * Dispatches a command immediately via Display.syncExec + CommandStack.
     * Used in GUI-attached mode for real-time model updates.
     *
     * @param command the GEF command to execute
     * @throws MutationException if dispatch fails
     */
    public void dispatchImmediate(Command command) throws MutationException {
        logger.info("Dispatching immediate command: {}", command.getLabel());
        IArchimateModel model = requireModel();
        // D3: stamp authorship at the single admission chokepoint. Every agent
        // mutation — immediate single ops, the batch-commit compound, and approved-proposal
        // executes — funnels here, so wrapping once tags them all. Human GUI edits reach
        // Archi's CommandStack directly and stay untagged. One wrapper == one stack entry,
        // preserving the speculative undo(undoCount) arithmetic.
        AgentAuthoredCompoundCommand tagged = new AgentAuthoredCompoundCommand(command);
        dispatchOnUiThread(() -> {
            CommandStack stack = getCommandStack(model);
            stack.execute(tagged);
            return null;
        });
    }

    // ---- Undo/Redo ----

    /**
     * Undoes the specified number of operations from the command stack.
     *
     * @param steps number of operations to undo (must be >= 1)
     * @return list of command labels that were undone
     * @throws MutationException if undo fails
     */
    public UndoRedoState undo(int steps) throws MutationException {
        logger.info("Undo requested: {} steps", steps);
        IArchimateModel model = requireModel();
        return dispatchOnUiThread(() -> scopedUndo(getCommandStack(model), steps));
    }

    /**
     * Pure, {@code Display}-free decision core for the scoped agent undo (D4 — the
     * "betrayal" guard). Undoes <strong>only</strong> agent-authored top-of-stack entries, LIFO:
     *
     * <ul>
     *   <li>If the very top of the undo stack is <strong>not</strong> agent-authored, perform
     *       <strong>zero</strong> undos and return {@link UndoRedoState#blockedReason()} =
     *       {@link #BLOCK_REASON_HUMAN_EDIT} (the horror-moment guard: the agent must never
     *       silently evaporate the human's hand-work — to revert it the agent submits a new
     *       proposal).</li>
     *   <li>For {@code steps > 1}, undo consecutive agent entries and <strong>stop without
     *       error</strong> at the first human-authored entry (never cross a human edit),
     *       returning the labels actually undone and <strong>no</strong> block reason
     *       (partial progress was made).</li>
     *   <li>An empty/exhausted stack returns zero undos and <strong>no</strong> block reason —
     *       distinct from the human-blocked case.</li>
     * </ul>
     *
     * <p>Extracted out of the {@code Display}-bound {@link #undo(int)} so the betrayal guard is
     * fully unit-testable against a real headless {@code CommandStack} (instantiable with no
     * {@code Display}).</p>
     *
     * @param stack the command stack to operate on
     * @param steps the number of operations requested
     * @return the resulting state, including a nullable block reason
     */
    static UndoRedoState scopedUndo(CommandStack stack, int steps) {
        List<String> labels = new ArrayList<>();
        String blockedReason = null;
        for (int i = 0; i < steps; i++) {
            if (!stack.canUndo()) break;
            Command cmd = stack.getUndoCommand();
            if (cmd instanceof AgentAuthoredCommand) {
                labels.add(cmd.getLabel());
                stack.undo();
            } else {
                // Top is the human's (or, defensively, an unexpected null). Stop here — never
                // cross a human edit. Flag the betrayal refusal only when zero progress was made.
                if (labels.isEmpty() && cmd != null) {
                    blockedReason = BLOCK_REASON_HUMAN_EDIT;
                }
                break;
            }
        }
        return new UndoRedoState(labels, stack.canUndo(), stack.canRedo(), blockedReason);
    }

    /**
     * Redoes the specified number of previously undone operations.
     *
     * @param steps number of operations to redo (must be >= 1)
     * @return list of command labels that were redone
     * @throws MutationException if redo fails
     */
    public UndoRedoState redo(int steps) throws MutationException {
        logger.info("Redo requested: {} steps", steps);
        IArchimateModel model = requireModel();
        return dispatchOnUiThread(() -> scopedRedo(getCommandStack(model), steps));
    }

    /**
     * Pure, {@code Display}-free decision core for the scoped agent redo (D4, symmetric
     * to {@link #scopedUndo}). Re-applies <strong>only</strong> agent-authored redo entries: if the
     * top redo entry is human-authored (e.g. the human used native undo on their own edit), perform
     * zero redos and return {@link #BLOCK_REASON_HUMAN_EDIT}; for {@code steps > 1}, stop without
     * error at the first human redo entry. A human change must never be re-applied — or evaporated —
     * by the agent. An empty/exhausted redo stack returns zero with no block reason.
     *
     * @param stack the command stack to operate on
     * @param steps the number of operations requested
     * @return the resulting state, including a nullable block reason
     */
    static UndoRedoState scopedRedo(CommandStack stack, int steps) {
        List<String> labels = new ArrayList<>();
        String blockedReason = null;
        for (int i = 0; i < steps; i++) {
            if (!stack.canRedo()) break;
            Command cmd = stack.getRedoCommand();
            if (cmd instanceof AgentAuthoredCommand) {
                labels.add(cmd.getLabel());
                stack.redo();
            } else {
                if (labels.isEmpty() && cmd != null) {
                    blockedReason = BLOCK_REASON_HUMAN_EDIT;
                }
                break;
            }
        }
        return new UndoRedoState(labels, stack.canUndo(), stack.canRedo(), blockedReason);
    }

    /**
     * Block-reason marker meaning "refused because the top-of-stack entry is the human's"
     * (the betrayal guard, D4). Carried on {@link UndoRedoState#blockedReason()} so the
     * handler can emit a distinct diagnostic rather than collapsing it into "Nothing to undo".
     */
    public static final String BLOCK_REASON_HUMAN_EDIT = "HUMAN_EDIT";

    /**
     * Internal state returned from undo/redo operations.
     *
     * @param labels        labels of the operations actually undone/redone
     * @param canUndo       whether the stack can undo after the operation
     * @param canRedo       whether the stack can redo after the operation
     * @param blockedReason nullable reason the operation was refused with zero progress
     *                      ({@link #BLOCK_REASON_HUMAN_EDIT}); {@code null} when the stack was
     *                      simply empty/exhausted or work was performed
     */
    public record UndoRedoState(List<String> labels, boolean canUndo, boolean canRedo, String blockedReason) {

        /**
         * Back-compat convenience constructor at the prior canonical arity: no block
         * reason. Retained so any existing caller compiles unchanged (record-arity discipline).
         */
        public UndoRedoState(List<String> labels, boolean canUndo, boolean canRedo) {
            this(labels, canUndo, canRedo, null);
        }
    }

    // ---- Batch management ----

    /**
     * Starts batch mode for a session. Subsequent mutations will be queued
     * instead of applied immediately.
     *
     * @param sessionId the session identifier
     * @param description optional batch description
     * @throws IllegalStateException if session is already in batch mode
     */
    public void beginBatch(String sessionId, String description) {
        beginBatch(sessionId, description, null);
    }

    /**
     * As {@link #beginBatch(String, String)} but carrying the optional agent-supplied {@code intent}.
     * The intent is recorded on the session's {@link MutationContext} ({@code batchIntent})
     * and kept distinct from {@code description} (the undo-history label) — never merged (design §4 D6).
     * The server never depends on it; {@code intent} is never logged at INFO (agent free-text).
     */
    public void beginBatch(String sessionId, String description, String intent) {
        logger.info("Beginning batch for session '{}'{}", sessionId,
                description != null ? ": " + description : "");
        MutationContext context = batchSessions.computeIfAbsent(
                sessionId, k -> new MutationContext());
        context.beginBatch(description, intent);
    }

    /**
     * Ends batch mode for a session, either committing or rolling back.
     *
     * @param sessionId the session identifier
     * @param commit true to commit all queued mutations, false to rollback
     * @return summary of the batch operation
     * @throws IllegalStateException if session is not in batch mode
     * @throws MutationException if commit dispatch fails
     */
    public BatchSummaryDto endBatch(String sessionId, boolean commit) throws MutationException {
        MutationContext context = getActiveContext(sessionId);

        if (!commit) {
            logger.info("Rolling back batch for session '{}' ({} queued commands)",
                    sessionId, context.getQueuedCount());
            BatchSummaryDto summary = context.buildRollbackSummary();
            context.reset();
            removeBatchSessionIfEmpty(sessionId);
            return summary;
        }

        int queuedCount = context.getQueuedCount();
        logger.info("Committing batch for session '{}' ({} queued commands)",
                sessionId, queuedCount);

        BatchSummaryDto summary = context.buildCommitSummary();

        if (queuedCount > 0) {
            Command compound = context.buildCompoundCommand();
            dispatchCommand(compound);
        }

        context.reset();
        removeBatchSessionIfEmpty(sessionId);
        return summary;
    }

    /**
     * Drops the session's {@link #batchSessions} entry once a batch has ended,
     * <strong>but only when the context holds no pending proposals</strong>. Proposals live in the SAME
     * {@link MutationContext} as the batch ({@code storeProposal} → {@code computeIfAbsent} on this map),
     * and {@link MutationContext#reset()} deliberately preserves them; removing unconditionally would
     * silently drop the human's pending-approval queue. After {@code reset()} the mode is already
     * {@code GUI_ATTACHED}, so the pending-count is the only meaningful guard.
     *
     * <p>The check-and-remove is atomic: {@link ConcurrentHashMap#compute} holds the bin lock across the
     * {@code getPendingCount()} read, so a concurrent {@code storeProposal} (which {@code computeIfAbsent}s
     * the same key) cannot interleave between the check and the removal — no check-then-remove race that
     * could evaporate a just-stored proposal. Returning {@code null} from {@code compute} removes the entry.</p>
     */
    private void removeBatchSessionIfEmpty(String sessionId) {
        batchSessions.compute(sessionId, (k, ctx) ->
                (ctx == null || ctx.getPendingCount() == 0) ? null : ctx);
    }

    /**
     * @return number of live per-session batch/proposal contexts (test accessor).
     */
    int batchSessionCount() {
        return batchSessions.size();
    }

    /**
     * Queues a command for batch execution.
     *
     * @param sessionId the session identifier
     * @param command the GEF command to queue
     * @param description human-readable description of the mutation
     * @return the batch sequence number for this command
     * @throws IllegalStateException if session is not in batch mode
     */
    public int queueForBatch(String sessionId, Command command, String description) {
        MutationContext context = getActiveContext(sessionId);
        int seq = context.queueCommand(command, description);
        logger.debug("Queued command #{} for session '{}': {}", seq, sessionId, description);
        return seq;
    }

    /**
     * Returns the current operational mode for a session.
     *
     * @param sessionId the session identifier
     * @return the operational mode (GUI_ATTACHED if no batch context exists)
     */
    public OperationalMode getMode(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        if (context == null) {
            return OperationalMode.GUI_ATTACHED;
        }
        return context.getMode();
    }

    /**
     * Returns the batch status for a session.
     *
     * @param sessionId the session identifier
     * @return batch status DTO
     */
    public BatchStatusDto getBatchStatus(String sessionId) {
        // Approval mode is now global — overlay it onto the per-session batch/queue status.
        Boolean approval = approvalModeProvider.isApprovalModeOn() ? Boolean.TRUE : null;
        MutationContext context = batchSessions.get(sessionId);
        if (context == null) {
            return new BatchStatusDto(
                    OperationalMode.GUI_ATTACHED.name(), null, null, null, approval, null);
        }
        BatchStatusDto base = context.getBatchStatus();
        return new BatchStatusDto(
                base.mode(), base.queuedCount(), base.queuedDescriptions(),
                base.batchStarted(), approval, base.pendingApprovalCount());
    }

    // ---- Approval management (globalised) ----

    /**
     * Checks if approval mode is active. The bit is now <strong>global</strong> (one human,
     * one desktop, one gate), read from the injected {@link ApprovalModeProvider}.
     *
     * <p>The {@code sessionId} parameter is retained so the ~45 {@code ArchiModelAccessorImpl}
     * gate-check call-sites stay byte-identical (god-object ratchet), but it is
     * <em>ignored</em> for the gate decision: every session sees the same human-owned mode.</p>
     *
     * @param sessionId the session identifier (ignored — the gate is global)
     * @return true if approval is required, false otherwise
     */
    public boolean isApprovalRequired(String sessionId) {
        return approvalModeProvider.isApprovalModeOn();
    }

    /**
     * Stores a proposal for a session.
     *
     * @param sessionId the session identifier
     * @param proposal the proposal to store
     * @return the assigned proposal ID
     */
    public String storeProposal(String sessionId, PendingProposal proposal) {
        MutationContext context = batchSessions.computeIfAbsent(
                sessionId, k -> new MutationContext());
        // Sweep abandoned proposals first so TTL relieves the hard cap before this store.
        List<String> swept = context.sweepExpired(Instant.now(), PROPOSAL_TTL);
        if (!swept.isEmpty()) {
            logger.info("Swept {} expired proposal(s) from session '{}' on propose: {}",
                    swept.size(), sessionId, swept);
        }
        String id = context.storeProposal(proposal);
        logger.debug("Stored proposal '{}' for session '{}': {}", id, sessionId, proposal.description());
        fireQueueChanged();
        return id;
    }

    /**
     * Stores a proposal from individual fields. Public API for callers that
     * cannot access package-private {@link PendingProposal}.
     *
     * @param sessionId         the session identifier
     * @param tool              the MCP tool name (e.g., "create-element")
     * @param description       human-readable description
     * @param command           the GEF Command ready for execution
     * @param entity            the DTO representing the proposed result
     * @param currentState      snapshot of current state (null for creates)
     * @param proposedChanges   map of proposed field changes
     * @param validationSummary validation result summary
     * @param createdAt         timestamp when the proposal was created
     * @return the assigned proposal ID
     */
    public String storeProposal(String sessionId, String tool, String description,
            Command command, Object entity, Map<String, Object> currentState,
            Map<String, Object> proposedChanges, String validationSummary,
            Instant createdAt) {
        PendingProposal proposal = new PendingProposal(
                null, tool, description, command, entity,
                currentState, proposedChanges, validationSummary, createdAt);
        return storeProposal(sessionId, proposal);
    }

    /**
     * Retrieves a proposal by ID.
     *
     * @param sessionId the session identifier
     * @param proposalId the proposal ID
     * @return the proposal, or null if not found
     */
    public PendingProposal getProposal(String sessionId, String proposalId) {
        MutationContext context = batchSessions.get(sessionId);
        return context != null ? context.getProposal(proposalId) : null;
    }

    /**
     * Removes a proposal by ID.
     *
     * @param sessionId the session identifier
     * @param proposalId the proposal ID
     * @return the removed proposal, or null if not found
     */
    public PendingProposal removeProposal(String sessionId, String proposalId) {
        MutationContext context = batchSessions.get(sessionId);
        PendingProposal removed = context != null ? context.removeProposal(proposalId) : null;
        if (removed != null) {
            // One fire here covers approve and reject too — both route through removeProposal.
            // Fired only on an actual removal so a miss (already-drained proposal) is silent.
            fireQueueChanged();
        }
        return removed;
    }

    /**
     * Gets all pending proposals for a session.
     *
     * @param sessionId the session identifier
     * @return list of pending proposals (empty if none)
     */
    public List<PendingProposal> getPendingProposals(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        return context != null ? context.getPendingProposals() : List.of();
    }

    /**
     * Dispatches an already-rebuilt approved command, respecting the current batch/immediate mode.
     * Immediate mode bumps the version counter via {@link #onImmediateDispatchCallback}; BATCH mode
     * queues the command (parity with the previous {@code executeProposal} BATCH branch).
     *
     * @param sessionId   the session identifier
     * @param command     the fresh command rebuilt by {@link ProposalBuilder}
     * @param description the proposal description (batch queue label)
     * @return batch sequence number if queued, null if dispatched immediately
     * @throws MutationException if dispatch fails
     */
    private Integer dispatchApproved(String sessionId, Command command, String description)
            throws MutationException {
        OperationalMode mode = getMode(sessionId);
        if (mode == OperationalMode.BATCH) {
            return queueForBatch(sessionId, command, description);
        }
        dispatchCommand(command);
        if (onImmediateDispatchCallback != null) {
            onImmediateDispatchCallback.run();
        }
        return null;
    }

    // ---- Handler-facing facade methods ----
    // These methods convert package-private PendingProposal to public DTOs,
    // keeping PendingProposal hidden from the handlers layer.

    /**
     * Returns pending proposals as DTOs for the handler layer.
     *
     * @param sessionId the session identifier
     * @return list of ProposalDto summaries (empty if none)
     */
    public List<ProposalDto> getPendingProposalDtos(String sessionId) {
        List<PendingProposal> proposals = getPendingProposals(sessionId);
        List<ProposalDto> dtos = new ArrayList<>();
        for (PendingProposal p : proposals) {
            dtos.add(toProposalDto(p));
        }
        return dtos;
    }

    /**
     * Returns the union of pending proposals across <em>all</em> sessions for the Pending Approvals
     * dock view. Each entry carries its {@code sessionId} so the view can route
     * Approve/Reject back to the right session — the gate is global (one human, one queue) even
     * though storage stays per-session.
     *
     * <p>Ordered by {@code createdAt} <strong>ascending</strong> (oldest first) so the human works
     * the queue top-down in the order the agent built it — also the order most likely to satisfy
     * inter-proposal dependencies. Sorting is on the {@link Instant} (not the ISO string) to avoid
     * the lexicographic hazard of variable fractional-second digits in {@code Instant.toString()}.</p>
     *
     * <p>Sweeps proposals older than {@link #PROPOSAL_TTL} from each session before listing, so
     * abandoned proposals stop counting against the hard cap and drop out of the human's view. Expiry is
     * surfaced via an INFO log, the proposal's disappearance from this live list, and — when a sweep
     * actually removed something — an {@code ApprovalQueueListener} fire so the dock repaints even if the
     * sweep was triggered by a non-dock caller. Non-destructive to the model.</p>
     *
     * @return all sessions' pending proposals as {@link PendingProposalView}, oldest first (empty if none)
     */
    public List<PendingProposalView> listAllPending() {
        Instant now = Instant.now();
        boolean anySwept = false;
        record Pair(String sessionId, PendingProposal proposal) {}
        List<Pair> pairs = new ArrayList<>();
        for (Map.Entry<String, MutationContext> entry : batchSessions.entrySet()) {
            String sessionId = entry.getKey();
            List<String> swept = entry.getValue().sweepExpired(now, PROPOSAL_TTL);
            if (!swept.isEmpty()) {
                anySwept = true;
                logger.info("Swept {} expired proposal(s) from session '{}' on list: {}",
                        swept.size(), sessionId, swept);
            }
            for (PendingProposal p : entry.getValue().getPendingProposals()) {
                pairs.add(new Pair(sessionId, p));
            }
        }
        // A TTL sweep is a queue mutation — notify listeners so the dock repaints even when the sweep was
        // triggered by a non-dock caller (e.g. the agent's list-pending-approvals), surfacing the expiry
        // rather than leaving an expired card on screen until the next dock-initiated refresh. Fired
        // only on an actual sweep; the returned list below already reflects post-sweep state, and the dock's
        // re-entrant rebuild→listAllPending finds nothing left to sweep, so this terminates in one cycle.
        if (anySwept) {
            fireQueueChanged();
        }
        pairs.sort(Comparator.comparing(pair -> pair.proposal().createdAt()));
        List<PendingProposalView> views = new ArrayList<>(pairs.size());
        for (Pair pair : pairs) {
            views.add(new PendingProposalView(pair.sessionId(), toProposalDto(pair.proposal())));
        }
        return views;
    }

    /**
     * Approves a single proposal: re-resolves and rebuilds its command fresh against the current model,
     * dispatches it, and returns the result with the entity DTO.
     *
     * <p><strong>Vet/rebuild before removing.</strong> The proposal is <em>peeked</em>, not removed,
     * until a fresh command has been successfully built. A stale verdict (the human edited/removed a
     * targeted object) or an unrebuildable target therefore <strong>leaves the proposal in the queue</strong>
     * and throws — so the Pending Approvals card stays put with its plain-language, target-named reason for
     * the human to read and Reject, rather than the card vanishing on the queue-changed repaint before the
     * reason can be seen. Only a successful rebuild removes the proposal and dispatches it.</p>
     *
     * @param sessionId  the session identifier
     * @param proposalId the proposal to approve
     * @return ApprovalResult with entity and optional batch sequence, or null if not found
     * @throws MutationException if the proposal is stale or its command cannot be rebuilt (nothing dispatched)
     */
    public ApprovalResult approveProposal(String sessionId, String proposalId)
            throws MutationException {
        PendingProposal proposal = getProposal(sessionId, proposalId);
        if (proposal == null) {
            return null;
        }
        logger.info("Approving proposal '{}' for session '{}': {}",
                proposalId, sessionId, proposal.description());
        // Store-the-request approve path — re-resolve + re-check + build fresh, never run a frozen
        // command, and DON'T remove the proposal until the rebuild succeeds (so a stale rejection keeps the
        // card on screen with its named reason). (1) Reject-stale if the human edited/removed a
        // targeted object since propose, naming what they touched. (2) Rebuild fresh against the
        // CURRENT model via the shared ProposalBuilder (same prepareXxx the immediate path runs, so no
        // drift); an unrebuildable target also surfaces as stale here. (3) Only now remove from the queue
        // and dispatch through the same seam as immediate ops, preserving the agent-authored tag.
        ProposalStalenessGuard.StaleVerdict verdict = stalenessGuard.vet(proposal.capture());
        if (verdict.stale()) {
            logger.info("Proposal '{}' rejected as stale (kept in queue): {}", proposalId, verdict.reason());
            throw new MutationException(verdict.reason());
        }
        PreparedMutation<?> fresh = proposalBuilder.rebuild(proposal); // may throw stale — proposal still queued
        removeProposal(sessionId, proposalId); // commit point: fresh command built, now drain + dispatch
        Integer batchSeq = dispatchApproved(sessionId, fresh.command(), proposal.description());
        // Prefer the freshly re-resolved entity (e.g. a create's real new id) over the propose-time DTO.
        Object entity = fresh.entity() != null ? fresh.entity() : proposal.entity();
        return new ApprovalResult(entity, batchSeq, proposal.tool(), proposal.description());
    }

    /**
     * Rejects a single proposal: removes it from pending and returns
     * a DTO summary for the response.
     *
     * @param sessionId  the session identifier
     * @param proposalId the proposal to reject
     * @return ProposalDto with status "rejected", or null if not found
     */
    public ProposalDto rejectProposal(String sessionId, String proposalId) {
        PendingProposal proposal = removeProposal(sessionId, proposalId);
        if (proposal == null) {
            return null;
        }
        logger.info("Rejecting proposal '{}' for session '{}': {}",
                proposalId, sessionId, proposal.description());
        return new ProposalDto(
                proposal.proposalId(), proposal.tool(), "rejected",
                proposal.description(), proposal.currentState(),
                proposal.proposedChanges(), proposal.validationSummary(),
                proposal.createdAt().toString(),
                proposal.effectDescription(), proposal.intent());
    }

    private ProposalDto toProposalDto(PendingProposal p) {
        return new ProposalDto(
                p.proposalId(), p.tool(), "pending",
                p.description(), p.currentState(), p.proposedChanges(),
                p.validationSummary(), p.createdAt().toString(),
                p.effectDescription(), p.intent());
    }

    /**
     * Clears all session contexts. Called during server shutdown.
     */
    public void clearAllSessions() {
        batchSessions.clear();
        logger.debug("Cleared all mutation sessions (batch + approval)");
        fireQueueChanged();
    }

    // ---- Internal dispatch ----

    /**
     * Dispatches a command via Display.syncExec + CommandStack.
     * Protected for test override.
     *
     * @param command the command to dispatch
     * @throws MutationException if dispatch fails
     */
    protected void dispatchCommand(Command command) throws MutationException {
        dispatchImmediate(command);
    }

    private <T> T dispatchOnUiThread(java.util.concurrent.Callable<T> work) throws MutationException {
        Display display = Display.getDefault();
        if (display == null) {
            throw new MutationException(
                    "No display available — headless mode not supported for mutations");
        }

        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        display.syncExec(() -> {
            try {
                result.set(work.call());
            } catch (Exception e) {
                error.set(e);
            }
        });

        if (error.get() != null) {
            Exception ex = error.get();
            if (ex instanceof MutationException me) {
                throw me;
            }
            throw new MutationException("Mutation failed on UI thread", ex);
        }
        return result.get();
    }

    private IArchimateModel requireModel() throws MutationException {
        IArchimateModel model = modelSupplier.get();
        if (model == null) {
            throw new MutationException("No model loaded — cannot execute mutation");
        }
        return model;
    }

    private CommandStack getCommandStack(IArchimateModel model) throws MutationException {
        Object adapter = model.getAdapter(CommandStack.class);
        if (!(adapter instanceof CommandStack stack)) {
            throw new MutationException("CommandStack not available for model");
        }
        return stack;
    }

    private MutationContext getActiveContext(String sessionId) {
        MutationContext context = batchSessions.get(sessionId);
        if (context == null || context.getMode() != OperationalMode.BATCH) {
            throw new IllegalStateException("No active batch for session '" + sessionId + "'");
        }
        return context;
    }
}
