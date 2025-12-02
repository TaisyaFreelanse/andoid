import { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import { randomUUID } from 'crypto';
import { prisma } from '../server';
import { registerDeviceSchema, heartbeatSchema, taskResultSchema } from '../utils/validator';
import { logger } from '../utils/logger';
import { storageService } from '../services/storage.service';
import { semrushService } from '../services/semrush.service';

export async function agentRoutes(fastify: FastifyInstance) {
  
  fastify.post('/register', async (request: FastifyRequest, _reply: FastifyReply) => {
    const body = registerDeviceSchema.parse(request.body);

    
    let device = await prisma.device.findUnique({
      where: { androidId: body.androidId },
    });

    if (device) {
      
      device = await prisma.device.update({
        where: { id: device.id },
        data: {
          aaid: body.aaid,
          browserType: body.browserType,
          status: 'online',
          lastHeartbeat: new Date(),
        },
      });
    } else {
      
      device = await prisma.device.create({
        data: {
          androidId: body.androidId,
          aaid: body.aaid,
          browserType: body.browserType,
          agentToken: randomUUID(),
          status: 'online',
          lastHeartbeat: new Date(),
        },
      });
    }

    logger.info({ deviceId: device.id, androidId: device.androidId }, 'Device registered');

    return {
      deviceId: device.id,
      agentToken: device.agentToken,
    };
  });

  
  fastify.post('/heartbeat', async (request: FastifyRequest, reply: FastifyReply) => {
    const deviceId = (request.headers['x-device-id'] || request.headers['deviceid']) as string;
    const token = (request.headers['x-agent-token'] || request.headers['agenttoken']) as string;
    const body = heartbeatSchema.parse(request.body);

    if (!deviceId || !token) {
      return reply.status(401).send({
        error: {
          message: 'Missing device ID or token',
          code: 'MISSING_CREDENTIALS',
        },
      });
    }

    const device = await prisma.device.findUnique({
      where: { id: deviceId },
    });

    if (!device || device.agentToken !== token) {
      return reply.status(401).send({
        error: {
          message: 'Invalid device ID or token',
          code: 'INVALID_CREDENTIALS',
        },
      });
    }

    await prisma.device.update({
      where: { id: deviceId },
      data: {
        status: body.status || 'online',
        lastHeartbeat: new Date(),
        ipAddress: body.ipAddress,
      },
    });

   
    // Найти задачи для этого устройства со статусом pending или assigned
    logger.info({ deviceId, status: body.status }, 'Heartbeat received, searching for tasks');
    
    const tasks = await prisma.task.findMany({
      where: {
        deviceId: deviceId,
        status: { in: ['pending', 'assigned'] },
      },
      take: 1,
      orderBy: { createdAt: 'asc' },
    });
    
    logger.info({ deviceId, tasksFound: tasks.length, taskIds: tasks.map(t => t.id) }, 'Tasks found for device');

    // Если нашли задачу - обновить статус на assigned
    if (tasks.length > 0) {
      await prisma.task.update({
        where: { id: tasks[0].id },
        data: { 
          status: 'assigned',
          startedAt: tasks[0].startedAt || new Date(),
        },
      });
    }

    return {
      success: true,
      tasks: tasks.map((task) => ({
        id: task.id,
        type: task.type,
        config: task.configJson, // Changed to 'config' for Android client compatibility
      })),
    };
  });

  
  fastify.post('/task/result', async (request: FastifyRequest, reply: FastifyReply) => {
    const deviceId = (request.headers['x-device-id'] || request.headers['deviceid']) as string;
    const body = taskResultSchema.parse(request.body);

    if (!deviceId) {
      return reply.status(401).send({
        error: {
          message: 'Missing device ID',
          code: 'MISSING_DEVICE_ID',
        },
      });
    }

    const task = await prisma.task.findUnique({
      where: { id: body.taskId },
    });

    if (!task) {
      return reply.status(404).send({
        error: {
          message: 'Task not found',
          code: 'TASK_NOT_FOUND',
        },
      });
    }

    const updatedTask = await prisma.task.update({
      where: { id: body.taskId },
      data: {
        status: body.status,
        resultJson: body.resultJson,
        completedAt: body.status === 'completed' || body.status === 'failed' ? new Date() : undefined,
      },
    });

    logger.info({ taskId: body.taskId, deviceId, status: body.status }, 'Task result submitted');

    return { task: updatedTask };
  });

  
  fastify.post('/screenshot', async (request: FastifyRequest, reply: FastifyReply) => {
    const deviceId = (request.headers['x-device-id'] || request.headers['deviceid']) as string;
    const taskId = (request.headers['x-task-id'] || request.headers['taskid']) as string;

    if (!deviceId || !taskId) {
      return reply.status(400).send({
        error: {
          message: 'Missing device ID or task ID',
          code: 'MISSING_PARAMETERS',
        },
      });
    }

    try {
      const data = await request.file();
      
      if (!data) {
        return reply.status(400).send({
          error: {
            message: 'No file uploaded',
            code: 'NO_FILE',
          },
        });
      }

      const buffer = await data.toBuffer();
      const timestamp = new Date();
      const fileName = storageService.generateScreenshotPath(deviceId, taskId, timestamp);
      
      await storageService.uploadFile(buffer, fileName, 'image/png');

      
      await prisma.parsedData.updateMany({
        where: {
          taskId: taskId,
          screenshotPath: null,
        },
        data: {
          screenshotPath: fileName,
        },
      });

      logger.info({ deviceId, taskId, fileName }, 'Screenshot uploaded');

      return {
        success: true,
        path: fileName,
        url: `/api/artifacts/${fileName}`,
      };
    } catch (error) {
      logger.error({ error, deviceId, taskId }, 'Error uploading screenshot');
      return reply.status(500).send({
        error: {
          message: 'Error uploading screenshot',
          code: 'UPLOAD_ERROR',
        },
      });
    }
  });

  
  fastify.post('/parsed-data', async (request: FastifyRequest, reply: FastifyReply) => {
    const deviceId = (request.headers['x-device-id'] || request.headers['deviceid']) as string;
    const body = request.body as {
      taskId: string;
      url: string;
      adUrl?: string;
      adDomain?: string;
      screenshotPath?: string;
    };

    if (!deviceId || !body.taskId) {
      return reply.status(400).send({
        error: {
          message: 'Missing device ID or task ID',
          code: 'MISSING_PARAMETERS',
        },
      });
    }

    try {
      
      if (body.adDomain) {
        const existing = await prisma.parsedData.findFirst({
          where: {
            taskId: body.taskId,
            adDomain: body.adDomain,
          },
        });

        if (existing) {
          logger.info({ taskId: body.taskId, adDomain: body.adDomain }, 'Duplicate domain skipped');
          return {
            success: true,
            message: 'Duplicate domain, skipped',
            id: existing.id,
          };
        }
      }

      const parsedData = await prisma.parsedData.create({
        data: {
          taskId: body.taskId,
          url: body.url,
          adUrl: body.adUrl,
          adDomain: body.adDomain,
          screenshotPath: body.screenshotPath,
        },
      });

      if (body.adDomain) {
        try {
          await semrushService.checkDomain(body.adDomain);
        } catch (error) {
          logger.warn({ error, domain: body.adDomain }, 'Semrush check failed');
        }
      }

      logger.info({ parsedDataId: parsedData.id, taskId: body.taskId }, 'Parsed data saved');

      return {
        success: true,
        id: parsedData.id,
      };
    } catch (error) {
      logger.error({ error, deviceId, taskId: body.taskId }, 'Error saving parsed data');
      return reply.status(500).send({
        error: {
          message: 'Error saving parsed data',
          code: 'SAVE_ERROR',
        },
      });
    }
  });
}
