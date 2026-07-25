import type { UserRole } from "@/lib/types";

/**
 * The task-assignment API shapes, mirroring `backend/app/api/routes/tasks.py`.
 *
 * These live here rather than in `lib/types.ts` because the serialised task is much richer than the
 * stored row: the backend resolves the workshop title, the artisan and section rows, the record-type
 * labels and BOTH progress numbers into every item, so a whole board renders from one call. The
 * legacy `AssignedTask` in `lib/types.ts` describes the bare row and is deliberately left alone.
 */

export type TaskStatus = "OPEN" | "IN_PROGRESS" | "DONE" | "CANCELLED";

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
  derivedBreakdown: Record<string, number>;
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
  progressCount: number;
  derivedCount: number | null;
  percentComplete: number | null;
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
  overdueCount: number;
  reportedTotal: number;
  derivedTotal: number | null;
  percentComplete: number;
  assignees: TaskBatchAssignee[];
};

export type TaskProgressAssignee = {
  user: TaskUserBrief | null;
  taskCount: number;
  statusCounts: Record<TaskStatus, number>;
  openCount: number;
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
  overdueCount: number;
  /** True when the 2000-row scan window was hit: the rollup is partial, say so. */
  truncated: boolean;
  assignees: TaskProgressAssignee[];
};

/** POST /tasks/batch response. */
export type TaskBatchResult = {
  batchId: string;
  title: string;
  created: number;
  batch: TaskBatch;
  tasks: FieldTask[];
};
