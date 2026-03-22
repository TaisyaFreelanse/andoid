-- AlterTable: widen android_id and aaid columns to avoid P2000 errors
ALTER TABLE "devices" ALTER COLUMN "android_id" TYPE VARCHAR(64);
ALTER TABLE "devices" ALTER COLUMN "aaid" TYPE VARCHAR(64);
