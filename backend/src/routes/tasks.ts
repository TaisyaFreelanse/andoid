import { FastifyInstance } from 'fastify';
import { prisma } from '../server';
import { authenticate, AuthenticatedRequest } from '../middleware/auth.middleware';
import { requireRole } from '../middleware/rbac.middleware';
import { createTaskSchema, updateTaskSchema } from '../utils/validator';
import { logger } from '../utils/logger';
import { addTaskToQueue, TaskPriority } from '../queue/bull.config';

export async function taskRoutes(fastify: FastifyInstance) {
  
  fastify.get(
    '/',
    { preHandler: [authenticate] },
    async (_request, _reply) => {
      const tasks = await prisma.task.findMany({
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

      return { tasks };
    }
  );

  
  fastify.get<{ Params: { id: string } }>(
    '/:id',
    { preHandler: [authenticate] },
    async (request, reply) => {
      const task = await prisma.task.findUnique({
        where: { id: request.params.id },
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

      if (!task) {
        return reply.status(404).send({
          error: {
            message: 'Task not found',
            code: 'TASK_NOT_FOUND',
          },
        });
      }

      return { task };
    }
  );

  
  fastify.post(
    '/',
    { preHandler: [authenticate, requireRole('admin', 'operator')] },
    async (request, _reply) => {
      const authRequest = request as AuthenticatedRequest;
      const body = createTaskSchema.parse(request.body);
      const { priority, ...taskData } = request.body as any;

      const task = await prisma.task.create({
        data: {
          userId: authRequest.user.id,
          deviceId: taskData.deviceId,
          proxyId: taskData.proxyId,
          name: body.name,
          type: body.type,
          configJson: body.configJson,
        },
        include: {
          user: {
            select: {
              id: true,
              username: true,
            },
          },
        },
      });

      
      const taskPriority = priority 
        ? (TaskPriority[priority as keyof typeof TaskPriority] || TaskPriority.NORMAL)
        : TaskPriority.NORMAL;

      await addTaskToQueue({
        taskId: task.id,
        type: body.type,
        config: body.configJson,
        deviceId: taskData.deviceId,
        proxyId: taskData.proxyId,
        priority: taskPriority,
      });

      logger.info({ taskId: task.id, userId: authRequest.user.id, priority: taskPriority }, 'Task created and queued');

      return { task };
    }
  );

  
  fastify.patch<{ Params: { id: string } }>(
    '/:id',
    { preHandler: [authenticate, requireRole('admin', 'operator')] },
    async (request, _reply) => {
      const body = updateTaskSchema.parse(request.body);

      const task = await prisma.task.update({
        where: { id: request.params.id },
        data: body,
      });

      logger.info({ taskId: task.id }, 'Task updated');

      return { task };
    }
  );

  
  fastify.delete<{ Params: { id: string } }>(
    '/:id',
    { preHandler: [authenticate, requireRole('admin')] },
    async (request, _reply) => {
      await prisma.task.delete({
        where: { id: request.params.id },
      });

      logger.info({ taskId: request.params.id }, 'Task deleted');

      return { message: 'Task deleted' };
    }
  );
}

