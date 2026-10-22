-- U5 (the backfill check): the U0 mirror from Workshop."questionnaireId" to QuestionnaireBinding fired
-- on UPDATE only, so a workshop CREATED with a questionnaire (the admin form, imports, fixtures) had
-- no primary binding, and the shared start sheet's rule (a form bound to the workshop) refused it.
-- The same function on INSERT; it reads OLD only to compare, and OLD is NULL on an insert. Additive.
DROP TRIGGER IF EXISTS questionnaire_binding_from_workshop_ins ON "Workshop";
CREATE TRIGGER questionnaire_binding_from_workshop_ins AFTER INSERT ON "Workshop"
  FOR EACH ROW WHEN (NEW."questionnaireId" IS NOT NULL) EXECUTE FUNCTION questionnaire_binding_from_workshop();
-- Workshops created with a questionnaire since U0 and still unbound.
INSERT INTO "QuestionnaireBinding" ("id", "questionnaireId", "workshopId", "isPrimary")
SELECT gen_random_uuid()::text, w."questionnaireId", w."id", true
  FROM "Workshop" w
 WHERE w."questionnaireId" IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM "QuestionnaireBinding" b WHERE b."workshopId" = w."id" AND b."isPrimary")
ON CONFLICT DO NOTHING;
