import apiClient from './client';

export type TaskType = 'surfing' | 'parsing' | 'uniqueness' | 'screenshot';
export type TaskStatus = 'pending' | 'assigned' | 'running' | 'completed' | 'failed' | 'cancelled';

export interface Task {
  id: string;
  userId: string | null;
  deviceId: string | null;
  proxyId: string | null;
  name: string;
  type: TaskType;
  configJson: any;
  status: TaskStatus;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  resultJson: any | null;
  user?: {
    id: string;
    username: string;
    email: string;
  };
  device?: {
    id: string;
    name: string | null;
    androidId: string;
  };
  proxy?: {
    id: string;
    host: string;
    port: number;
  };
  taskSteps?: any[];
  parsedData?: any[];
}

export interface CreateTaskData {
  name: string;
  type: TaskType;
  configJson: any;
  deviceId?: string;
  proxyId?: string;
  priority?: number;
}

export const tasksApi = {
  getAll: async (): Promise<Task[]> => {
    const response = await apiClient.get<{ tasks: Task[] }>('/tasks');
    return response.data.tasks;
  },
  getById: async (id: string): Promise<Task> => {
    const response = await apiClient.get<{ task: Task }>(`/tasks/${id}`);
    return response.data.task;
  },
  create: async (data: CreateTaskData): Promise<Task> => {
    const response = await apiClient.post<{ task: Task }>('/tasks', data);
    return response.data.task;
  },
  update: async (id: string, data: Partial<Task>): Promise<Task> => {
    const response = await apiClient.patch<{ task: Task }>(`/tasks/${id}`, data);
    return response.data.task;
  },
  delete: async (id: string): Promise<void> => {
    await apiClient.delete(`/tasks/${id}`);
  },
};

