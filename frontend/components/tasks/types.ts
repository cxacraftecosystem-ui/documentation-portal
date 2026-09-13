import type { UserRole } from "@/lib/types";

/**
 * The task-assignment API shapes, mirroring `backend/app/api/routes/tasks.py`.
 *
 * These live here rather than in `lib/types.ts` because the serialised task is much richer than the
 * stored row: the backend resolves the workshop title, the artisan and section rows, the record-type
 * labels and BOTH progress numbers into every item, so a whole board renders from one call. The
 * legacy `AssignedTask` in `lib/types.ts` describes the bare row and is deliberately left alone.
 */

/**
 * The five states a task can be in — FIVE since the review state landed, and the addition is not a
 * cosmetic one.
 *
 * `SUBMITTED` is the assignee's "I have finished" BEFORE anybody with authority has agreed. It is
 * the server's word (`backend/app/api/routes/tasks.py:105`, which argues there why it is not called
 * `IN_REVIEW` — nothing guarantees a reviewer is looking — and emphatically not
 * `DONE_PENDING_REVIEW`, which puts the substring DONE inside a value that is not done). The
 * owner's phrase "under review" is the LABEL, and the label is served (`statusLabel` below), not
 * invented here.
 *
 * WIDENING THIS UNION IS THE POINT, AND THE COMPILER IS THE FEATURE. Every `Record<TaskStatus, …>`
 * in the client — the pill's tone map, its text map, the assignee screen's filter labels — goes red
 * until it has a `SUBMITTED` entry, which is exactly the treatment `Record<UserRole, …>` gets in
 * `lib/permissions.ts` and for the same reason: a status map that silently falls through paints the
 * new state with the old state's colour and nobody finds out from a test. Typing the field as a
 * bare `string` instead would have compiled on the day and lost every one of those call sites.
 */
export type TaskStatus = "OPEN" | "IN_PROGRESS" | "SUBMITTED" | "DONE" | "CANCELLED";

/**
 * The statuses an ADMIN OVERRIDE on the accountability board may move a task to.
 *
 * CANCELLED IS EXCLUDED BY THE TYPE, AND THAT IS THE POINT OF THIS ALIAS EXISTING. The server would
 * accept it — `update_task` lets any manager write any status, and an admin is always a manager
 * (backend/app/api/routes/tasks.py:1188) — so nothing but this declaration keeps the board's
 * per-task control away from it. Cancelling is a WITHDRAWAL of an assignment, and withdrawal
 * already has a home on the Assignments tab, where it is one action over the whole batch, behind a
 * danger-tone confirm, and restricted to the admin who sent it or the master admin
 * (`delete_task_batch`). Offering it here as a third small button beside "Mark done for them"
 * would put the one irreversible act on this board in the same visual register as the two
 * reversible ones, one row deep inside a disclosure, on a screen whose entire job is reading
 * numbers rather than changing them.
 *
 * SUBMITTED IS EXCLUDED TOO, AND FOR A DIFFERENT REASON: it is not an admin's word to write at all.
 * Submitting is the act of the person who did the work — the server only ever reaches SUBMITTED by
 * rewriting an assignee's own "DONE" (`update_task`, the assignee branch). An admin pressing a
 * button that hands somebody else's work in on their behalf would be manufacturing the very claim
 * the approval step exists to check.
 */
export type TaskOverrideTarget = Extract<TaskStatus, "OPEN" | "IN_PROGRESS" | "DONE">;

/**
 * Which measure `effectivePercent` was drawn from, straight off the wire
 * (`effective_progress()`, backend/app/api/routes/tasks.py:819).
 *
 * `"derived"` counted rows in the repository, `"reported"` divided what the assignee typed by their
 * quota, `"status"` read it off an approval or a withdrawal, and `null` means THERE IS NO HONEST
 * NUMBER — a bar drawn at that point is a fabrication, not a zero. The client never re-derives this
 * precedence; three hand-written clients each deciding it for themselves is three chances to draw a
 * different bar over the same row.
 */
export type TaskProgressSource = "status" | "derived" | "reported";

/** `user_brief()` — just enough to name a person on a board, never any privilege material. */
export type TaskUserBrief = {
  id: string;
  name: string;
  email?: string;
  role: UserRole;
  /** Present on the brief; absent when the row carries a full serialised User instead. */
  roleLabel?: string;
};

export type TaskArtisanRef = { id: string; name: string; place?: string | null };
export type TaskSectionRef = { id: string; code: string; title: string; sortOrder: number };

