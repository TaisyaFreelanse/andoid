import { FastifyInstance } from 'fastify';
import { authenticate } from '../middleware/auth.middleware';
import { requireRole } from '../middleware/rbac.middleware';
import { storageService } from '../services/storage.service';
import { prisma } from '../server';
import { logger } from '../utils/logger';

export async function artifactsRoutes(fastify: FastifyInstance) {
  
  // List all artifacts (screenshots) with pagination
  fastify.get(
    '/',
    { preHandler: [authenticate] },
    async (request, _reply) => {
      const { 
        deviceId, 
        taskId, 
        type,
        limit = '50', 
        offset = '0',
        dateFrom,
        dateTo 
      } = request.query as {
        deviceId?: string;
        taskId?: string;
        type?: string;
        limit?: string;
        offset?: string;
        dateFrom?: string;
        dateTo?: string;
      };

      const where: any = {};

      if (taskId) where.taskId = taskId;
      if (deviceId) where.deviceId = deviceId;
      if (type) where.type = type;
      if (dateFrom || dateTo) {
        where.capturedAt = {};
        if (dateFrom) where.capturedAt.gte = new Date(dateFrom);
        if (dateTo) where.capturedAt.lte = new Date(dateTo);
      }

      const [artifacts, total] = await Promise.all([
        prisma.artifact.findMany({
          where,
          orderBy: { capturedAt: 'desc' },
          take: parseInt(limit, 10),
          skip: parseInt(offset, 10),
        }),
        prisma.artifact.count({ where }),
      ]);

      // Generate presigned URLs
      const artifactsWithUrls = await Promise.all(artifacts.map(async (artifact) => {
        let url = null;
        try {
          url = await storageService.getPresignedUrl(artifact.path, 3600);
        } catch (error) {
          logger.warn({ error, path: artifact.path }, 'Failed to get presigned URL');
        }

        return {
          id: artifact.id,
          taskId: artifact.taskId,
          deviceId: artifact.deviceId,
          path: artifact.path,
          type: artifact.type,
          size: artifact.size,
          mimeType: artifact.mimeType,
          url,
          capturedAt: artifact.capturedAt,
          createdAt: artifact.createdAt,
        };
      }));

      return {
        artifacts: artifactsWithUrls,
        pagination: {
          total,
          limit: parseInt(limit, 10),
          offset: parseInt(offset, 10),
          hasMore: parseInt(offset, 10) + parseInt(limit, 10) < total,
        },
      };
    }
  );

  // Get artifact statistics
  fastify.get(
    '/stats',
    { preHandler: [authenticate] },
    async (request, _reply) => {
      const { deviceId, type, dateFrom, dateTo } = request.query as {
        deviceId?: string;
        type?: string;
        dateFrom?: string;
        dateTo?: string;
      };

      const where: any = {};
      if (deviceId) where.deviceId = deviceId;
      if (type) where.type = type;
      if (dateFrom || dateTo) {
        where.capturedAt = {};
        if (dateFrom) where.capturedAt.gte = new Date(dateFrom);
        if (dateTo) where.capturedAt.lte = new Date(dateTo);
      }

      const [totalArtifacts, byDevice, recent24h, totalSize] = await Promise.all([
        prisma.artifact.count({ where }),
        prisma.artifact.groupBy({
          by: ['deviceId'],
          where,
          _count: { id: true },
        }).then(data => {
          const counts: Record<string, number> = {};
          data.forEach(d => {
            counts[d.deviceId] = d._count.id;
          });
          return counts;
        }),
        prisma.artifact.count({
          where: {
            ...where,
            capturedAt: { gte: new Date(Date.now() - 24 * 60 * 60 * 1000) },
          },
        }),
        prisma.artifact.aggregate({
          where,
          _sum: { size: true },
        }).then(r => r._sum.size || 0),
      ]);

      return {
        stats: {
          total: totalArtifacts,
          recent24h,
          byDevice,
          totalSizeBytes: totalSize,
          totalSizeMB: Math.round((totalSize as number) / 1024 / 1024 * 100) / 100,
        },
      };
    }
  );

  // Upload artifact (for admin upload)
  fastify.post(
    '/upload',
    { preHandler: [authenticate, requireRole('admin', 'operator')] },
    async (request, reply) => {
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

        const { taskId, deviceId } = request.query as {
          taskId?: string;
          deviceId?: string;
        };

        const buffer = await data.toBuffer();
        const timestamp = new Date();
        const fileName = storageService.generateScreenshotPath(
          deviceId || 'manual',
          taskId || 'manual',
          timestamp
        );
        
        await storageService.uploadFile(buffer, fileName, data.mimetype || 'image/png');

        logger.info({ fileName, taskId, deviceId }, 'Artifact uploaded via admin');

        // If taskId provided, create parsed data entry
        if (taskId) {
          await prisma.parsedData.create({
            data: {
              taskId,
              url: 'manual-upload',
              screenshotPath: fileName,
            },
          });
        }

        const presignedUrl = await storageService.getPresignedUrl(fileName, 3600);

        return {
          success: true,
          path: fileName,
          url: presignedUrl,
        };
      } catch (error) {
        logger.error({ error }, 'Error uploading artifact');
        return reply.status(500).send({
          error: {
            message: 'Error uploading artifact',
            code: 'UPLOAD_ERROR',
          },
        });
      }
    }
  );

  // Delete artifact by path
  fastify.delete(
    '/by-path',
    { preHandler: [authenticate, requireRole('admin')] },
    async (request, reply) => {
      const { path } = request.query as { path: string };

      if (!path) {
        return reply.status(400).send({
          error: { message: 'Path required', code: 'MISSING_PATH' },
        });
      }

      try {
        // Delete from storage
        await storageService.deleteFile(path);

        // Update parsed data entries
        await prisma.parsedData.updateMany({
          where: { screenshotPath: path },
          data: { screenshotPath: null },
        });

        logger.info({ path }, 'Artifact deleted by path');

        return { success: true, message: 'Artifact deleted' };
      } catch (error) {
        logger.error({ error, path }, 'Error deleting artifact');
        return reply.status(500).send({
          error: { message: 'Failed to delete artifact', code: 'DELETE_ERROR' },
        });
      }
    }
  );

  // Get single artifact by path (redirect to presigned URL)
  fastify.get<{ Params: { '*': string } }>(
    '/*',
    { preHandler: [authenticate] },
    async (request, reply) => {
      try {
        const objectName = request.params['*'] || '';
        
        if (!objectName) {
          return reply.status(400).send({
            error: { message: 'Path required', code: 'MISSING_PATH' },
          });
        }

        const presignedUrl = await storageService.getPresignedUrl(objectName, 3600);
        
        return reply.redirect(presignedUrl);
      } catch (error) {
        logger.error({ error, path: request.params['*'] }, 'Error getting artifact');
        return reply.status(404).send({
          error: {
            message: 'Artifact not found',
            code: 'ARTIFACT_NOT_FOUND',
          },
        });
      }
    }
  );
}
