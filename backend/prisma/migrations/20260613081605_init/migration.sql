-- CreateEnum
CREATE TYPE "UserRole" AS ENUM ('ADMIN', 'RESEARCHER');

-- CreateEnum
CREATE TYPE "AuthProvider" AS ENUM ('LOCAL', 'GOOGLE');

-- CreateEnum
CREATE TYPE "RecordStatus" AS ENUM ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED');

-- CreateEnum
CREATE TYPE "MediaType" AS ENUM ('IMAGE', 'VIDEO', 'AUDIO', 'PDF', 'DOCUMENT', 'OTHER');

-- CreateEnum
CREATE TYPE "ProductType" AS ENUM ('FINISHED_GOOD', 'SAMPLE', 'RAW_MATERIAL', 'COMPONENT', 'PACKAGING', 'OTHER');

-- CreateEnum
CREATE TYPE "MarketDemand" AS ENUM ('LOW', 'MEDIUM', 'HIGH', 'SEASONAL', 'UNKNOWN');

-- CreateEnum
CREATE TYPE "MakerType" AS ENUM ('ARTISAN', 'LOCAL_BLACKSMITH', 'CARPENTER', 'WORKSHOP', 'FACTORY', 'UNKNOWN', 'OTHER');

-- CreateEnum
CREATE TYPE "TraditionType" AS ENUM ('TRADITIONAL', 'MODERN', 'HYBRID', 'UNKNOWN');

-- CreateEnum
CREATE TYPE "ReviewRecordType" AS ENUM ('ARTISAN', 'WORKSHOP', 'PRODUCT', 'TOOL', 'MEDIA');

