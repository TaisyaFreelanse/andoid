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
                resultJson: true, // Include extracted data
              },
            },
          },
          orderBy: { parsedAt: 'desc' },
          take: parseInt(limit, 10),
          skip: parseInt(offset, 10),
        }),
        prisma.parsedData.count({ where }),
      ]);

      // Merge task result data into parsed data
      const enrichedData = data.map((item) => ({
        ...item,
        extractedData: item.task?.resultJson || null,
      }));

      return {
        data: enrichedData,
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
              resultJson: true,
            },
          },
        },
        orderBy: { parsedAt: 'desc' },
      });

      // CSV with extracted data summary
      const csvHeader = 'task_id,task_name,url,ad_url,ad_domain,screenshot_path,parsed_at,titles_count,links_count,scores_count\n';
      const csvRows = data.map((item) => {
        const resultJson = item.task?.resultJson as any;
        const titlesCount = resultJson?.titles?.length || 0;
        const linksCount = resultJson?.links?.length || 0;
        const scoresCount = resultJson?.scores?.length || 0;
        
        return [
          item.taskId,
          item.task?.name || '',
          item.url,
          item.adUrl || '',
          item.adDomain || '',
          item.screenshotPath || '',
          item.parsedAt.toISOString(),
          titlesCount,
          linksCount,
          scoresCount,
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
              resultJson: true, // Include extracted data (titles, links, scores, etc.)
            },
          },
        },
        orderBy: { parsedAt: 'desc' },
      });

      // Merge task result data into parsed data for easier access
      const enrichedData = data.map((item) => ({
        ...item,
        extractedData: item.task?.resultJson || null,
      }));

      return { data: enrichedData };
    }
  );
}