/** One assignee's task, as returned by `serialize_task()`. */
export type FieldTask = {
  id: string;
  title: string;
  description?: string | null;
  status: TaskStatus;
  dueAt?: string | null;
  completedAt?: string | null;
  /** Legacy single-record link ("finish THIS product"), orthogonal to the scope block. */
  recordType?: string | null;
  recordId?: string | null;
  workshopId?: string | null;
  recordTypes: string[];
  artisanIds: string[];
  sectionIds: string[];
  targetCount?: number | null;
  progressCount: number;
  batchId?: string | null;
  assigneeId: string;
  createdById: string;
  createdAt: string;
  updatedAt: string;
  assignee?: TaskUserBrief | null;
  createdBy?: TaskUserBrief | null;
  // --- resolved by the server so the UI never follows up per task ---
  workshopTitle?: string | null;
  artisans: TaskArtisanRef[];
  sections: TaskSectionRef[];
  recordTypeLabels: string[];
  /** Self-reported completion 0-100; null when the task is open-ended and unfinished. */
  percentComplete: number | null;
  isOverdue: boolean;
  /** What the repository can actually see this person having produced. Null = not counted. */
  derivedCount: number | null;
  derivedTarget: number | null;
  /**
   * The derived count broken out by what it counted — `{ product: 3, sections: 6 }`. May also carry
   * `unlinkedSections`: sections whose only answers sit on an interview with no artisan attached,
   * which cannot raise the pair count. Without that key the difference between "nobody started" and
   * "the interviews were never linked to an artisan" is an unexplained, permanent 0%.
   */
  derivedBreakdown: Record<string, number>;
  /** The repository-derived figure as a percentage — the honest counter-number to `percentComplete`. */
  derivedPercent: number | null;
  /** The roster the derived denominator was built from, so a UI can say "2 sections x 12 artisans". */
  derivedArtisanCount: number | null;

  // --- the review state, said by the server so three clients cannot word it three ways ---
  /**
   * RENDER THIS, NOT A CLIENT-SIDE MAP. `STATUS_LABELS` lives on the server
   * (backend/app/api/routes/tasks.py:129) precisely because this repository has no API codegen: the
   * web types and Android's `ApiModels.kt` are hand-written, and Kotlin's `ignoreUnknownKeys` makes
   * a missed field silent. A researcher who moves between the handset and the laptop mid-workshop
   * must not be told two different things about one row.
   */
  statusLabel: string;
  /** `status === "SUBMITTED"` — handed in, waiting on an admin. */
  isAwaitingReview: boolean;
  /**
   * STILL ON MY LIST. Filter on this, never on `status !== "DONE"`: the set is three statuses now
   * and a client re-deriving it is a client that will forget the third one.
   */
  isOutstanding: boolean;
  /**
   * THE NUMBER A PROGRESS BAR IS DRAWN FROM, and `null` means DRAW NO BAR. A task with no
   * measurable scope and no quota ("Food + collect TA details") has no percentage; rendering it at
   * 0% says "nothing done" about work that was never countable, which is the failure this field
   * exists to prevent. Render the state pill and the label, and leave the bar out.
   */
  effectivePercent: number | null;
  /** Which measure that percentage came from, so the bar can be captioned honestly. */
  progressSource: TaskProgressSource | null;
  /** The caption itself — "6 of 24 artisan sections recorded", "4 of 10 reported". */
  progressLabel: string;
};

/** GET /tasks/options — every picker the assignment builder needs, in one call. */
export type TaskOptions = {
  recordTypes: { value: string; label: string; pluralLabel: string }[];
  assignees: TaskUserBrief[];
  workshops: { id: string; title: string; place?: string | null; date?: string | null }[];
  artisans: TaskArtisanRef[];
  sections: TaskSectionRef[];
};

export type TaskBatchAssignee = {
  taskId: string;
  user: TaskUserBrief | null;
  status: TaskStatus;
  statusLabel: string;
  isAwaitingReview: boolean;
  progressCount: number;
  derivedCount: number | null;
  percentComplete: number | null;
  effectivePercent: number | null;
  /**
   * On a SUBMITTED row this is WHEN IT WAS HANDED IN, not when it was approved — the server stamps
   * it at the first declaration that the work is finished and deliberately does not re-stamp it on
   * approval, so `completedAt > dueAt` stays a fact about the person who did the work rather than
   * about how long the reviewer took.
   */
  completedAt?: string | null;
};

