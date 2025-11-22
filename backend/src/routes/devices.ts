import { FastifyInstance } from 'fastify';
import { randomUUID } from 'crypto';
import { prisma } from '../server';
import { authenticate } from '../middleware/auth.middleware';
import { requireRole } from '../middleware/rbac.middleware';
import { createDeviceSchema, updateDeviceSchema } from '../utils/validator';
import { logger } from '../utils/logger';

export async function deviceRoutes(fastify: FastifyInstance) {
  
  fastify.get(
    '/',
    { preHandler: [authenticate] },
    async (_request, _reply) => {
      const devices = await prisma.device.findMany({
        orderBy: { createdAt: 'desc' },
      });

      return { devices };
    }
  );

  
  fastify.get<{ Params: { id: string } }>(
    '/:id',
    { preHandler: [authenticate] },
    async (request, reply) => {
      const device = await prisma.device.findUnique({
        where: { id: request.params.id },
        include: {
          deviceFingerprints: {
            orderBy: { createdAt: 'desc' },
            take: 10,
          },
        },
      });

      if (!device) {
        return reply.status(404).send({
          error: {
            message: 'Device not found',
            code: 'DEVICE_NOT_FOUND',
          },
        });
      }

      return { device };
    }
  );

  
  fastify.post(
    '/',
    { preHandler: [authenticate, requireRole('admin', 'operator')] },
    async (request, _reply) => {
      const body = createDeviceSchema.parse(request.body);

      const device = await prisma.device.create({
        data: {
          name: body.name,
          androidId: body.androidId,
          aaid: body.aaid,
          browserType: body.browserType,
          agentToken: randomUUID(),
        },
      });

      logger.info({ deviceId: device.id }, 'Device created');

      return { device };
    }
  );

  
  fastify.patch<{ Params: { id: string } }>(
    '/:id',
    { preHandler: [authenticate, requireRole('admin', 'operator')] },
    async (request, _reply) => {
      const body = updateDeviceSchema.parse(request.body);

      const device = await prisma.device.update({
        where: { id: request.params.id },
        data: body,
      });

      logger.info({ deviceId: device.id }, 'Device updated');

      return { device };
    }
  );

  
  fastify.delete<{ Params: { id: string } }>(
    '/:id',
    { preHandler: [authenticate, requireRole('admin')] },
    async (request, _reply) => {
      await prisma.device.delete({
        where: { id: request.params.id },
      });

      logger.info({ deviceId: request.params.id }, 'Device deleted');

      return { message: 'Device deleted' };
    }
  );
}

