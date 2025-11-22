import { Job } from 'bull';
import { taskQueue, TaskJobData } from './bull.config';
import { taskService } from '../services/task.service';
import { deviceService } from '../services/device.service';
import { proxyService } from '../services/proxy.service';
import { logger } from '../utils/logger';


async function processSurfingTask(taskId: string, _config: any, _deviceId: string, _proxyId?: string) {
  logger.info({ taskId, type: 'surfing' }, 'Processing surfing task');
  
  return { type: 'surfing', message: 'Task assigned to device' };
}

async function processParsingTask(taskId: string, _config: any, _deviceId: string, _proxyId?: string) {
  logger.info({ taskId, type: 'parsing' }, 'Processing parsing task');
 
  return { type: 'parsing', message: 'Task assigned to device' };
}

async function processUniquenessTask(taskId: string, _config: any, _deviceId: string) {
  logger.info({ taskId, type: 'uniqueness' }, 'Processing uniqueness task');
  
  return { type: 'uniqueness', message: 'Task assigned to device' };
}

async function processScreenshotTask(taskId: string, _config: any, _deviceId: string) {
  logger.info({ taskId, type: 'screenshot' }, 'Processing screenshot task');
  
  return { type: 'screenshot', message: 'Task assigned to device' };
}

export function setupTaskProcessor() {
 
  taskQueue.process(10, async (job: Job<TaskJobData>) => {
    const { taskId, type, config, deviceId, proxyId } = job.data;

    logger.info({ 
      taskId, 
      type, 
      deviceId, 
      proxyId,
      attempt: job.attemptsMade + 1,
      maxAttempts: job.opts.attempts 
    }, 'Processing task');

    try {
      
      await taskService.updateTaskStatus(taskId, 'running');

      
      let assignedDeviceId = deviceId;
      if (!assignedDeviceId) {
       
        const availableDevices = await deviceService.getAvailableDevices();
        if (availableDevices.length > 0) {
          const device = availableDevices[0];
          assignedDeviceId = device.id;
          await taskService.assignTaskToDevice(taskId, device.id);
        } else {
          throw new Error('No available devices');
        }
      } else {
        await taskService.assignTaskToDevice(taskId, assignedDeviceId);
      }

     
      let proxy = null;
      if (proxyId) {
        proxy = await proxyService.getProxyById(proxyId);
      } else if (config.proxy === 'auto' || config.proxy === true) {
        const country = (config as any).country;
        proxy = await proxyService.getRandomProxy(country);
      }

      
      if (proxy) {
        await proxyService.markProxyAsUsed(proxy.id);
        
        await taskService.updateTask(taskId, { proxyId: proxy.id });
      }

      
      let result;
      switch (type) {
        case 'surfing':
          result = await processSurfingTask(taskId, config, assignedDeviceId, proxy?.id);
          break;
        case 'parsing':
          result = await processParsingTask(taskId, config, assignedDeviceId, proxy?.id);
          break;
        case 'uniqueness':
          result = await processUniquenessTask(taskId, config, assignedDeviceId);
          break;
        case 'screenshot':
          result = await processScreenshotTask(taskId, config, assignedDeviceId);
          break;
        default:
          throw new Error(`Unknown task type: ${type}`);
      }

  
      logger.info({ taskId, type, deviceId: assignedDeviceId }, 'Task queued for agent execution');

      return {
        success: true,
        taskId,
        deviceId: assignedDeviceId,
        proxyId: proxy?.id,
        ...result,
      };
    } catch (error) {
      logger.error({ 
        error: error instanceof Error ? error.message : 'Unknown error',
        stack: error instanceof Error ? error.stack : undefined,
        taskId,
        type,
        attempt: job.attemptsMade + 1
      }, 'Error processing task');
      
      
      if (job.attemptsMade >= (job.opts.attempts || 3) - 1) {
        await taskService.updateTaskStatus(taskId, 'failed', {
          error: error instanceof Error ? error.message : 'Unknown error',
          attempts: job.attemptsMade + 1,
        });
      }

      throw error;
    }
  });
}

setupTaskProcessor();

