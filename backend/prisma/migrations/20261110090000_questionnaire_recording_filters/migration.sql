-- Recording chips and filters (owner, 31 Oct 2026): the recordings list and search read takes
-- newest capture first and page on (capturedAt, id). Every other filter (artisans, researchers,
-- sections and questions, workshops, the session's date and source) already has its index:
-- QuestionnaireRecordingSubject(artisanId), QuestionnaireRecordingTarget(sectionId) and
-- (questionLineageId), QuestionnaireInterview(sessionDate), (workshopId, createdAt) and
-- (designWorkshopId, createdAt), MediaFile(questionnaireInterviewId). Additive only; the same SQL
-- in designer-portal and documentation-portal.
CREATE INDEX IF NOT EXISTS "QuestionnaireRecording_capturedAt_id_idx" ON "QuestionnaireRecording"("capturedAt", "id");
