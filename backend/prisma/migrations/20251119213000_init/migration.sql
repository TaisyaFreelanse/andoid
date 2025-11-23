
CREATE TYPE "UserRole" AS ENUM ('admin', 'operator', 'viewer');


CREATE TYPE "DeviceStatus" AS ENUM ('online', 'offline', 'busy', 'error');


CREATE TYPE "BrowserType" AS ENUM ('chrome', 'webview');


CREATE TYPE "TaskType" AS ENUM ('surfing', 'parsing', 'uniqueness', 'screenshot');


CREATE TYPE "TaskStatus" AS ENUM ('pending', 'assigned', 'running', 'completed', 'failed', 'cancelled');


CREATE TYPE "TaskStepStatus" AS ENUM ('pending', 'running', 'completed', 'failed');


CREATE TYPE "ProxyType" AS ENUM ('http', 'https', 'socks5');


CREATE TYPE "ProxyStatus" AS ENUM ('active', 'inactive', 'error');


CREATE TABLE "users" (
    "id" TEXT NOT NULL,
    "username" VARCHAR(50) NOT NULL,
    "email" VARCHAR(255) NOT NULL,
    "password_hash" VARCHAR(255) NOT NULL,
    "role" "UserRole" NOT NULL DEFAULT 'viewer',
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "users_pkey" PRIMARY KEY ("id")
);


CREATE TABLE "devices" (
    "id" TEXT NOT NULL,
    "name" VARCHAR(100),
    "android_id" VARCHAR(16) NOT NULL,
    "aaid" VARCHAR(36),
    "ip_address" INET,
    "status" "DeviceStatus" NOT NULL DEFAULT 'offline',
    "last_heartbeat" TIMESTAMP(3),
    "browser_type" "BrowserType",
    "agent_token" VARCHAR(255) NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "devices_pkey" PRIMARY KEY ("id")
);


CREATE TABLE "tasks" (
    "id" TEXT NOT NULL,
    "user_id" TEXT,
    "device_id" TEXT,
    "proxy_id" TEXT,
    "name" VARCHAR(255) NOT NULL,
    "type" "TaskType" NOT NULL,
    "config_json" JSONB NOT NULL,
    "status" "TaskStatus" NOT NULL DEFAULT 'pending',
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "started_at" TIMESTAMP(3),
    "completed_at" TIMESTAMP(3),
    "result_json" JSONB,

    CONSTRAINT "tasks_pkey" PRIMARY KEY ("id")
);


CREATE TABLE "task_steps" (
    "id" TEXT NOT NULL,
    "task_id" TEXT NOT NULL,
    "step_type" VARCHAR(50) NOT NULL,
    "step_config" JSONB NOT NULL,
    "order" INTEGER NOT NULL,
    "status" "TaskStepStatus" NOT NULL DEFAULT 'pending',
    "result_json" JSONB,
    "executed_at" TIMESTAMP(3),

    CONSTRAINT "task_steps_pkey" PRIMARY KEY ("id")
);


CREATE TABLE "proxies" (
    "id" TEXT NOT NULL,
    "host" VARCHAR(255) NOT NULL,
    "port" INTEGER NOT NULL,
    "username" VARCHAR(255),
    "password_encrypted" VARCHAR(255),
    "type" "ProxyType" NOT NULL,
    "status" "ProxyStatus" NOT NULL DEFAULT 'active',
    "country" VARCHAR(2),
    "timezone" VARCHAR(50),
    "last_used" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "proxies_pkey" PRIMARY KEY ("id")
);


CREATE TABLE "parsed_data" (
    "id" TEXT NOT NULL,
    "task_id" TEXT NOT NULL,
    "url" TEXT NOT NULL,
    "ad_url" TEXT,
    "ad_domain" VARCHAR(255),
    "screenshot_path" VARCHAR(500),
    "parsed_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "parsed_data_pkey" PRIMARY KEY ("id")
);


CREATE TABLE "semrush_data" (
    "id" TEXT NOT NULL,
    "domain" VARCHAR(255) NOT NULL,
    "data_json" JSONB NOT NULL,
    "checked_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "expires_at" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "semrush_data_pkey" PRIMARY KEY ("id")
);


CREATE TABLE "device_fingerprints" (
    "id" TEXT NOT NULL,
    "device_id" TEXT NOT NULL,
    "android_id" VARCHAR(16) NOT NULL,
    "aaid" VARCHAR(36),
    "user_agent" TEXT,
    "timezone" VARCHAR(50),
    "latitude" DECIMAL(10,8),
    "longitude" DECIMAL(11,8),
    "build_prop_hash" VARCHAR(64),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "device_fingerprints_pkey" PRIMARY KEY ("id")
);


