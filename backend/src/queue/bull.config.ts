import Queue from 'bull';
import { config } from '../config';
import { logger } from '../utils/logger';


export enum TaskPriority {
  LOW = 1,
  NORMAL = 5,
  HIGH = 10,
  URGENT = 20,
}


const getRetryConfig = (taskType: string) => {
  switch (taskType) {
    case 'uniqueness':
     
      return {
        attempts: 5,
        backoff: {
          type: 'exponential' as const,
          delay: 5000, 
        },
      };
    case 'parsing':
     
      return {
        attempts: 4,
        backoff: {
          type: 'exponential' as const,
          delay: 3000,
        },
      };
    case 'surfing':
      
      return {
        attempts: 3,
        backoff: {
          type: 'exponential' as const,
          delay: 2000,
        },
      };
    case 'screenshot':
      
      return {
        attempts: 2,
        backoff: {
          type: 'exponential' as const,
          delay: 1000,
        },
      };
    default:
      return {
        attempts: 3,
        backoff: {
          type: 'exponential' as const,
          delay: 2000,
        },
      };
  }
};

export const createQueue = (name: string) => {
  const queue = new Queue(name, {
    redis: {
      host: config.redis.host,
      port: config.redis.port,
      password: config.redis.password,
      maxRetriesPerRequest: null, 
    },
    defaultJobOptions: {
      attempts: 3,
      backoff: {
        type: 'exponential',
        delay: 2000,
      },
      removeOnComplete: {
        age: 3600, 
        count: 1000, 
      },
      removeOnFail: {
        age: 86400, 
      },
    },
    settings: {
      stalledInterval: 30000, 
      maxStalledCount: 1, 
    },
  });

  queue.on('error', (error) => {
    logger.error({ error, queue: name }, 'Queue error');
  });

  queue.on('waiting', (jobId) => {
    logger.debug({ jobId, queue: name }, 'Job waiting');
  });

  queue.on('active', (job) => {
    logger.info({ jobId: job.id, queue: name, data: job.data }, 'Job started');
  });

  queue.on('completed', (job) => {
    logger.info({ jobId: job.id, queue: name }, 'Job completed');
  });

  queue.on('failed', (job, error) => {
    logger.error(
      { 
        jobId: job?.id, 
        error: error.message, 
        stack: error.stack,
        attemptsMade: job?.attemptsMade,
        queue: name 
      }, 
      'Job failed'
    );
  });

  queue.on('stalled', (jobId) => {
    logger.warn({ jobId, queue: name }, 'Job stalled');
  });

  return queue;
};


export const taskQueue = createQueue('tasks');


export interface TaskJobData {
  taskId: string;
  type: 'surfing' | 'parsing' | 'uniqueness' | 'screenshot';
  config: any;
  deviceId?: string;
  proxyId?: string;
  priority?: TaskPriority;
}


export async function addTaskToQueue(data: TaskJobData) {
  const retryConfig = getRetryConfig(data.type);
  const priority = data.priority || TaskPriority.NORMAL;

  const job = await taskQueue.add(data, {
    ...retryConfig,
    priority,
    jobId: data.taskId, 
  });

  logger.info(
    { 
      taskId: data.taskId, 
      type: data.type, 
      priority,
      jobId: job.id 
    }, 
    'Task added to queue'
  );

  return job;
}

