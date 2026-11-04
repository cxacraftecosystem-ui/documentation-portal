import type { UserRole } from "@/lib/types";

export type TaskStatus = "OPEN" | "IN_PROGRESS" | "SUBMITTED" | "DONE" | "CANCELLED";

export type TaskOverrideTarget = Extract<TaskStatus, "OPEN" | "IN_PROGRESS" | "DONE">;

export type TaskProgressSource = "status" | "derived" | "reported";

export type TaskUserBrief = {
  id: string;
  name: string;
  email?: string;
  role: UserRole;
  roleLabel?: string;
};

export type TaskArtisanRef = { id: string; name: string; place?: string | null };
export type TaskSectionRef = { id: string; code: string; title: string; sortOrder: number };

export type FieldTask = {
  id: string;
  title: string;
  description?: string | null;
  status: TaskStatus;
  dueAt?: string | null;
  completedAt?: string | null;
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
  workshopTitle?: string | null;
  artisans: TaskArtisanRef[];
  sections: TaskSectionRef[];
  recordTypeLabels: string[];
  percentComplete: number | null;
  isOverdue: boolean;
  derivedCount: number | null;
  derivedTarget: number | null;
  derivedBreakdown: Record<string, number>;
  derivedPercent: number | null;
  derivedArtisanCount: number | null;

  statusLabel: string;
  isAwaitingReview: boolean;
  isOutstanding: boolean;
  effectivePercent: number | null;
  progressSource: TaskProgressSource | null;
  progressLabel: string;
};

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
  completedAt?: string | null;
};

export type TaskBatch = {
  batchId: string | null;
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
  awaitingReviewCount: number;
  outstandingCount: number;
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
  awaitingReviewCount: number;
  outstandingCount: number;
  overdueCount: number;
  targetTotal: number | null;
  reportedTotal: number;
  derivedTotal: number | null;
  percentComplete: number;
  tasks: FieldTask[];
};

export type TaskProgressReport = {
  workshopId: string | null;
  workshopTitle: string | null;
  assigneeCount: number;
  taskCount: number;
  doneCount: number;
  openCount: number;
  awaitingReviewCount: number;
  outstandingCount: number;
  overdueCount: number;
  truncated: boolean;
  assignees: TaskProgressAssignee[];
};

export type TaskSummary = {
  assignee: TaskUserBrief | null;
  workshopId: string | null;
  taskCount: number;
  statusCounts: Record<TaskStatus, number>;
  remainingCount: number;
  awaitingReviewCount: number;
  outstandingCount: number;
  approvedCount: number;
  cancelledCount: number;
  overdueCount: number;
  dueSoonCount: number;
  nextDueAt: string | null;
  percentComplete: number;
  measuredCount: number;
  derivationSkipped: boolean;
  truncated: boolean;
};

export type TaskBatchResult = {
  batchId: string;
  title: string;
  created: number;
  batch: TaskBatch;
  tasks: FieldTask[];
};
