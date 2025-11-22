import { FastifyInstance } from 'fastify';
import { authenticate } from '../middleware/auth.middleware';
import { storageService } from '../services/storage.service';
import { logger } from '../utils/logger';

export async function artifactsRoutes(fastify: FastifyInstance) {
 
  fastify.get<{ Params: { '*': string } }>(
    '/*',
    { preHandler: [authenticate] },
    async (request, reply) => {
      try {
        const objectName = request.params['*'] || '';
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