-- CreateTable
CREATE TABLE "User" (
    "id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "passwordHash" TEXT,
    "avatarUrl" TEXT,
    "role" "UserRole" NOT NULL DEFAULT 'RESEARCHER',
    "authProvider" "AuthProvider" NOT NULL DEFAULT 'LOCAL',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "User_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Craft" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "localName" TEXT,
    "category" TEXT,
    "description" TEXT,
    "place" TEXT,
    "extraMetadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "createdById" TEXT,

    CONSTRAINT "Craft_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Location" (
    "id" TEXT NOT NULL,
    "latitude" DOUBLE PRECISION NOT NULL,
    "longitude" DOUBLE PRECISION NOT NULL,
    "altitude" DOUBLE PRECISION,
    "accuracy" DOUBLE PRECISION,
    "address" TEXT,
    "placeName" TEXT,
    "capturedAt" TIMESTAMP(3),
    "extraMetadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Location_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Artisan" (
    "id" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "localName" TEXT,
    "gender" TEXT,
    "phone" TEXT,
    "email" TEXT,
    "place" TEXT NOT NULL,
    "address" TEXT,
    "notes" TEXT,
    "status" "RecordStatus" NOT NULL DEFAULT 'PENDING',
    "reviewNotes" TEXT,
    "reviewedById" TEXT,
    "reviewedAt" TIMESTAMP(3),
    "extraMetadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "craftId" TEXT,
    "locationId" TEXT,
    "createdById" TEXT NOT NULL,

    CONSTRAINT "Artisan_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Workshop" (
    "id" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "date" TIMESTAMP(3) NOT NULL,
    "place" TEXT NOT NULL,
    "description" TEXT,
    "notes" TEXT,
    "status" "RecordStatus" NOT NULL DEFAULT 'PENDING',
    "reviewNotes" TEXT,
    "reviewedById" TEXT,
    "reviewedAt" TIMESTAMP(3),
    "extraMetadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "locationId" TEXT,
    "createdById" TEXT NOT NULL,

    CONSTRAINT "Workshop_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "WorkshopArtisan" (
    "workshopId" TEXT NOT NULL,
    "artisanId" TEXT NOT NULL,
    "role" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "WorkshopArtisan_pkey" PRIMARY KEY ("workshopId","artisanId")
);

-- CreateTable
CREATE TABLE "ProductDocumentation" (
    "id" TEXT NOT NULL,
    "craftName" TEXT NOT NULL,
    "place" TEXT NOT NULL,
    "artisanName" TEXT NOT NULL,
    "productName" TEXT NOT NULL,
    "localName" TEXT,
    "productType" "ProductType" NOT NULL DEFAULT 'OTHER',
    "timeTakenToCompleteProduct" TEXT,
    "size" TEXT,
    "costOfMaking" DECIMAL(12,2),
    "sellingPrice" DECIMAL(12,2),
    "marketDemand" "MarketDemand" NOT NULL DEFAULT 'UNKNOWN',
    "rawMaterialsUsed" TEXT,
    "mainToolsUsed" TEXT,
    "productFunctionUse" TEXT,
    "remarks" TEXT,
    "status" "RecordStatus" NOT NULL DEFAULT 'PENDING',
    "reviewNotes" TEXT,
    "reviewedById" TEXT,
    "reviewedAt" TIMESTAMP(3),
    "extraMetadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "artisanId" TEXT,
    "craftId" TEXT,
    "workshopId" TEXT,
    "locationId" TEXT,
    "createdById" TEXT NOT NULL,

    CONSTRAINT "ProductDocumentation_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ToolDocumentation" (
    "id" TEXT NOT NULL,
    "craftName" TEXT NOT NULL,
    "place" TEXT NOT NULL,
    "artisanName" TEXT NOT NULL,
    "toolkitName" TEXT NOT NULL,
    "localName" TEXT,
    "englishName" TEXT,
    "processUsedIn" TEXT,
    "material" TEXT,
    "yearsInUse" INTEGER,
    "height" DECIMAL(10,2),
    "width" DECIMAL(10,2),
    "thickness" DECIMAL(10,2),
    "weight" DECIMAL(10,2),
    "radius" DECIMAL(10,2),
    "maker" "MakerType" NOT NULL DEFAULT 'UNKNOWN',
    "traditionType" "TraditionType" NOT NULL DEFAULT 'UNKNOWN',
    "replacementCost" DECIMAL(12,2),
    "suggestionsForToolImprovement" TEXT,
    "remarks" TEXT,
    "status" "RecordStatus" NOT NULL DEFAULT 'PENDING',
    "reviewNotes" TEXT,
    "reviewedById" TEXT,
    "reviewedAt" TIMESTAMP(3),
    "extraMetadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "artisanId" TEXT,
    "craftId" TEXT,
    "workshopId" TEXT,
    "locationId" TEXT,
    "createdById" TEXT NOT NULL,

    CONSTRAINT "ToolDocumentation_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "MediaFile" (
    "id" TEXT NOT NULL,
    "originalFilename" TEXT NOT NULL,
    "mediaType" "MediaType" NOT NULL,
    "mimeType" TEXT NOT NULL,
    "sizeBytes" BIGINT NOT NULL,
    "bucket" TEXT NOT NULL,
    "objectKey" TEXT NOT NULL,
    "url" TEXT,
    "caption" TEXT,
    "checksum" TEXT,
    "linkedRecordType" TEXT,
    "linkedRecordId" TEXT,
    "status" "RecordStatus" NOT NULL DEFAULT 'PENDING',
    "reviewNotes" TEXT,
    "reviewedById" TEXT,
    "reviewedAt" TIMESTAMP(3),
    "extraMetadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,
    "uploadedById" TEXT NOT NULL,
    "artisanId" TEXT,
    "workshopId" TEXT,
    "productId" TEXT,
    "toolId" TEXT,
    "locationId" TEXT,

    CONSTRAINT "MediaFile_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ReviewLog" (
    "id" TEXT NOT NULL,
    "recordType" "ReviewRecordType" NOT NULL,
    "recordId" TEXT NOT NULL,
    "status" "RecordStatus" NOT NULL,
    "notes" TEXT,
    "reviewerId" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ReviewLog_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "User_email_key" ON "User"("email");

-- CreateIndex
CREATE INDEX "User_role_idx" ON "User"("role");

-- CreateIndex
CREATE UNIQUE INDEX "Craft_name_key" ON "Craft"("name");

-- CreateIndex
CREATE INDEX "Craft_name_idx" ON "Craft"("name");

-- CreateIndex
CREATE INDEX "Craft_place_idx" ON "Craft"("place");

-- CreateIndex
CREATE INDEX "Location_latitude_longitude_idx" ON "Location"("latitude", "longitude");

-- CreateIndex
CREATE INDEX "Location_placeName_idx" ON "Location"("placeName");

-- CreateIndex
CREATE INDEX "Artisan_name_idx" ON "Artisan"("name");

-- CreateIndex
CREATE INDEX "Artisan_place_idx" ON "Artisan"("place");

-- CreateIndex
CREATE INDEX "Artisan_craftId_idx" ON "Artisan"("craftId");

-- CreateIndex
CREATE INDEX "Artisan_createdById_idx" ON "Artisan"("createdById");

-- CreateIndex
CREATE INDEX "Artisan_status_idx" ON "Artisan"("status");

-- CreateIndex
CREATE INDEX "Workshop_date_idx" ON "Workshop"("date");

-- CreateIndex
CREATE INDEX "Workshop_place_idx" ON "Workshop"("place");

-- CreateIndex
CREATE INDEX "Workshop_createdById_idx" ON "Workshop"("createdById");

-- CreateIndex
CREATE INDEX "Workshop_status_idx" ON "Workshop"("status");

-- CreateIndex
CREATE INDEX "WorkshopArtisan_artisanId_idx" ON "WorkshopArtisan"("artisanId");

-- CreateIndex
CREATE INDEX "ProductDocumentation_productName_idx" ON "ProductDocumentation"("productName");

-- CreateIndex
CREATE INDEX "ProductDocumentation_craftName_idx" ON "ProductDocumentation"("craftName");

-- CreateIndex
CREATE INDEX "ProductDocumentation_place_idx" ON "ProductDocumentation"("place");

-- CreateIndex
CREATE INDEX "ProductDocumentation_artisanName_idx" ON "ProductDocumentation"("artisanName");

-- CreateIndex
CREATE INDEX "ProductDocumentation_artisanId_idx" ON "ProductDocumentation"("artisanId");

-- CreateIndex
CREATE INDEX "ProductDocumentation_craftId_idx" ON "ProductDocumentation"("craftId");

-- CreateIndex
CREATE INDEX "ProductDocumentation_workshopId_idx" ON "ProductDocumentation"("workshopId");

-- CreateIndex
CREATE INDEX "ProductDocumentation_createdById_idx" ON "ProductDocumentation"("createdById");

-- CreateIndex
CREATE INDEX "ProductDocumentation_status_idx" ON "ProductDocumentation"("status");

-- CreateIndex
CREATE INDEX "ToolDocumentation_toolkitName_idx" ON "ToolDocumentation"("toolkitName");

-- CreateIndex
CREATE INDEX "ToolDocumentation_craftName_idx" ON "ToolDocumentation"("craftName");

-- CreateIndex
CREATE INDEX "ToolDocumentation_place_idx" ON "ToolDocumentation"("place");

-- CreateIndex
CREATE INDEX "ToolDocumentation_artisanName_idx" ON "ToolDocumentation"("artisanName");

-- CreateIndex
CREATE INDEX "ToolDocumentation_artisanId_idx" ON "ToolDocumentation"("artisanId");

-- CreateIndex
CREATE INDEX "ToolDocumentation_craftId_idx" ON "ToolDocumentation"("craftId");

-- CreateIndex
CREATE INDEX "ToolDocumentation_workshopId_idx" ON "ToolDocumentation"("workshopId");

-- CreateIndex
CREATE INDEX "ToolDocumentation_createdById_idx" ON "ToolDocumentation"("createdById");

-- CreateIndex
CREATE INDEX "ToolDocumentation_status_idx" ON "ToolDocumentation"("status");

-- CreateIndex
CREATE UNIQUE INDEX "MediaFile_objectKey_key" ON "MediaFile"("objectKey");

-- CreateIndex
CREATE INDEX "MediaFile_mediaType_idx" ON "MediaFile"("mediaType");

-- CreateIndex
CREATE INDEX "MediaFile_uploadedById_idx" ON "MediaFile"("uploadedById");

-- CreateIndex
CREATE INDEX "MediaFile_linkedRecordType_linkedRecordId_idx" ON "MediaFile"("linkedRecordType", "linkedRecordId");

-- CreateIndex
CREATE INDEX "MediaFile_createdAt_idx" ON "MediaFile"("createdAt");

-- CreateIndex
CREATE INDEX "MediaFile_status_idx" ON "MediaFile"("status");

-- CreateIndex
CREATE INDEX "ReviewLog_recordType_recordId_idx" ON "ReviewLog"("recordType", "recordId");

-- CreateIndex
CREATE INDEX "ReviewLog_reviewerId_idx" ON "ReviewLog"("reviewerId");

-- CreateIndex
CREATE INDEX "ReviewLog_createdAt_idx" ON "ReviewLog"("createdAt");

-- AddForeignKey
ALTER TABLE "Craft" ADD CONSTRAINT "Craft_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Artisan" ADD CONSTRAINT "Artisan_craftId_fkey" FOREIGN KEY ("craftId") REFERENCES "Craft"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Artisan" ADD CONSTRAINT "Artisan_locationId_fkey" FOREIGN KEY ("locationId") REFERENCES "Location"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Artisan" ADD CONSTRAINT "Artisan_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Workshop" ADD CONSTRAINT "Workshop_locationId_fkey" FOREIGN KEY ("locationId") REFERENCES "Location"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Workshop" ADD CONSTRAINT "Workshop_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WorkshopArtisan" ADD CONSTRAINT "WorkshopArtisan_workshopId_fkey" FOREIGN KEY ("workshopId") REFERENCES "Workshop"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "WorkshopArtisan" ADD CONSTRAINT "WorkshopArtisan_artisanId_fkey" FOREIGN KEY ("artisanId") REFERENCES "Artisan"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ProductDocumentation" ADD CONSTRAINT "ProductDocumentation_artisanId_fkey" FOREIGN KEY ("artisanId") REFERENCES "Artisan"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ProductDocumentation" ADD CONSTRAINT "ProductDocumentation_craftId_fkey" FOREIGN KEY ("craftId") REFERENCES "Craft"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ProductDocumentation" ADD CONSTRAINT "ProductDocumentation_workshopId_fkey" FOREIGN KEY ("workshopId") REFERENCES "Workshop"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ProductDocumentation" ADD CONSTRAINT "ProductDocumentation_locationId_fkey" FOREIGN KEY ("locationId") REFERENCES "Location"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ProductDocumentation" ADD CONSTRAINT "ProductDocumentation_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ToolDocumentation" ADD CONSTRAINT "ToolDocumentation_artisanId_fkey" FOREIGN KEY ("artisanId") REFERENCES "Artisan"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ToolDocumentation" ADD CONSTRAINT "ToolDocumentation_craftId_fkey" FOREIGN KEY ("craftId") REFERENCES "Craft"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ToolDocumentation" ADD CONSTRAINT "ToolDocumentation_workshopId_fkey" FOREIGN KEY ("workshopId") REFERENCES "Workshop"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ToolDocumentation" ADD CONSTRAINT "ToolDocumentation_locationId_fkey" FOREIGN KEY ("locationId") REFERENCES "Location"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ToolDocumentation" ADD CONSTRAINT "ToolDocumentation_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MediaFile" ADD CONSTRAINT "MediaFile_uploadedById_fkey" FOREIGN KEY ("uploadedById") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MediaFile" ADD CONSTRAINT "MediaFile_artisanId_fkey" FOREIGN KEY ("artisanId") REFERENCES "Artisan"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MediaFile" ADD CONSTRAINT "MediaFile_workshopId_fkey" FOREIGN KEY ("workshopId") REFERENCES "Workshop"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MediaFile" ADD CONSTRAINT "MediaFile_productId_fkey" FOREIGN KEY ("productId") REFERENCES "ProductDocumentation"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MediaFile" ADD CONSTRAINT "MediaFile_toolId_fkey" FOREIGN KEY ("toolId") REFERENCES "ToolDocumentation"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MediaFile" ADD CONSTRAINT "MediaFile_locationId_fkey" FOREIGN KEY ("locationId") REFERENCES "Location"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ReviewLog" ADD CONSTRAINT "ReviewLog_reviewerId_fkey" FOREIGN KEY ("reviewerId") REFERENCES "User"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
