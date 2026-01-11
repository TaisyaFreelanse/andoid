import { FastifyInstance } from 'fastify';
import { prisma } from '../server';
import { authenticate } from '../middleware/auth.middleware';
import { requireRole } from '../middleware/rbac.middleware';
import { logger } from '../utils/logger';
import { storageService } from '../services/storage.service';

export async function parsedDataRoutes(fastify: FastifyInstance) {
  
  // Get all parsed data with filtering and pagination
  fastify.get(
    '/',
    { preHandler: [authenticate] },
    async (request, _reply) => {
      const { 
        taskId, 
        adDomain, 
        deviceId,
        taskType,
        dateFrom,
        dateTo,
        limit = '100', 
        offset = '0',
        includeScreenshots = 'false',
        includeSemrush = 'false'
      } = request.query as {
        taskId?: string;
        adDomain?: string;
        deviceId?: string;
        taskType?: string;
        dateFrom?: string;
        dateTo?: string;
        limit?: string;
        offset?: string;
        includeScreenshots?: string;
        includeSemrush?: string;
      };

      const where: any = {};
      
      // Basic filters
      if (taskId) where.taskId = taskId;
      if (adDomain) where.adDomain = { contains: adDomain, mode: 'insensitive' };
      
      // Date range filter
      if (dateFrom || dateTo) {
        where.parsedAt = {};
        if (dateFrom) where.parsedAt.gte = new Date(dateFrom);
        if (dateTo) where.parsedAt.lte = new Date(dateTo);
      }

      // Device filter (through task relation)
      const taskWhere: any = {};
      if (deviceId) taskWhere.deviceId = deviceId;
      if (taskType) taskWhere.type = taskType;
      
      if (Object.keys(taskWhere).length > 0) {
        where.task = taskWhere;
      }

      const [data, total] = await Promise.all([
        prisma.parsedData.findMany({
          where,
          include: {
            task: {
              select: {
                id: true,
                name: true,
                type: true,
                deviceId: true,
                resultJson: true,
                createdAt: true,
                completedAt: true,
              },
            },
          },
          orderBy: { parsedAt: 'desc' },
          take: parseInt(limit, 10),
          skip: parseInt(offset, 10),
        }),
        prisma.parsedData.count({ where }),
      ]);

      // Enrich data with presigned URLs and Semrush data
      const enrichedData = await Promise.all(data.map(async (item) => {
        const enriched: any = {
          ...item,
          extractedData: item.task?.resultJson || null,
        };

        // Add presigned URL for screenshot
        if (includeScreenshots === 'true' && item.screenshotPath) {
          try {
            enriched.screenshotUrl = await storageService.getPresignedUrl(item.screenshotPath, 3600);
          } catch (error) {
            enriched.screenshotUrl = null;
          }
        }

        // Add Semrush data if requested
        if (includeSemrush === 'true' && item.adDomain) {
          try {
            const semrushData = await prisma.semrushData.findUnique({
              where: { domain: item.adDomain },
            });
            enriched.semrushData = semrushData?.dataJson || null;
            enriched.semrushCheckedAt = semrushData?.checkedAt || null;
          } catch (error) {
            enriched.semrushData = null;
          }
        }

        return enriched;
      }));

      return {
        data: enrichedData,
        pagination: {
          total,
          limit: parseInt(limit, 10),
          offset: parseInt(offset, 10),
          hasMore: parseInt(offset, 10) + parseInt(limit, 10) < total,
        },
      };
    }
  );

  // Get single parsed data by ID
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

      // Add presigned URL for screenshot
      let screenshotUrl = null;
      if (data.screenshotPath) {
        try {
          screenshotUrl = await storageService.getPresignedUrl(data.screenshotPath, 3600);
        } catch (error) {
          logger.warn({ error, path: data.screenshotPath }, 'Failed to get presigned URL');
        }
      }

      // Get Semrush data
      let semrushData = null;
      if (data.adDomain) {
        const semrush = await prisma.semrushData.findUnique({
          where: { domain: data.adDomain },
        });
        semrushData = semrush?.dataJson || null;
      }

      return { 
        data: {
          ...data,
          screenshotUrl,
          semrushData,
        }
      };
    }
  );

  // Get statistics
  fastify.get(
    '/stats',
    { preHandler: [authenticate] },
    async (request, _reply) => {
      const { dateFrom, dateTo, deviceId } = request.query as {
        dateFrom?: string;
        dateTo?: string;
        deviceId?: string;
      };

      const where: any = {};
      if (dateFrom || dateTo) {
        where.parsedAt = {};
        if (dateFrom) where.parsedAt.gte = new Date(dateFrom);
        if (dateTo) where.parsedAt.lte = new Date(dateTo);
      }
      if (deviceId) {
        where.task = { deviceId };
      }

      const [
        totalCount,
        uniqueDomains,
        withScreenshots,
        recentData,
        topDomains
      ] = await Promise.all([
        // Total count
        prisma.parsedData.count({ where }),
        
        // Unique domains count
        prisma.parsedData.groupBy({
          by: ['adDomain'],
          where: { ...where, adDomain: { not: null } },
        }).then(r => r.length),
        
        // With screenshots count
        prisma.parsedData.count({
          where: { ...where, screenshotPath: { not: null } },
        }),
        
        // Recent 24h count
        prisma.parsedData.count({
          where: {
            ...where,
            parsedAt: { gte: new Date(Date.now() - 24 * 60 * 60 * 1000) },
          },
        }),
        
        // Top domains
        prisma.parsedData.groupBy({
          by: ['adDomain'],
          where: { ...where, adDomain: { not: null } },
          _count: { adDomain: true },
          orderBy: { _count: { adDomain: 'desc' } },
          take: 10,
        }),
      ]);

      // Get Semrush coverage
      const domainsWithSemrush = await prisma.semrushData.count();

      return {
        stats: {
          totalParsedData: totalCount,
          uniqueDomains,
          withScreenshots,
          recentLast24h: recentData,
          semrushCoverage: domainsWithSemrush,
        },
        topDomains: topDomains.map(d => ({
          domain: d.adDomain,
          count: d._count.adDomain,
        })),
      };
    }
  );

  // Export to CSV with Semrush data
  fastify.get(
    '/export/csv',
    { preHandler: [authenticate] },
    async (request, reply) => {
      const { dateFrom, dateTo, deviceId, taskType, includeSemrush = 'true' } = request.query as {
        dateFrom?: string;
        dateTo?: string;
        deviceId?: string;
        taskType?: string;
        includeSemrush?: string;
      };

      const where: any = {};
      if (dateFrom || dateTo) {
        where.parsedAt = {};
        if (dateFrom) where.parsedAt.gte = new Date(dateFrom);
        if (dateTo) where.parsedAt.lte = new Date(dateTo);
      }
      
      const taskWhere: any = {};
      if (deviceId) taskWhere.deviceId = deviceId;
      if (taskType) taskWhere.type = taskType;
      if (Object.keys(taskWhere).length > 0) {
        where.task = taskWhere;
      }

      const data = await prisma.parsedData.findMany({
        where,
        include: {
          task: {
            select: {
              id: true,
              name: true,
              type: true,
              deviceId: true,
              resultJson: true,
            },
          },
        },
        orderBy: { parsedAt: 'desc' },
      });

      // Get all Semrush data for domains if requested
      let semrushMap = new Map<string, any>();
      if (includeSemrush === 'true') {
        const domains = [...new Set(data.map(d => d.adDomain).filter(Boolean))];
        if (domains.length > 0) {
          const semrushRecords = await prisma.semrushData.findMany({
            where: { domain: { in: domains as string[] } },
          });
          semrushRecords.forEach(s => semrushMap.set(s.domain, s.dataJson));
        }
      }

      // CSV Header
      const csvHeader = [
        'task_id',
        'task_name',
        'task_type',
        'device_id',
        'url',
        'ad_url',
        'ad_domain',
        'screenshot_path',
        'parsed_at',
        ...(includeSemrush === 'true' ? [
          'semrush_domain_rank',
          'semrush_organic_keywords',
          'semrush_backlinks',
          'semrush_organic_traffic'
        ] : [])
      ].join(',') + '\n';

      // CSV Rows
      const csvRows = data.map((item) => {
        const semrush = item.adDomain ? semrushMap.get(item.adDomain) : null;
        
        const row = [
          item.taskId,
          item.task?.name || '',
          item.task?.type || '',
          item.task?.deviceId || '',
          item.url,
          item.adUrl || '',
          item.adDomain || '',
          item.screenshotPath || '',
          item.parsedAt.toISOString(),
        ];

        if (includeSemrush === 'true') {
          row.push(
            semrush?.domain_rank || semrush?.domainRank || '',
            semrush?.organic_keywords || semrush?.organicKeywords || '',
            semrush?.backlinks || '',
            semrush?.organic_traffic || semrush?.organicTraffic || ''
          );
        }

        return row.map((field) => `"${String(field).replace(/"/g, '""')}"`).join(',');
      });

      const csv = csvHeader + csvRows.join('\n');
      const filename = `parsed-data-${new Date().toISOString().split('T')[0]}.csv`;

      reply.header('Content-Type', 'text/csv; charset=utf-8');
      reply.header('Content-Disposition', `attachment; filename="${filename}"`);
      return csv;
    }
  );

  // Export to JSON with full data
  fastify.get(
    '/export/json',
    { preHandler: [authenticate] },
    async (request, _reply) => {
      const { 
        dateFrom, 
        dateTo, 
        deviceId, 
        taskType,
        includeSemrush = 'true',
        includeScreenshotUrls = 'false'
      } = request.query as {
        dateFrom?: string;
        dateTo?: string;
        deviceId?: string;
        taskType?: string;
        includeSemrush?: string;
        includeScreenshotUrls?: string;
      };

      const where: any = {};
      if (dateFrom || dateTo) {
        where.parsedAt = {};
        if (dateFrom) where.parsedAt.gte = new Date(dateFrom);
        if (dateTo) where.parsedAt.lte = new Date(dateTo);
      }
      
      const taskWhere: any = {};
      if (deviceId) taskWhere.deviceId = deviceId;
      if (taskType) taskWhere.type = taskType;
      if (Object.keys(taskWhere).length > 0) {
        where.task = taskWhere;
      }

      const data = await prisma.parsedData.findMany({
        where,
        include: {
          task: {
            select: {
              id: true,
              name: true,
              type: true,
              deviceId: true,
              resultJson: true,
              createdAt: true,
              completedAt: true,
            },
          },
        },
        orderBy: { parsedAt: 'desc' },
      });

      // Get all Semrush data
      let semrushMap = new Map<string, any>();
      if (includeSemrush === 'true') {
        const domains = [...new Set(data.map(d => d.adDomain).filter(Boolean))];
        if (domains.length > 0) {
          const semrushRecords = await prisma.semrushData.findMany({
            where: { domain: { in: domains as string[] } },
          });
          semrushRecords.forEach(s => semrushMap.set(s.domain, {
            data: s.dataJson,
            checkedAt: s.checkedAt,
            expiresAt: s.expiresAt,
          }));
        }
      }

      // Enrich data
      const enrichedData = await Promise.all(data.map(async (item) => {
        const enriched: any = {
          id: item.id,
          taskId: item.taskId,
          url: item.url,
          adUrl: item.adUrl,
          adDomain: item.adDomain,
          screenshotPath: item.screenshotPath,
          parsedAt: item.parsedAt,
          task: item.task,
          extractedData: item.task?.resultJson || null,
        };

        // Add Semrush data
        if (includeSemrush === 'true' && item.adDomain) {
          enriched.semrush = semrushMap.get(item.adDomain) || null;
        }

        // Add presigned URL
        if (includeScreenshotUrls === 'true' && item.screenshotPath) {
          try {
            enriched.screenshotUrl = await storageService.getPresignedUrl(item.screenshotPath, 3600);
          } catch (error) {
            enriched.screenshotUrl = null;
          }
        }

        return enriched;
      }));

      const filename = `parsed-data-${new Date().toISOString().split('T')[0]}.json`;
      _reply.header('Content-Type', 'application/json');
      _reply.header('Content-Disposition', `attachment; filename="${filename}"`);
      
      return {
        exportedAt: new Date().toISOString(),
        filters: { dateFrom, dateTo, deviceId, taskType },
        count: enrichedData.length,
        data: enrichedData,
      };
    }
  );

  // Get screenshots for a task
  fastify.get<{ Params: { taskId: string } }>(
    '/task/:taskId/screenshots',
    { preHandler: [authenticate] },
    async (request, _reply) => {
      const { taskId } = request.params;

      const parsedData = await prisma.parsedData.findMany({
        where: {
          taskId,
          screenshotPath: { not: null },
        },
        select: {
          id: true,
          screenshotPath: true,
          parsedAt: true,
          adDomain: true,
        },
      });

      if (parsedData.length === 0) {
        return { screenshots: [] };
      }

      // Get presigned URLs for all screenshots
      const screenshots = await Promise.all(parsedData.map(async (item) => {
        try {
          const url = await storageService.getPresignedUrl(item.screenshotPath!, 3600);
          return {
            id: item.id,
            path: item.screenshotPath,
            url,
            adDomain: item.adDomain,
            capturedAt: item.parsedAt,
          };
        } catch (error) {
          return {
            id: item.id,
            path: item.screenshotPath,
            url: null,
            adDomain: item.adDomain,
            capturedAt: item.parsedAt,
            error: 'Failed to generate URL',
          };
        }
      }));

      return { screenshots };
    }
  );

  // Bulk operations: Get domains list
  fastify.get(
    '/domains',
    { preHandler: [authenticate] },
    async (request, _reply) => {
      const { 
        dateFrom, 
        dateTo,
        withSemrush = 'false',
        limit = '100',
        offset = '0'
      } = request.query as {
        dateFrom?: string;
        dateTo?: string;
        withSemrush?: string;
        limit?: string;
        offset?: string;
      };

      const where: any = { adDomain: { not: null } };
      if (dateFrom || dateTo) {
        where.parsedAt = {};
        if (dateFrom) where.parsedAt.gte = new Date(dateFrom);
        if (dateTo) where.parsedAt.lte = new Date(dateTo);
      }

      // Get unique domains with counts
      const domainsData = await prisma.parsedData.groupBy({
        by: ['adDomain'],
        where,
        _count: { adDomain: true },
        _max: { parsedAt: true },
        orderBy: { _count: { adDomain: 'desc' } },
        take: parseInt(limit, 10),
        skip: parseInt(offset, 10),
      });

      const totalDomains = await prisma.parsedData.groupBy({
        by: ['adDomain'],
        where,
      }).then(r => r.length);

      // Add Semrush data if requested
      let result = domainsData.map(d => ({
        domain: d.adDomain,
        count: d._count.adDomain,
        lastSeen: d._max.parsedAt,
      }));

      if (withSemrush === 'true') {
        const domains = domainsData.map(d => d.adDomain).filter(Boolean) as string[];
        const semrushRecords = await prisma.semrushData.findMany({
          where: { domain: { in: domains } },
        });
        const semrushMap = new Map(semrushRecords.map(s => [s.domain, s.dataJson]));

        result = result.map(d => ({
          ...d,
          semrush: semrushMap.get(d.domain!) || null,
        }));
      }

      return {
        domains: result,
        pagination: {
          total: totalDomains,
          limit: parseInt(limit, 10),
          offset: parseInt(offset, 10),
          hasMore: parseInt(offset, 10) + parseInt(limit, 10) < totalDomains,
        },
      };
    }
  );

  // Delete single artifact
  fastify.delete<{ Params: { id: string } }>(
    '/:id',
    { preHandler: [authenticate, requireRole('admin', 'operator')] },
    async (request, reply) => {
      try {
        const parsedData = await prisma.parsedData.findUnique({
          where: { id: request.params.id },
        });

        if (!parsedData) {
          return reply.status(404).send({
            error: { message: 'Artifact not found', code: 'ARTIFACT_NOT_FOUND' },
          });
        }

        // Delete screenshot from storage if exists
        if (parsedData.screenshotPath) {
          try {
            await storageService.deleteFile(parsedData.screenshotPath);
          } catch (error) {
            logger.warn({ error, path: parsedData.screenshotPath }, 'Failed to delete screenshot from storage');
          }
        }

        await prisma.parsedData.delete({
          where: { id: request.params.id },
        });

        logger.info({ parsedDataId: request.params.id }, 'Parsed data deleted');

        return { success: true, message: 'Artifact deleted' };
      } catch (error) {
        logger.error({ error, parsedDataId: request.params.id }, 'Failed to delete parsed data');
        return reply.status(500).send({
          error: { message: 'Failed to delete artifact', code: 'DELETE_ERROR' },
        });
      }
    }
  );

  // Delete all artifacts for a task
  fastify.delete<{ Params: { taskId: string } }>(
    '/task/:taskId',
    { preHandler: [authenticate, requireRole('admin', 'operator')] },
    async (request, _reply) => {
      // Get all parsed data to delete screenshots
      const parsedDataList = await prisma.parsedData.findMany({
        where: { taskId: request.params.taskId },
        select: { screenshotPath: true },
      });

      // Delete screenshots from storage
      for (const item of parsedDataList) {
        if (item.screenshotPath) {
          try {
            await storageService.deleteFile(item.screenshotPath);
          } catch (error) {
            logger.warn({ error, path: item.screenshotPath }, 'Failed to delete screenshot from storage');
          }
        }
      }

      const result = await prisma.parsedData.deleteMany({
        where: { taskId: request.params.taskId },
      });

      logger.info({ taskId: request.params.taskId, count: result.count }, 'All parsed data for task deleted');

      return { success: true, message: `Deleted ${result.count} artifacts` };
    }
  );

  // Bulk delete by domain
  fastify.delete(
    '/domains/bulk',
    { preHandler: [authenticate, requireRole('admin')] },
    async (request, reply) => {
      const { domains } = request.body as { domains: string[] };

      if (!domains || !Array.isArray(domains) || domains.length === 0) {
        return reply.status(400).send({
          error: { message: 'Domains array required', code: 'MISSING_DOMAINS' },
        });
      }

      // Get all parsed data to delete screenshots
      const parsedDataList = await prisma.parsedData.findMany({
        where: { adDomain: { in: domains } },
        select: { screenshotPath: true },
      });

      // Delete screenshots from storage
      for (const item of parsedDataList) {
        if (item.screenshotPath) {
          try {
            await storageService.deleteFile(item.screenshotPath);
          } catch (error) {
            logger.warn({ error, path: item.screenshotPath }, 'Failed to delete screenshot');
          }
        }
      }

      const result = await prisma.parsedData.deleteMany({
        where: { adDomain: { in: domains } },
      });

      // Also delete Semrush data for these domains
      await prisma.semrushData.deleteMany({
        where: { domain: { in: domains } },
      });

      logger.info({ domains, count: result.count }, 'Bulk delete by domains completed');

      return { 
        success: true, 
        message: `Deleted ${result.count} artifacts for ${domains.length} domains` 
      };
    }
  );
}
