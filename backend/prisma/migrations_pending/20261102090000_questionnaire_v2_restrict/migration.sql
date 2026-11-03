ALTER TABLE "QuestionnaireInterviewArtisan" DROP CONSTRAINT "QuestionnaireInterviewArtisan_artisanId_fkey";
ALTER TABLE "QuestionnaireInterviewArtisan" ADD CONSTRAINT "QuestionnaireInterviewArtisan_artisanId_fkey"
  FOREIGN KEY ("artisanId") REFERENCES "Artisan"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE "MediaFile" DROP CONSTRAINT "MediaFile_questionnaireInterviewId_fkey";
ALTER TABLE "MediaFile" ADD CONSTRAINT "MediaFile_questionnaireInterviewId_fkey"
  FOREIGN KEY ("questionnaireInterviewId") REFERENCES "QuestionnaireInterview"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
