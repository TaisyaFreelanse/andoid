import { FastifyInstance } from 'fastify';
import bcrypt from 'bcrypt';
import { prisma } from '../server';
import { authenticate } from '../middleware/auth.middleware';
import { requireRole } from '../middleware/rbac.middleware';
import { createProxySchema, updateProxySchema } from '../utils/validator';
import { logger } from '../utils/logger';

export async function proxyRoutes(fastify: FastifyInstance) {
  
  fastify.get(
    '/',
    { preHandler: [authenticate] },
    async (_request, _reply) => {
      const proxies = await prisma.proxy.findMany({
        orderBy: { createdAt: 'desc' },
      });

      
      const safeProxies = proxies.map((proxy) => ({
        ...proxy,
        passwordEncrypted: undefined,
      }));

      return { proxies: safeProxies };
    }
  );

  
  fastify.get<{ Params: { id: string } }>(
    '/:id',
    { preHandler: [authenticate] },
    async (request, reply) => {
      const proxy = await prisma.proxy.findUnique({
        where: { id: request.params.id },
      });

      if (!proxy) {
        return reply.status(404).send({
          error: {
            message: 'Proxy not found',
            code: 'PROXY_NOT_FOUND',
          },
        });
      }

      return {
        proxy: {
          ...proxy,
          passwordEncrypted: undefined,
        },
      };
    }
  );

  
  fastify.post(
    '/',
    { preHandler: [authenticate, requireRole('admin', 'operator')] },
    async (request, _reply) => {
      const body = createProxySchema.parse(request.body);

      let passwordEncrypted: string | null = null;
      if (body.password) {
        passwordEncrypted = await bcrypt.hash(body.password, 10);
      }

      const proxy = await prisma.proxy.create({
        data: {
          host: body.host,
          port: body.port,
          username: body.username,
          passwordEncrypted,
          type: body.type,
          country: body.country,
          timezone: body.timezone,
        },
      });

      logger.info({ proxyId: proxy.id }, 'Proxy created');

      return {
        proxy: {
          ...proxy,
          passwordEncrypted: undefined,
        },
      };
    }
  );

  
  fastify.patch<{ Params: { id: string } }>(
    '/:id',
    { preHandler: [authenticate, requireRole('admin', 'operator')] },
    async (request, _reply) => {
      const body = updateProxySchema.parse(request.body);

      const updateData: any = { ...body };
      
      if (body.password) {
        updateData.passwordEncrypted = await bcrypt.hash(body.password, 10);
        delete updateData.password;
      }

      const proxy = await prisma.proxy.update({
        where: { id: request.params.id },
        data: updateData,
      });

      logger.info({ proxyId: proxy.id }, 'Proxy updated');

      return {
        proxy: {
          ...proxy,
          passwordEncrypted: undefined,
        },
      };
    }
  );

  
  fastify.delete<{ Params: { id: string } }>(
    '/:id',
    { preHandler: [authenticate, requireRole('admin')] },
    async (request, _reply) => {
      await prisma.proxy.delete({
        where: { id: request.params.id },
      });

      logger.info({ proxyId: request.params.id }, 'Proxy deleted');

      return { message: 'Proxy deleted' };
    }
  );
}