/** GET /tasks/batches — one assignment action rolled back up. */
export type TaskBatch = {
  /** Null for rows written before batching existed, and for single-assignee creates. */
  batchId: string | null;
  /** Stable grouping key: the batchId, or `task:{id}` for the unbatched ones. */
  key: string;
  title: string;
  description?: string | null;
  dueAt?: string | null;
  createdAt: string;
  createdBy: TaskUserBrief | null;
  workshopId?: string | null;
  workshopTitle?: string | null;
  recordTypes: string[];
  recordTypeLabels: string[];
  artisans: TaskArtisanRef[];
  sections: TaskSectionRef[];
  targetCount: number | null;
  assigneeCount: number;
  statusCounts: Record<TaskStatus, number>;
  doneCount: number;
  openCount: number;
  /** Handed in and waiting on a reviewer — kept OUT of `openCount`, which is what the assignee owes. */
  awaitingReviewCount: number;
  /** Not yet approved, by anybody's doing: open + in progress + awaiting review. */
  outstandingCount: number;
  overdueCount: number;
  reportedTotal: number;
  derivedTotal: number | null;
  /**
   * APPROVED ONLY — submissions are deliberately not folded in. A batch reading "5 of 5 done" while
   * nobody had looked at any of it is the illusion the review state was added to remove; draw the
   * awaiting slice from `awaitingReviewCount` instead.
   */
  percentComplete: number;
  assignees: TaskBatchAssignee[];
};

export type TaskProgressAssignee = {
  user: TaskUserBrief | null;
  taskCount: number;
  statusCounts: Record<TaskStatus, number>;
  /** Work this person still owes: OPEN + IN_PROGRESS. Submissions are not here — see below. */
  openCount: number;
  /**
   * Work waiting on the READER of this board, not on the person named on the row. Kept out of
   * `openCount` deliberately: the board sorts "who is behind" by it, and a researcher who handed
   * everything in on time must not be chased for the reviewer's backlog.
   */
  awaitingReviewCount: number;
  outstandingCount: number;
  overdueCount: number;
  targetTotal: number | null;
  reportedTotal: number;
  derivedTotal: number | null;
  percentComplete: number;
  tasks: FieldTask[];
};

/** GET /tasks/progress — the accountability rollup. */
export type TaskProgressReport = {
  workshopId: string | null;
  workshopTitle: string | null;
  assigneeCount: number;
  taskCount: number;
  doneCount: number;
  openCount: number;
  /** THE ONE NUMBER ON THIS BOARD THAT IS ABOUT THE READER: approvals they owe. */
  awaitingReviewCount: number;
  outstandingCount: number;
  overdueCount: number;
  /** True when the 2000-row scan window was hit: the rollup is partial, say so. */
  truncated: boolean;
  assignees: TaskProgressAssignee[];
};

/**
 * GET /tasks/summary — MY workload in one object, and the only correct source for the full-width
 * card at the top of the assignee's screen.
 *
 * WHY NOT COUNT THE PAGE THAT IS ALREADY ON SCREEN. `GET /tasks?view=assigned` is paged twelve rows
 * at a time, so a card computed from it tells anyone with thirteen tasks that they have fewer than
 * they do — under-reporting outstanding work, which is the single thing this card exists to make
 * impossible to miss. This endpoint scans the caller's whole (bounded) list and says `truncated`
 * when even that window was hit.
 *
 * It is always about the caller; there is no `assigneeId`. Reading somebody else's line is the
 * accountability board's job and that route is admin-only.
 */
export type TaskSummary = {
  assignee: TaskUserBrief | null;
  workshopId: string | null;
  taskCount: number;
  /** All five keys are always present, so `statusCounts.SUBMITTED` is never undefined. */
  statusCounts: Record<TaskStatus, number>;
  /** OPEN + IN_PROGRESS — what is still on YOU, and the number the card leads with. */
  remainingCount: number;
  /** Handed in, not yet agreed. Still on the screen, visibly not "to do". */
  awaitingReviewCount: number;
  /** remaining + awaiting review = the cards this person will actually see in their list. */
  outstandingCount: number;
  approvedCount: number;
  cancelledCount: number;
  overdueCount: number;
  dueSoonCount: number;
  /** Excludes anything already overdue — that emergency is counted once, by `overdueCount`. */
  nextDueAt: string | null;
  percentComplete: number;
  /**
   * HOW MANY OF THE FIGURES BEHIND THAT PERCENTAGE WERE COUNTED FROM THE REPOSITORY rather than
   * inferred from a status or typed into a box. A card that claims progress advances automatically
   * has to be able to say how much of it actually did.
   */
  measuredCount: number;
  /** The derivation budget was exceeded, so no task on this summary was counted from records. */
  derivationSkipped: boolean;
  /** The 2000-row scan window was hit: these counts are a floor, not a total. */
  truncated: boolean;
};

/** POST /tasks/batch response. */
export type TaskBatchResult = {
  batchId: string;
  title: string;
  created: number;
  batch: TaskBatch;
  tasks: FieldTask[];
};
