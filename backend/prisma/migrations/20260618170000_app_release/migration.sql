-- Over-the-air Android release pushed by the master admin; clients self-update to the highest versionCode.
CREATE TABLE "AppRelease" (
    "id" TEXT NOT NULL,
    "versionCode" INTEGER NOT NULL,
    "versionName" TEXT NOT NULL,
    "objectKey" TEXT NOT NULL,
    "url" TEXT,
    "notes" TEXT,
    "publishedById" TEXT,
    "publishedAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "AppRelease_pkey" PRIMARY KEY ("id")
);

CREATE INDEX "AppRelease_versionCode_idx" ON "AppRelease"("versionCode");

ALTER TABLE "AppRelease" ADD CONSTRAINT "AppRelease_publishedById_fkey" FOREIGN KEY ("publishedById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;
