import { TaskStatus, TaskType } from '@prisma/client';
import { prisma } from '../server';

export class TaskService {
  async getAllTasks(filters?: {
    userId?: string;
    deviceId?: string;
    status?: TaskStatus;
    type?: TaskType;
  }) {
    const where: any = {};
    if (filters?.userId) where.userId = filters.userId;
    if (filters?.deviceId) where.deviceId = filters.deviceId;
    if (filters?.status) where.status = filters.status;
    if (filters?.type) where.type = filters.type;

    return prisma.task.findMany({
      where,
      include: {
        user: {
          select: {
            id: true,
            username: true,
            email: true,
          },
        },
        device: {
          select: {
            id: true,
            name: true,
            androidId: true,
          },
        },
        proxy: {
          select: {
            id: true,
            host: true,
            port: true,
          },
        },
      },
      orderBy: { createdAt: 'desc' },
    });
  }

  async getTaskById(id: string) {
    return prisma.task.findUnique({
      where: { id },
      include: {
        user: {
          select: {
            id: true,
            username: true,
            email: true,
          },
        },
        device: true,
        proxy: true,
        taskSteps: {
          orderBy: { order: 'asc' },
        },
        parsedData: {
          orderBy: { parsedAt: 'desc' },
        },
      },
    });
  }

  async createTask(data: {
    userId: string;
    deviceId?: string;
    proxyId?: string;
    name: string;
    type: TaskType;
    configJson: any;
  }) {
    return prisma.task.create({
      data,
      include: {
        user: {
          select: {
            id: true,
            username: true,
          },
        },
      },
    });
  }

  async updateTaskStatus(id: string, status: TaskStatus, resultJson?: any) {
    const updateData: any = {
      status,
    };

    if (status === 'running' && !resultJson) {
      updateData.startedAt = new Date();
    }

    if (status === 'completed' || status === 'failed') {
      updateData.completedAt = new Date();
      if (resultJson) {
        updateData.resultJson = resultJson;
      }
    }

    return prisma.task.update({
      where: { id },
      data: updateData,
    });
  }

  async getPendingTasks(deviceId?: string) {
    const where: any = {
      status: 'pending',
    };

    if (deviceId) {
      where.OR = [
        { deviceId },
        { deviceId: null },
      ];
    } else {
      where.deviceId = null;
    }

    return prisma.task.findMany({
      where,
      orderBy: { createdAt: 'asc' },
      take: 1,
    });
  }

  async assignTaskToDevice(taskId: string, deviceId: string) {
    return prisma.task.update({
      where: { id: taskId },
      data: {
        deviceId,
        status: 'assigned',
      },
    });
  }

  async updateTask(taskId: string, data: { proxyId?: string; deviceId?: string }) {
    return prisma.task.update({
      where: { id: taskId },
      data,
    });
  }
}

export const taskService = new TaskService();

