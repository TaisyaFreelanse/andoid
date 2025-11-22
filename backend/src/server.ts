import Fastify from 'fastify';
import cors from '@fastify/cors';
import jwt from '@fastify/jwt';
import websocket from '@fastify/websocket';
import multipart from '@fastify/multipart';
import { PrismaClient } from '@prisma/client';
import { config } from './config';
import { logger } from './utils/logger';
import { authRoutes } from './routes/auth';
import { deviceRoutes } from './routes/devices';
import { taskRoutes } from './routes/tasks';
import { proxyRoutes } from './routes/proxies';
import { parsedDataRoutes } from './routes/parsed-data';
import { agentRoutes } from './routes/agent';
import { artifactsRoutes } from './routes/artifacts';
import { logsRoutes } from './routes/logs';
import { setupBullBoard } from './queue/bull-board';
import './queue/task.processor'; 


export const prisma = new PrismaClient({
  log: config.nodeEnv === 'development' ? ['query', 'error', 'warn'] : ['error'],
});


const fastify = Fastify({
  logger: false, 
  requestIdLogLabel: 'reqId',
  disableRequestLogging: false,
});


async function registerPlugins() {
  
  const corsOrigins = config.corsOrigin.split(',').map(o => o.trim());
  await fastify.register(cors, {
    origin: (origin, cb) => {
      
      if (!origin) return cb(null, true);
      
      if (corsOrigins.includes(origin) || corsOrigins.includes('*')) {
        return cb(null, true);
      }
      return cb(new Error('Not allowed by CORS'), false);
    },
    credentials: true,
  });

  
  await fastify.register(jwt, {
    secret: config.jwt.secret,
  });

  
  await fastify.register(websocket);

  
  await fastify.register(multipart, {
    limits: {
      fileSize: 10 * 1024 * 1024, 
    },
  });
}


async function registerRoutes() {
  
  fastify.get('/health', async () => {
    return { status: 'ok', timestamp: new Date().toISOString() };
  });

  
  if (config.nodeEnv === 'development') {
    setupBullBoard(fastify);
  }

  
  await fastify.register(authRoutes, { prefix: '/api/auth' });
  await fastify.register(deviceRoutes, { prefix: '/api/devices' });
  await fastify.register(taskRoutes, { prefix: '/api/tasks' });
  await fastify.register(proxyRoutes, { prefix: '/api/proxies' });
  await fastify.register(parsedDataRoutes, { prefix: '/api/parsed-data' });
  await fastify.register(agentRoutes, { prefix: '/api/agent' });
  await fastify.register(artifactsRoutes, { prefix: '/api/artifacts' });
  await fastify.register(logsRoutes, { prefix: '/api/logs' });
}


fastify.setErrorHandler((error, request, reply) => {
  logger.error({ error, requestId: request.id }, 'Request error');
  
  reply.status(error.statusCode || 500).send({
    error: {
      message: error.message || 'Internal Server Error',
      code: error.code || 'INTERNAL_ERROR',
    },
  });
});


async function gracefulShutdown() {
  logger.info('Shutting down gracefully...');
  
  try {
    await fastify.close();
    await prisma.$disconnect();
    logger.info('Shutdown complete');
    process.exit(0);
  } catch (error) {
    logger.error({ error }, 'Error during shutdown');
    process.exit(1);
  }
}

process.on('SIGTERM', gracefulShutdown);
process.on('SIGINT', gracefulShutdown);


async function start() {
  try {
    await registerPlugins();
    await registerRoutes();
    
    const address = await fastify.listen({
      port: config.port,
      host: config.host,
    });
    
    logger.info(`Server listening on ${address}`);
  } catch (error) {
    logger.error({ 
      error: error instanceof Error ? error.message : String(error),
      stack: error instanceof Error ? error.stack : undefined 
    }, 'Error starting server');
    await prisma.$disconnect();
    process.exit(1);
  }
}

start();

