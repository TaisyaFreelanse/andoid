-- CreateTable
CREATE TABLE "artifacts" (
    "id" TEXT NOT NULL,
    "device_id" TEXT NOT NULL,
    "task_id" TEXT NOT NULL,
    "path" VARCHAR(500) NOT NULL,
    "type" VARCHAR(50) NOT NULL,
    "size" INTEGER,
    "mime_type" VARCHAR(100),
    "captured_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "artifacts_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "artifacts_device_id_idx" ON "artifacts"("device_id");

-- CreateIndex
CREATE INDEX "artifacts_task_id_idx" ON "artifacts"("task_id");

-- CreateIndex
CREATE INDEX "artifacts_captured_at_idx" ON "artifacts"("captured_at");

-- CreateIndex
CREATE INDEX "artifacts_type_idx" ON "artifacts"("type");