CREATE TABLE "audit_logs" (
    "id" TEXT NOT NULL,
    "user_id" TEXT,
    "action" VARCHAR(100) NOT NULL,
    "resource_type" VARCHAR(50) NOT NULL,
    "resource_id" UUID,
    "details" JSONB,
    "timestamp" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "audit_logs_pkey" PRIMARY KEY ("id")
);


CREATE UNIQUE INDEX "users_username_key" ON "users"("username");


CREATE UNIQUE INDEX "users_email_key" ON "users"("email");


CREATE INDEX "users_role_idx" ON "users"("role");


CREATE UNIQUE INDEX "devices_android_id_key" ON "devices"("android_id");


CREATE INDEX "devices_status_idx" ON "devices"("status");


CREATE INDEX "devices_last_heartbeat_idx" ON "devices"("last_heartbeat");


CREATE INDEX "tasks_user_id_idx" ON "tasks"("user_id");


CREATE INDEX "tasks_device_id_idx" ON "tasks"("device_id");


CREATE INDEX "tasks_status_idx" ON "tasks"("status");


CREATE INDEX "tasks_created_at_idx" ON "tasks"("created_at");


CREATE INDEX "tasks_type_idx" ON "tasks"("type");


CREATE INDEX "task_steps_task_id_idx" ON "task_steps"("task_id");


CREATE INDEX "task_steps_task_id_order_idx" ON "task_steps"("task_id", "order");


CREATE INDEX "task_steps_status_idx" ON "task_steps"("status");


CREATE INDEX "proxies_status_idx" ON "proxies"("status");


CREATE INDEX "proxies_type_idx" ON "proxies"("type");


CREATE INDEX "proxies_country_idx" ON "proxies"("country");


CREATE INDEX "proxies_last_used_idx" ON "proxies"("last_used");


CREATE INDEX "parsed_data_task_id_idx" ON "parsed_data"("task_id");


CREATE INDEX "parsed_data_ad_domain_idx" ON "parsed_data"("ad_domain");


CREATE INDEX "parsed_data_parsed_at_idx" ON "parsed_data"("parsed_at");


CREATE UNIQUE INDEX "parsed_data_task_id_ad_domain_key" ON "parsed_data"("task_id", "ad_domain");


CREATE UNIQUE INDEX "semrush_data_domain_key" ON "semrush_data"("domain");


CREATE INDEX "semrush_data_expires_at_idx" ON "semrush_data"("expires_at");


CREATE INDEX "device_fingerprints_device_id_idx" ON "device_fingerprints"("device_id");


CREATE INDEX "device_fingerprints_device_id_created_at_idx" ON "device_fingerprints"("device_id", "created_at");


CREATE INDEX "audit_logs_user_id_idx" ON "audit_logs"("user_id");


CREATE INDEX "audit_logs_resource_type_resource_id_idx" ON "audit_logs"("resource_type", "resource_id");


CREATE INDEX "audit_logs_timestamp_idx" ON "audit_logs"("timestamp");


CREATE INDEX "audit_logs_action_idx" ON "audit_logs"("action");


ALTER TABLE "tasks" ADD CONSTRAINT "tasks_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;


ALTER TABLE "tasks" ADD CONSTRAINT "tasks_device_id_fkey" FOREIGN KEY ("device_id") REFERENCES "devices"("id") ON DELETE SET NULL ON UPDATE CASCADE;


ALTER TABLE "tasks" ADD CONSTRAINT "tasks_proxy_id_fkey" FOREIGN KEY ("proxy_id") REFERENCES "proxies"("id") ON DELETE SET NULL ON UPDATE CASCADE;


ALTER TABLE "task_steps" ADD CONSTRAINT "task_steps_task_id_fkey" FOREIGN KEY ("task_id") REFERENCES "tasks"("id") ON DELETE CASCADE ON UPDATE CASCADE;


ALTER TABLE "parsed_data" ADD CONSTRAINT "parsed_data_task_id_fkey" FOREIGN KEY ("task_id") REFERENCES "tasks"("id") ON DELETE CASCADE ON UPDATE CASCADE;


ALTER TABLE "device_fingerprints" ADD CONSTRAINT "device_fingerprints_device_id_fkey" FOREIGN KEY ("device_id") REFERENCES "devices"("id") ON DELETE CASCADE ON UPDATE CASCADE;


ALTER TABLE "audit_logs" ADD CONSTRAINT "audit_logs_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "users"("id") ON DELETE SET NULL ON UPDATE CASCADE;
