INSERT INTO "SearchIndexQueue" ("entityType", "entityId")
SELECT s.t, s.i FROM (
    SELECT 'tools', t."id" FROM "ToolDocumentation" t
    WHERE EXISTS (SELECT 1 FROM "ToolArtisan" l WHERE l."toolId" = t."id")
    UNION
    SELECT 'media', m."id" FROM "MediaFile" m
    WHERE EXISTS (SELECT 1 FROM "ToolArtisan" l WHERE l."toolId" = m."toolId")
) AS s(t, i)
WHERE NOT EXISTS (
    SELECT 1 FROM "SearchIndexQueue" q WHERE q."entityType" = s.t AND q."entityId" = s.i
);
