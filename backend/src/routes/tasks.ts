import { FastifyInstance } from 'fastify';
import { prisma } from '../server';
import { authenticate, AuthenticatedRequest } from '../middleware/auth.middleware';
import { requireRole } from '../middleware/rbac.middleware';
import { createTaskSchema, updateTaskSchema } from '../utils/validator';
import { logger } from '../utils/logger';
import { addTaskToQueue, TaskPriority } from '../queue/bull.config';
import { config } from '../config';
import { storageService } from '../services/storage.service';

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
    async (request, reply) => {
      try {
        const authRequest = request as AuthenticatedRequest;
        const body = createTaskSchema.parse(request.body);
        const { priority, ...taskData } = request.body as any;

        const task = await prisma.task.create({
          data: {
            userId: authRequest.user.id,
            deviceId: taskData.deviceId || null,
            proxyId: taskData.proxyId || null,
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

        // Try to add to queue, but don't fail if queue is disabled
        const queueResult = await addTaskToQueue({
          taskId: task.id,
          type: body.type,
          config: body.configJson,
          deviceId: taskData.deviceId,
          proxyId: taskData.proxyId,
          priority: taskPriority,
        });

        if (queueResult === null) {
          logger.warn({ taskId: task.id }, 'Task queue is disabled, task created but not queued');
        } else {
          logger.info({ taskId: task.id, userId: authRequest.user.id, priority: taskPriority }, 'Task created and queued');
        }

        return { task };
      } catch (error) {
        logger.error({ 
          error: {
            message: error instanceof Error ? error.message : String(error),
            stack: error instanceof Error ? error.stack : undefined,
            code: (error as any)?.code,
          },
          requestId: request.id,
          body: request.body,
        }, 'Error creating task');
        
        return reply.status(500).send({
          error: {
            message: 'Failed to create task',
            code: 'CREATE_TASK_ERROR',
            ...(config.nodeEnv === 'development' && {
              details: error instanceof Error ? error.message : String(error),
            }),
          },
        });
      }
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

  // Get screenshots for a task
  fastify.get<{ Params: { id: string } }>(
    '/:id/screenshots',
    { preHandler: [authenticate] },
    async (request, reply) => {
      const { id } = request.params;

      const task = await prisma.task.findUnique({
        where: { id },
        select: { id: true, name: true, resultJson: true },
      });

      if (!task) {
        return reply.status(404).send({
          error: { message: 'Task not found', code: 'TASK_NOT_FOUND' },
        });
      }

      // Get screenshots from parsed data
      const parsedData = await prisma.parsedData.findMany({
        where: {
          taskId: id,
          screenshotPath: { not: null },
        },
        select: {
          id: true,
          screenshotPath: true,
          parsedAt: true,
          adDomain: true,
          url: true,
        },
        orderBy: { parsedAt: 'desc' },
      });

      // Also check resultJson for screenshots
      const resultJson = task.resultJson as any;
      const resultScreenshots = resultJson?.screenshots || [];

      // Generate presigned URLs
      const screenshots = await Promise.all([
        // From parsed data
        ...parsedData.map(async (item) => {
          try {
            const url = await storageService.getPresignedUrl(item.screenshotPath!, 3600);
            return {
              id: item.id,
              type: 'parsed_data',
              path: item.screenshotPath,
              url,
              domain: item.adDomain,
              sourceUrl: item.url,
              capturedAt: item.parsedAt,
            };
          } catch (error) {
            return {
              id: item.id,
              type: 'parsed_data',
              path: item.screenshotPath,
              url: null,
              domain: item.adDomain,
              sourceUrl: item.url,
              capturedAt: item.parsedAt,
              error: 'Failed to generate URL',
            };
          }
        }),
        // From resultJson
        ...resultScreenshots.map(async (path: string, index: number) => {
          try {
            const url = await storageService.getPresignedUrl(path, 3600);
            return {
              id: `result_${index}`,
              type: 'result',
              path,
              url,
              capturedAt: null,
            };
          } catch (error) {
            return {
              id: `result_${index}`,
              type: 'result',
              path,
              url: null,
              error: 'Failed to generate URL',
            };
          }
        }),
      ]);

      return {
        taskId: id,
        taskName: task.name,
        count: screenshots.length,
        screenshots,
      };
    }
  );

  // Get task result with enriched data (Semrush, screenshots)
  fastify.get<{ Params: { id: string } }>(
    '/:id/result',
    { preHandler: [authenticate] },
    async (request, reply) => {
      const { id } = request.params;
      const { includeScreenshots = 'true', includeSemrush = 'true' } = request.query as {
        includeScreenshots?: string;
        includeSemrush?: string;
      };

      const task = await prisma.task.findUnique({
        where: { id },
        include: {
          device: {
            select: { id: true, name: true, androidId: true },
          },
          parsedData: {
            orderBy: { parsedAt: 'desc' },
          },
        },
      });

      if (!task) {
        return reply.status(404).send({
          error: { message: 'Task not found', code: 'TASK_NOT_FOUND' },
        });
      }

      const result: any = {
        id: task.id,
        name: task.name,
        type: task.type,
        status: task.status,
        device: task.device,
        createdAt: task.createdAt,
        startedAt: task.startedAt,
        completedAt: task.completedAt,
        resultJson: task.resultJson,
        config: task.configJson,
      };

      // Enrich parsed data
      if (task.parsedData.length > 0) {
        result.parsedData = await Promise.all(task.parsedData.map(async (pd) => {
          const enriched: any = { ...pd };

          // Add screenshot URL
          if (includeScreenshots === 'true' && pd.screenshotPath) {
            try {
              enriched.screenshotUrl = await storageService.getPresignedUrl(pd.screenshotPath, 3600);
            } catch (error) {
              enriched.screenshotUrl = null;
            }
          }

          // Add Semrush data
          if (includeSemrush === 'true' && pd.adDomain) {
            const semrush = await prisma.semrushData.findUnique({
              where: { domain: pd.adDomain },
            });
            enriched.semrush = semrush?.dataJson || null;
          }

          return enriched;
        }));
      }

      // Extract domains summary
      const domains = task.parsedData
        .map(pd => pd.adDomain)
        .filter((d, i, arr) => d && arr.indexOf(d) === i);
      
      result.summary = {
        domainsFound: domains.length,
        screenshotsCount: task.parsedData.filter(pd => pd.screenshotPath).length,
        domains,
      };

      return { result };
    }
  );

  // Retry failed task
  fastify.post<{ Params: { id: string } }>(
    '/:id/retry',
    { preHandler: [authenticate, requireRole('admin', 'operator')] },
    async (request, reply) => {
      const { id } = request.params;

      const task = await prisma.task.findUnique({
        where: { id },
      });

      if (!task) {
        return reply.status(404).send({
          error: { message: 'Task not found', code: 'TASK_NOT_FOUND' },
        });
      }

      if (task.status !== 'failed' && task.status !== 'cancelled') {
        return reply.status(400).send({
          error: { 
            message: 'Only failed or cancelled tasks can be retried', 
            code: 'INVALID_STATUS' 
          },
        });
      }

      // Reset task status
      const updatedTask = await prisma.task.update({
        where: { id },
        data: {
          status: 'pending',
          startedAt: null,
          completedAt: null,
          resultJson: undefined,
        },
      });

      // Re-add to queue
      const queueResult = await addTaskToQueue({
        taskId: task.id,
        type: task.type,
        config: task.configJson as any,
        deviceId: task.deviceId || undefined,
        proxyId: task.proxyId || undefined,
        priority: TaskPriority.HIGH,
      });

      logger.info({ taskId: id }, 'Task retried');

      return { 
        success: true, 
        task: updatedTask,
        queued: queueResult !== null,
      };
    }
  );

  // Bulk cancel tasks
  fastify.post(
    '/bulk/cancel',
    { preHandler: [authenticate, requireRole('admin', 'operator')] },
    async (request, reply) => {
      const { taskIds } = request.body as { taskIds: string[] };

      if (!taskIds || !Array.isArray(taskIds) || taskIds.length === 0) {
        return reply.status(400).send({
          error: { message: 'Task IDs array required', code: 'MISSING_TASK_IDS' },
        });
      }

      const result = await prisma.task.updateMany({
        where: {
          id: { in: taskIds },
          status: { in: ['pending', 'assigned', 'running'] },
        },
        data: {
          status: 'cancelled',
          completedAt: new Date(),
        },
      });

      logger.info({ taskIds, count: result.count }, 'Bulk cancel tasks');

      return { 
        success: true, 
        cancelled: result.count,
        message: `Cancelled ${result.count} tasks`,
      };
    }
  );

  // Get tasks statistics
  fastify.get(
    '/stats/summary',
    { preHandler: [authenticate] },
    async (request, _reply) => {
      const { deviceId, dateFrom, dateTo } = request.query as {
        deviceId?: string;
        dateFrom?: string;
        dateTo?: string;
      };

      const where: any = {};
      if (deviceId) where.deviceId = deviceId;
      if (dateFrom || dateTo) {
        where.createdAt = {};
        if (dateFrom) where.createdAt.gte = new Date(dateFrom);
        if (dateTo) where.createdAt.lte = new Date(dateTo);
      }

      const [
        totalTasks,
        byStatus,
        byType,
        recent24h,
        averageExecutionTime
      ] = await Promise.all([
        prisma.task.count({ where }),
        prisma.task.groupBy({
          by: ['status'],
          where,
          _count: { status: true },
        }),
        prisma.task.groupBy({
          by: ['type'],
          where,
          _count: { type: true },
        }),
        prisma.task.count({
          where: {
            ...where,
            createdAt: { gte: new Date(Date.now() - 24 * 60 * 60 * 1000) },
          },
        }),
        prisma.task.findMany({
          where: {
            ...where,
            status: 'completed',
            startedAt: { not: null },
            completedAt: { not: null },
          },
          select: { startedAt: true, completedAt: true },
          take: 100,
        }).then(tasks => {
          if (tasks.length === 0) return null;
          const totalMs = tasks.reduce((sum, t) => {
            const duration = t.completedAt!.getTime() - t.startedAt!.getTime();
            return sum + duration;
          }, 0);
          return Math.round(totalMs / tasks.length / 1000); // seconds
        }),
      ]);

      return {
        stats: {
          total: totalTasks,
          recent24h,
          averageExecutionTimeSeconds: averageExecutionTime,
          byStatus: Object.fromEntries(byStatus.map(s => [s.status, s._count.status])),
          byType: Object.fromEntries(byType.map(t => [t.type, t._count.type])),
        },
      };
    }
  );
}

