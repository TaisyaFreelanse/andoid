import { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import { randomUUID } from 'crypto';
import { prisma } from '../server';
import { registerDeviceSchema, heartbeatSchema, taskResultSchema } from '../utils/validator';
import { logger } from '../utils/logger';
import { storageService } from '../services/storage.service';
import { broadcastLog } from './logs';
import { domainCheckerService } from '../services/domain-checker.service';

export async function agentRoutes(fastify: FastifyInstance) {
  
  fastify.post('/register', async (request: FastifyRequest, _reply: FastifyReply) => {
    // Log RAW request body BEFORE parsing to see what actually comes from client
    logger.info({
      rawBody: request.body,
      rawBodyKeys: request.body ? Object.keys(request.body as object) : [],
    }, 'Device registration - RAW request body (before parsing)');

    const body = registerDeviceSchema.parse(request.body);
    const existingDeviceId = body.existingDeviceId;
    const isRooted = body.isRooted;
    const rootCheckDetails = body.rootCheckDetails;
    const rootCheckMethods = body.rootCheckMethods;

    // Log root status with detailed information for debugging
    logger.info({
      androidId: body.androidId,
      isRooted: isRooted,
      existingDeviceId: existingDeviceId,
      rootCheckDetails: rootCheckDetails,
      rootCheckMethods: rootCheckMethods,
      parsedBody: body, // Log parsed body after validation
      rawBody: request.body, // Log raw body for comparison
    }, 'Device registration request - Root check with details');

    let device = null;
    
    // First, try to find by existingDeviceId (for re-registration after reinstall)
    if (existingDeviceId) {
      device = await prisma.device.findUnique({
        where: { id: existingDeviceId },
      });
    }
    
    // If not found by existingDeviceId, try by androidId
    if (!device) {
      device = await prisma.device.findUnique({
        where: { androidId: body.androidId },
      });
    }

    if (device) {
      // Update existing device
      device = await prisma.device.update({
        where: { id: device.id },
        data: {
          androidId: body.androidId, // Update androidId in case it changed
          aaid: body.aaid,
          browserType: body.browserType,
          status: 'online',
          lastHeartbeat: new Date(),
        },
      });
      logger.info({ 
        deviceId: device.id, 
        androidId: device.androidId,
        isRooted: isRooted,
      }, 'Device re-registered');
      const rootStatus = isRooted ? '✓ Root доступен' : '✗ Root недоступен';
      broadcastLog(`📱 Устройство переподключено: ${device.name || device.androidId} (${rootStatus})`, 'info');
    } else {
      // Create new device
      device = await prisma.device.create({
        data: {
          androidId: body.androidId,
          aaid: body.aaid,
          browserType: body.browserType,
          agentToken: randomUUID(),
          status: 'online',
          lastHeartbeat: new Date(),
        },
      });
      logger.info({ 
        deviceId: device.id, 
        androidId: device.androidId,
        isRooted: isRooted,
      }, 'New device registered');
      const rootStatus = isRooted ? '✓ Root доступен' : '✗ Root недоступен';
      broadcastLog(`📱 Новое устройство: ${device.name || device.androidId} (${rootStatus})`, 'info');
    }

    return {
      deviceId: device.id,
      agentToken: device.agentToken,
    };
  });

  
  fastify.post('/heartbeat', async (request: FastifyRequest, reply: FastifyReply) => {
    const deviceId = (request.headers['x-device-id'] || request.headers['deviceid']) as string;
    const token = (request.headers['x-agent-token'] || request.headers['agenttoken']) as string;
    const body = heartbeatSchema.parse(request.body);

    if (!deviceId || !token) {
      return reply.status(401).send({
        error: {
          message: 'Missing device ID or token',
          code: 'MISSING_CREDENTIALS',
        },
      });
    }

    const device = await prisma.device.findUnique({
      where: { id: deviceId },
    });

    if (!device || device.agentToken !== token) {
      return reply.status(401).send({
        error: {
          message: 'Invalid device ID or token',
          code: 'INVALID_CREDENTIALS',
        },
      });
    }

    await prisma.device.update({
      where: { id: deviceId },
      data: {
        status: body.status || 'online',
        lastHeartbeat: new Date(),
        ipAddress: body.ipAddress,
      },
    });

   
    // Найти задачи для этого устройства со статусом pending или assigned
    logger.info({ deviceId, status: body.status }, 'Heartbeat received, searching for tasks');
    
    const tasks = await prisma.task.findMany({
      where: {
        deviceId: deviceId,
        status: { in: ['pending', 'assigned'] },
      },
      take: 1,
      orderBy: { createdAt: 'asc' },
    });
    
    logger.info({ deviceId, tasksFound: tasks.length, taskIds: tasks.map(t => t.id) }, 'Tasks found for device');
    
    if (tasks.length > 0) {
      broadcastLog(`📋 Задач для устройства: ${tasks.length} (${tasks.map(t => t.name).join(', ')})`, 'info');
    }

    // Если нашли задачу - обновить статус на assigned
    if (tasks.length > 0) {
      await prisma.task.update({
        where: { id: tasks[0].id },
        data: { 
          status: 'assigned',
          startedAt: tasks[0].startedAt || new Date(),
        },
      });
    }

    const response = {
      success: true,
      tasks: tasks.map((task) => {
        const configJson = task.configJson as any;
        // Extract steps from configJson and format for Android Agent
        const steps = configJson?.steps?.map((step: any) => {
          const { type, ...stepConfig } = step; // Separate type from other fields
          return {
            type: type,
            config: stepConfig, // All other fields go into config
          };
        }) || [];
        
        logger.info({ taskId: task.id, stepsCount: steps.length, stepTypes: steps.map((s: any) => s.type) }, 'Preparing task for Android Agent');
        
        return {
          id: task.id,
          name: task.name,
          type: task.type,
          status: task.status,
          priority: 0,
          config: configJson,
          steps: steps,
        };
      }),
    };
    
    logger.info({ responsePreview: JSON.stringify(response).substring(0, 500), responseSize: JSON.stringify(response).length }, 'Heartbeat response');
    
    return response;
  });

  
  // Update task status endpoint (for Android Agent)
  fastify.put('/tasks/:taskId/status', async (request: FastifyRequest<{ Params: { taskId: string } }>, reply: FastifyReply) => {
    const deviceId = (request.headers['x-device-id'] || request.headers['deviceid']) as string;
    const { taskId } = request.params;
    const body = request.body as { status: string };

    if (!deviceId) {
      return reply.status(401).send({
        error: { message: 'Missing device ID', code: 'MISSING_DEVICE_ID' },
      });
    }

    // Map string status to TaskStatus enum
    const statusMap: Record<string, 'pending' | 'assigned' | 'running' | 'completed' | 'failed' | 'cancelled'> = {
      pending: 'pending',
      assigned: 'assigned',
      running: 'running',
      completed: 'completed',
      failed: 'failed',
      cancelled: 'cancelled',
    };
    const taskStatus = statusMap[body.status.toLowerCase()];
    
    if (!taskStatus) {
      return reply.status(400).send({
        error: { message: 'Invalid status', code: 'INVALID_STATUS' },
      });
    }

    try {
      const task = await prisma.task.update({
        where: { id: taskId },
        data: {
          status: taskStatus,
          startedAt: taskStatus === 'running' ? new Date() : undefined,
          completedAt: taskStatus === 'completed' || taskStatus === 'failed' ? new Date() : undefined,
        },
      });

      logger.info({ taskId, deviceId, status: taskStatus }, 'Task status updated');
      
      const statusEmoji = taskStatus === 'running' ? '🔄' : taskStatus === 'completed' ? '✅' : taskStatus === 'failed' ? '❌' : '📝';
      broadcastLog(`${statusEmoji} Задача ${task.name}: ${taskStatus}`, taskStatus === 'failed' ? 'error' : 'info');
      
      return { success: true, task };
    } catch (error) {
      logger.error({ taskId, error }, 'Failed to update task status');
      return reply.status(404).send({
        error: { message: 'Task not found', code: 'TASK_NOT_FOUND' },
      });
    }
  });

  // Send task result endpoint (for Android Agent)
  fastify.post('/tasks/:taskId/result', async (request: FastifyRequest<{ Params: { taskId: string } }>, reply: FastifyReply) => {
    const deviceId = (request.headers['x-device-id'] || request.headers['deviceid']) as string;
    const { taskId } = request.params;
    const body = request.body as { status?: string; success?: boolean; result?: any; data?: any; error?: string; executionTime?: number; screenshots?: string[] };

    // Log incoming data for debugging
    const dataKeys = body.data ? Object.keys(body.data) : [];
    const adUrls = body.data?.ad_urls;
    const adLinks = body.data?.ad_links;
    const adDomains = body.data?.ad_domains;
    
    logger.info({ 
      taskId, 
      bodyKeys: Object.keys(body),
      hasData: !!body.data,
      hasResult: !!body.result,
      dataKeys: dataKeys,
      adUrlsCount: Array.isArray(adUrls) ? adUrls.length : (adUrls ? 1 : 0),
      adLinksCount: Array.isArray(adLinks) ? adLinks.length : (adLinks ? 1 : 0),
      adDomainsCount: Array.isArray(adDomains) ? adDomains.length : (adDomains ? 1 : 0),
      adUrlsSample: Array.isArray(adUrls) ? adUrls.slice(0, 3) : (adUrls ? [adUrls] : []),
      success: body.success,
      error: body.error,
      errorMessage: body.error || (body.data?.error ? body.data.error : null)
    }, 'Task result received from Android Agent');

    if (!deviceId) {
      logger.error({ taskId, headers: Object.keys(request.headers) }, 'Missing X-Device-Id header in task result request');
      return reply.status(401).send({
        error: { message: 'Missing device ID', code: 'MISSING_DEVICE_ID' },
      });
    }

    // Determine status from body - handle various formats from Android Agent
    let finalStatus: 'completed' | 'failed' = 'completed';
    
    if (body.status) {
      const statusLower = body.status.toLowerCase();
      if (statusLower === 'failed' || statusLower === 'error') {
        finalStatus = 'failed';
      }
    } else if (body.success === false || body.error) {
      finalStatus = 'failed';
    }
    
    logger.info({
      taskId,
      bodySuccess: body.success,
      bodyError: body.error,
      bodyStatus: body.status,
      determinedStatus: finalStatus
    }, 'Determining task final status');

    // Get the result data
    const resultData = body.result || body.data || {};

    // Include error in result data if present
    if (body.error) {
      resultData.error = body.error;
    }

    try {
      logger.info({ taskId, finalStatus, resultDataKeys: Object.keys(resultData) }, 'Updating task status in database');
      
      // First check if task exists
      const existingTask = await prisma.task.findUnique({
        where: { id: taskId },
      });
      
      if (!existingTask) {
        logger.error({ taskId, deviceId }, 'Task not found in database when trying to update result');
        return reply.status(404).send({
          error: { message: 'Task not found', code: 'TASK_NOT_FOUND' },
        });
      }
      
      const task = await prisma.task.update({
        where: { id: taskId },
        data: {
          status: finalStatus,
          resultJson: resultData,
          completedAt: new Date(),
        },
      });
      
      // Log what was saved in resultJson
      const savedResultJson = task.resultJson as any;
      const savedAdUrls = savedResultJson?.ad_urls;
      const savedAdLinks = savedResultJson?.ad_links;
      const savedAdDomains = savedResultJson?.ad_domains;
      
      logger.info({ 
        taskId, 
        updatedStatus: task.status, 
        taskName: task.name,
        hasCompletedAt: !!task.completedAt,
        savedAdUrlsCount: Array.isArray(savedAdUrls) ? savedAdUrls.length : (savedAdUrls ? 1 : 0),
        savedAdLinksCount: Array.isArray(savedAdLinks) ? savedAdLinks.length : (savedAdLinks ? 1 : 0),
        savedAdDomainsCount: Array.isArray(savedAdDomains) ? savedAdDomains.length : (savedAdDomains ? 1 : 0),
        savedAdUrlsSample: Array.isArray(savedAdUrls) ? savedAdUrls.slice(0, 3) : (savedAdUrls ? [savedAdUrls] : []),
        savedResultJsonKeys: savedResultJson ? Object.keys(savedResultJson) : []
      }, 'Task status updated successfully');

      // Auto-save to parsed data ONLY for parsing tasks
      const taskType = task.type?.toLowerCase() || '';
      
      if (finalStatus === 'completed' && task && taskType === 'parsing') {
        try {
          const configJson = task.configJson as any;
          const url = configJson?.url || configJson?.steps?.[0]?.url || 'unknown';
          
          // Extract data from various possible keys
          const titles = resultData.titles || resultData.extracted_data || [];
          const links = resultData.links || [];
          const adUrls = resultData.ad_urls || [];
          const adDomains = resultData.ad_domains || [];
          const screenshots = resultData.screenshots || body.screenshots || [];
          
          // Process ad_domains array: check via API and save only valid domains
          let savedDomainsCount = 0;
          let skippedDomainsCount = 0;
          
          if (Array.isArray(adDomains) && adDomains.length > 0) {
            logger.info({ taskId, domainsCount: adDomains.length }, 'Processing ad_domains array');
            
            // Check all domains via API
            const domainCheckResults = await domainCheckerService.checkDomains(adDomains);
            
            // Save only valid domains (that exist and have metrics)
            for (const domain of adDomains) {
              if (!domain || typeof domain !== 'string') continue;
              
              const checkResult = domainCheckResults.get(domain);
              
              // Check if domain should be saved
              const shouldSave = checkResult?.exists && checkResult?.isValid && 
                                (checkResult.metrics?.domainRank !== undefined || 
                                 checkResult.metrics?.organicKeywords !== undefined ||
                                 checkResult.metrics?.backlinks !== undefined);
              
              if (!shouldSave) {
                skippedDomainsCount++;
                logger.debug({ taskId, domain, reason: checkResult ? 'invalid_or_no_metrics' : 'check_failed' }, 'Skipping domain');
                continue;
              }
              
              // Check for duplicate in database
              const existing = await prisma.parsedData.findFirst({
                where: {
                  taskId: taskId,
                  adDomain: domain,
                },
              });
              
              if (existing) {
                skippedDomainsCount++;
                logger.debug({ taskId, domain }, 'Duplicate domain skipped');
                continue;
              }
              
              // Save domain
              try {
                await prisma.parsedData.create({
                  data: {
                    taskId: taskId,
                    url: url,
                    adDomain: domain,
                    adUrl: adUrls.find((u: string) => u && u.includes(domain)) || null,
                    screenshotPath: screenshots[0] || resultData.screenshot || resultData.screenshotPath || null,
                  },
                });
                savedDomainsCount++;
                logger.info({ taskId, domain, metrics: checkResult.metrics }, 'Domain saved after API check');
              } catch (saveErr: any) {
                // Handle unique constraint violation
                if (saveErr.code === 'P2002') {
                  skippedDomainsCount++;
                  logger.debug({ taskId, domain }, 'Duplicate domain (unique constraint)');
                } else {
                  logger.warn({ taskId, domain, error: saveErr }, 'Failed to save domain');
                }
              }
            }
            
            logger.info({ 
              taskId, 
              totalDomains: adDomains.length, 
              saved: savedDomainsCount, 
              skipped: skippedDomainsCount 
            }, 'Ad domains processing completed');
          } else {
            // Fallback: save single domain if ad_domains array is empty
            const singleDomain = resultData.ad_domain || resultData.adDomain;
            if (singleDomain) {
              // Check domain via API before saving
              const shouldSave = await domainCheckerService.shouldSaveDomain(singleDomain);
              
              if (shouldSave) {
                // Check for duplicate
                const existing = await prisma.parsedData.findFirst({
                  where: {
                    taskId: taskId,
                    adDomain: singleDomain,
                  },
                });
                
                if (!existing) {
          await prisma.parsedData.create({
            data: {
              taskId: taskId,
              url: url,
              adUrl: adUrls[0] || resultData.adUrl || null,
                      adDomain: singleDomain,
              screenshotPath: screenshots[0] || resultData.screenshot || resultData.screenshotPath || null,
            },
          });
                  savedDomainsCount = 1;
                  logger.info({ taskId, domain: singleDomain }, 'Single domain saved after API check');
                } else {
                  logger.debug({ taskId, domain: singleDomain }, 'Duplicate single domain skipped');
                }
              } else {
                logger.debug({ taskId, domain: singleDomain }, 'Single domain skipped (invalid or no metrics)');
              }
            }
          }
          
          // Log extracted data count
          const extractedCount = (titles?.length || 0) + (links?.length || 0);
          logger.info({ 
            taskId, 
            taskType, 
            titlesCount: titles?.length, 
            linksCount: links?.length,
            savedDomainsCount,
            skippedDomainsCount
          }, 'Parsed data auto-saved');
          
          if (extractedCount > 0 || savedDomainsCount > 0) {
            broadcastLog(`💾 Артефакт парсинга сохранён: ${url} (${extractedCount} элементов, ${savedDomainsCount} доменов)`, 'info');
          } else {
            broadcastLog(`💾 Артефакт парсинга сохранён: ${url}`, 'info');
          }
        } catch (parseErr) {
          // Don't fail the request if parsed data save fails
          logger.warn({ taskId, error: parseErr }, 'Failed to auto-save parsed data');
        }
      } else if (finalStatus === 'completed') {
        // For non-parsing tasks, just log without saving to parsed_data
        const actionVerb = taskType === 'surfing' ? '🏄 Серфинг' : 
                          taskType === 'screenshot' ? '📸 Скриншот' : 
                          taskType === 'uniqueness' ? '🔧 Уникализация' : '✅ Задача';
        broadcastLog(`${actionVerb} завершён: ${task.name}`, 'info');
      }

      logger.info({ taskId, deviceId, status: finalStatus, resultKeys: Object.keys(resultData) }, 'Task result submitted via /tasks/:id/result');
      
      const resultEmoji = finalStatus === 'completed' ? '🎉' : '💥';
      broadcastLog(`${resultEmoji} Результат задачи ${task.name}: ${finalStatus}`, finalStatus === 'failed' ? 'error' : 'info');
      
      return { success: true, task };
    } catch (error) {
      logger.error({ taskId, error }, 'Failed to submit task result');
      return reply.status(404).send({
        error: { message: 'Task not found', code: 'TASK_NOT_FOUND' },
      });
    }
  });

  fastify.post('/task/result', async (request: FastifyRequest, reply: FastifyReply) => {
    const deviceId = (request.headers['x-device-id'] || request.headers['deviceid']) as string;
    const body = taskResultSchema.parse(request.body);

    if (!deviceId) {
      return reply.status(401).send({
        error: {
          message: 'Missing device ID',
          code: 'MISSING_DEVICE_ID',
        },
      });
    }

    const task = await prisma.task.findUnique({
      where: { id: body.taskId },
    });

    if (!task) {
      return reply.status(404).send({
        error: {
          message: 'Task not found',
          code: 'TASK_NOT_FOUND',
        },
      });
    }

    const updatedTask = await prisma.task.update({
      where: { id: body.taskId },
      data: {
        status: body.status,
        resultJson: body.resultJson,
        completedAt: body.status === 'completed' || body.status === 'failed' ? new Date() : undefined,
      },
    });

    logger.info({ taskId: body.taskId, deviceId, status: body.status }, 'Task result submitted');

    return { task: updatedTask };
  });

  
  fastify.post('/screenshot', async (request: FastifyRequest, reply: FastifyReply) => {
    const deviceId = (request.headers['x-device-id'] || request.headers['deviceid']) as string;
    const taskId = (request.headers['x-task-id'] || request.headers['taskid']) as string;

    if (!deviceId || !taskId) {
      return reply.status(400).send({
        error: {
          message: 'Missing device ID or task ID',
          code: 'MISSING_PARAMETERS',
        },
      });
    }

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

      const buffer = await data.toBuffer();
      const timestamp = new Date();
      const fileName = storageService.generateScreenshotPath(deviceId, taskId, timestamp);
      
      await storageService.uploadFile(buffer, fileName, 'image/png');

      
      await prisma.parsedData.updateMany({
        where: {
          taskId: taskId,
          screenshotPath: null,
        },
        data: {
          screenshotPath: fileName,
        },
      });

      logger.info({ deviceId, taskId, fileName }, 'Screenshot uploaded');

      return {
        success: true,
        path: fileName,
        url: `/api/artifacts/${fileName}`,
      };
    } catch (error) {
      logger.error({ error, deviceId, taskId }, 'Error uploading screenshot');
      return reply.status(500).send({
        error: {
          message: 'Error uploading screenshot',
          code: 'UPLOAD_ERROR',
        },
      });
    }
  });

  
  fastify.post('/parsed-data', async (request: FastifyRequest, reply: FastifyReply) => {
    const deviceId = (request.headers['x-device-id'] || request.headers['deviceid']) as string;
    const body = request.body as {
      taskId: string;
      url: string;
      adUrl?: string;
      adDomain?: string;
      screenshotPath?: string;
    };

    if (!deviceId || !body.taskId) {
      return reply.status(400).send({
        error: {
          message: 'Missing device ID or task ID',
          code: 'MISSING_PARAMETERS',
        },
      });
    }

    try {
      
      if (body.adDomain) {
        // Check for duplicate first
        const existing = await prisma.parsedData.findFirst({
          where: {
            taskId: body.taskId,
            adDomain: body.adDomain,
          },
        });

        if (existing) {
          logger.info({ taskId: body.taskId, adDomain: body.adDomain }, 'Duplicate domain skipped');
          return {
            success: true,
            message: 'Duplicate domain, skipped',
            id: existing.id,
          };
        }

        // Check domain via API BEFORE saving
        const shouldSave = await domainCheckerService.shouldSaveDomain(body.adDomain);
        
        if (!shouldSave) {
          logger.info({ taskId: body.taskId, adDomain: body.adDomain }, 'Domain skipped (invalid or no metrics from API)');
          return {
            success: true,
            message: 'Domain invalid or has no metrics, skipped',
            skipped: true,
          };
        }

        logger.info({ taskId: body.taskId, adDomain: body.adDomain }, 'Domain validated via API, saving');
      }

      const parsedData = await prisma.parsedData.create({
        data: {
          taskId: body.taskId,
          url: body.url,
          adUrl: body.adUrl,
          adDomain: body.adDomain,
          screenshotPath: body.screenshotPath,
        },
      });

      logger.info({ parsedDataId: parsedData.id, taskId: body.taskId }, 'Parsed data saved');

      return {
        success: true,
        id: parsedData.id,
      };
    } catch (error: any) {
      // Handle unique constraint violation
      if (error.code === 'P2002') {
        logger.info({ taskId: body.taskId, adDomain: body.adDomain }, 'Duplicate domain (unique constraint)');
        return {
          success: true,
          message: 'Duplicate domain, skipped',
          skipped: true,
        };
      }
      
      logger.error({ error, deviceId, taskId: body.taskId }, 'Error saving parsed data');
      return reply.status(500).send({
        error: {
          message: 'Error saving parsed data',
          code: 'SAVE_ERROR',
        },
      });
    }
  });

  // Logs endpoint - receive logs from Android Agent
  fastify.post('/logs', async (request: FastifyRequest, reply: FastifyReply) => {
    const deviceId = (request.headers['x-device-id'] || request.headers['deviceid']) as string;
    const body = request.body as { 
      level?: string; 
      tag?: string; 
      message?: string; 
      taskId?: string;
      timestamp?: number;
    };

    if (!deviceId) {
      return reply.status(401).send({
        error: { message: 'Missing device ID', code: 'MISSING_DEVICE_ID' },
      });
    }

    const level = body.level?.toLowerCase() || 'info';
    const tag = body.tag || 'AndroidAgent';
    const message = body.message || '';
    const taskId = body.taskId;

    // Log to backend logger
    const logData = {
      deviceId,
      taskId,
      tag,
      message,
      timestamp: body.timestamp || Date.now(),
    };

    // Use appropriate log level
    switch (level) {
      case 'error':
      case 'e':
        logger.error(logData, `[${tag}] ${message}`);
        break;
      case 'warn':
      case 'w':
        logger.warn(logData, `[${tag}] ${message}`);
        break;
      case 'debug':
      case 'd':
        logger.debug(logData, `[${tag}] ${message}`);
        break;
      default:
        logger.info(logData, `[${tag}] ${message}`);
    }

    // Broadcast to WebSocket clients
    broadcastLog(`[${tag}] ${message}`, level);

    return reply.send({ success: true });
  });

  // Test endpoint for domain checking
  fastify.post('/test/domain-check', async (request: FastifyRequest, reply: FastifyReply) => {
    const body = request.body as { domain?: string; domains?: string[] };
    
    if (!body.domain && (!body.domains || body.domains.length === 0)) {
      return reply.status(400).send({
        error: {
          message: 'Missing domain or domains array',
          code: 'MISSING_DOMAIN',
        },
      });
    }

    try {
      const domainsToCheck = body.domains || (body.domain ? [body.domain] : []);
      
      logger.info({ domains: domainsToCheck }, 'Testing domain check via API');
      
      const results = await domainCheckerService.checkDomains(domainsToCheck);
      
      const formattedResults = Array.from(results.entries()).map(([domain, result]) => ({
        domain,
        isValid: result?.isValid || false,
        exists: result?.exists || false,
        metrics: result?.metrics || null,
        source: result?.source || null,
        error: result?.error || null,
      }));

      return {
        success: true,
        results: formattedResults,
        summary: {
          total: domainsToCheck.length,
          valid: formattedResults.filter(r => r.isValid).length,
          invalid: formattedResults.filter(r => !r.isValid).length,
          exists: formattedResults.filter(r => r.exists).length,
          withMetrics: formattedResults.filter(r => r.metrics && (r.metrics.domainRank || r.metrics.organicKeywords || r.metrics.backlinks)).length,
        },
      };
    } catch (error: any) {
      logger.error({ error, body }, 'Error testing domain check');
      return reply.status(500).send({
        error: {
          message: 'Error checking domains',
          code: 'CHECK_ERROR',
          details: error.message,
        },
      });
    }
  });
}
