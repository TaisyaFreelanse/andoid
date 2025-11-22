import { createBullBoard } from '@bull-board/api';
import { BullAdapter } from '@bull-board/api/bullAdapter';
import { FastifyAdapter } from '@bull-board/fastify';
import { FastifyInstance } from 'fastify';
import { taskQueue } from './bull.config';
import { logger } from '../utils/logger';

export function setupBullBoard(fastify: FastifyInstance) {
  try {
    const serverAdapter = new FastifyAdapter();
    serverAdapter.setBasePath('/admin/queues');

    createBullBoard({
      queues: [new BullAdapter(taskQueue)],
      serverAdapter,
    });

    fastify.register(serverAdapter.registerPlugin());

    logger.info('Bull Board initialized at /admin/queues');
  } catch (error) {
    logger.error({ error }, 'Error setting up Bull Board');
  }
}

