"""The two task statuses the queue worker's Monday summary counts by, kept apart from the tasks
router so the worker can read them without importing FastAPI (app/api/routes/tasks.py imports them
back; backend/tests/test_import_footprint.py pins the worker's import graph)."""

# The assignee's "I have finished" — a CLAIM awaiting a decision, not the decision. Named for the
# act that provably happened (somebody submitted it) rather than for a reviewer who may not exist
# yet: "IN_REVIEW" would assert that a person is looking at it, which the row cannot support, and
# "DONE_PENDING_REVIEW" would put the substring DONE into a value that is emphatically not done —
# one careless `"DONE" in task.status` and an unapproved task counts as finished repository-wide.
# The word the OWNER uses ("under review") is the assignee-facing LABEL, see STATUS_LABELS.
SUBMITTED = "SUBMITTED"

# WORK THE ASSIGNEE STILL OWES. Unchanged on purpose: this is the set `is_overdue` consults, and
# SUBMITTED must stay out of it. A task handed in on the 1st and approved on the 12th would
# otherwise flip to overdue on the 8th and put the reviewer's delay on the researcher's record.
LIVE_STATUSES = {"OPEN", "IN_PROGRESS"}
