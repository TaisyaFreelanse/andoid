import { FastifyInstance } from 'fastify';
import { prisma } from '../server';
import { authenticate } from '../middleware/auth.middleware';

export async function parsedDataRoutes(fastify: FastifyInstance) {
  
  fastify.get(
    '/',
    { preHandler: [authenticate] },
    async (request, _reply) => {
      const { taskId, adDomain, limit = '100', offset = '0' } = request.query as {
        taskId?: string;
        adDomain?: string;
        limit?: string;
        offset?: string;
      };

      const where: any = {};
      if (taskId) where.taskId = taskId;
      if (adDomain) where.adDomain = adDomain;

      const [data, total] = await Promise.all([
        prisma.parsedData.findMany({
          where,
          include: {
            task: {
              select: {
                id: true,
                name: true,
                type: true,
              },
            },
          },
          orderBy: { parsedAt: 'desc' },
          take: parseInt(limit, 10),
          skip: parseInt(offset, 10),
        }),
        prisma.parsedData.count({ where }),
      ]);

      return {
        data,
        pagination: {
          total,
          limit: parseInt(limit, 10),
          offset: parseInt(offset, 10),
        },
      };
    }
  );

  
  fastify.get<{ Params: { id: string } }>(
    '/:id',
    { preHandler: [authenticate] },
    async (request, reply) => {
      const data = await prisma.parsedData.findUnique({
        where: { id: request.params.id },
        include: {
          task: true,
        },
      });

      if (!data) {
        return reply.status(404).send({
          error: {
            message: 'Parsed data not found',
            code: 'PARSED_DATA_NOT_FOUND',
          },
        });
      }

      return { data };
    }
  );

  
  fastify.get(
    '/export/csv',
    { preHandler: [authenticate] },
    async (request, reply) => {
      const { dateFrom, dateTo } = request.query as {
        dateFrom?: string;
        dateTo?: string;
      };

      const where: any = {};
      if (dateFrom || dateTo) {
        where.parsedAt = {};
        if (dateFrom) where.parsedAt.gte = new Date(dateFrom);
        if (dateTo) where.parsedAt.lte = new Date(dateTo);
      }

      const data = await prisma.parsedData.findMany({
        where,
        include: {
          task: {
            select: {
              id: true,
              name: true,
            },
          },
        },
        orderBy: { parsedAt: 'desc' },
      });

      
      const csvHeader = 'task_id,url,ad_url,ad_domain,screenshot_path,parsed_at\n';
      const csvRows = data.map((item) => {
        return [
          item.taskId,
          item.url,
          item.adUrl || '',
          item.adDomain || '',
          item.screenshotPath || '',
          item.parsedAt.toISOString(),
        ].map((field) => `"${String(field).replace(/"/g, '""')}"`).join(',');
      });

      const csv = csvHeader + csvRows.join('\n');

      reply.header('Content-Type', 'text/csv');
      reply.header('Content-Disposition', 'attachment; filename="parsed-data.csv"');
      return csv;
    }
  );

  
  fastify.get(
    '/export/json',
    { preHandler: [authenticate] },
    async (request, _reply) => {
      const { dateFrom, dateTo } = request.query as {
        dateFrom?: string;
        dateTo?: string;
      };

      const where: any = {};
      if (dateFrom || dateTo) {
        where.parsedAt = {};
        if (dateFrom) where.parsedAt.gte = new Date(dateFrom);
        if (dateTo) where.parsedAt.lte = new Date(dateTo);
      }

      const data = await prisma.parsedData.findMany({
        where,
        include: {
          task: {
            select: {
              id: true,
              name: true,
              type: true,
            },
          },
        },
        orderBy: { parsedAt: 'desc' },
      });

      return { data };
    }
  );
}

