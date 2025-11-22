import { Task, TaskType, TaskStatus } from '@prisma/client';

export type TaskCreateInput = {
  userId: string;
  deviceId?: string;
  proxyId?: string;
  name: string;
  type: TaskType;
  configJson: any;
};

export type TaskUpdateInput = {
  status?: TaskStatus;
  resultJson?: any;
  startedAt?: Date;
  completedAt?: Date;
};

export type TaskWithRelations = Task & {
  user?: {
    id: string;
    username: string;
    email: string;
  };
  device?: {
    id: string;
    name: string;
    androidId: string;
  };
  proxy?: {
    id: string;
    host: string;
    port: number;
  };
  taskSteps?: any[];
  parsedData?: any[];
};

export type TaskJobData = {
  taskId: string;
  type: TaskType;
  config: any;
  deviceId?: string;
  proxyId?: string;
};

