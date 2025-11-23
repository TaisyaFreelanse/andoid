import { FastifyInstance } from 'fastify';
import { logger } from '../utils/logger';


const activeConnections = new Set<any>();

export async function logsRoutes(fastify: FastifyInstance) {
  
  fastify.get('/stream', { websocket: true }, async (socket, request) => {
    logger.info({ 
      url: request.url, 
      headers: {
        upgrade: request.headers.upgrade,
        connection: request.headers.connection,
        'sec-websocket-key': request.headers['sec-websocket-key'],
        'sec-websocket-version': request.headers['sec-websocket-version'],
      },
      query: request.query,
      readyState: socket.readyState 
    }, 'WebSocket connection handler called');
    
    // Проверяем токен из query параметра (опционально для WebSocket)
    const token = (request.query as any)?.token;
    if (token) {
      try {
        const decoded = await fastify.jwt.verify(token);
        logger.info({ userId: (decoded as any).id, username: (decoded as any).username }, 'WebSocket authenticated via token');
      } catch (error) {
        logger.warn({ error: error instanceof Error ? error.message : String(error) }, 'WebSocket token verification failed, allowing connection anyway');
        // Не закрываем соединение, так как на Этапе 2 это не критично
      }
    } else {
      logger.warn('WebSocket connection without token, allowing anyway (Stage 2)');
    }
    
   
    activeConnections.add(socket);
    logger.info({ totalConnections: activeConnections.size }, 'WebSocket connection added to active set');
    
    
    try {
      const welcomeMsg = JSON.stringify({
        type: 'connected',
        message: 'WebSocket подключен. Ожидание логов от Android Agent (Этап 3).',
        timestamp: new Date().toISOString(),
      });
      socket.send(welcomeMsg);
      logger.info('Welcome message sent');
    } catch (error) {
      logger.error({ error }, 'Error sending welcome message');
    }
    
    
    socket.on('message', (message: Buffer) => {
      try {
        const data = JSON.parse(message.toString());
        logger.info({ data }, 'Received WebSocket message from client');
        
        
        if (data.type === 'ping') {
          socket.send(JSON.stringify({
            type: 'pong',
            timestamp: new Date().toISOString(),
          }));
          logger.info('Pong sent');
        }
      } catch (error) {
        logger.error({ error, message: message.toString() }, 'Error processing WebSocket message');
      }
    });

    
    // Уменьшаем интервал heartbeat до 3 секунд, чтобы Render не закрывал idle соединения
    const interval = setInterval(() => {
      try {
        if (socket.readyState === 1) { 
          socket.send(JSON.stringify({
            type: 'heartbeat',
            timestamp: new Date().toISOString(),
          }));
        } else {
          clearInterval(interval);
          activeConnections.delete(socket);
          logger.info('Heartbeat stopped, connection closed');
        }
      } catch (error) {
        logger.error({ error }, 'Error sending heartbeat');
        clearInterval(interval);
        activeConnections.delete(socket);
      }
    }, 3000);

    
    socket.on('close', (code: number, reason: Buffer) => {
      clearInterval(interval);
      activeConnections.delete(socket);
      logger.info({ 
        code, 
        reason: reason.toString(),
        remainingConnections: activeConnections.size 
      }, 'WebSocket closed');
    });

    
    socket.on('error', (error: any) => {
      clearInterval(interval);
      activeConnections.delete(socket);
      logger.error({ 
        error: error.message || error,
        stack: error.stack 
      }, 'WebSocket error');
    });
    
    logger.info('WebSocket log stream handler fully registered');
  });
}


export function broadcastLog(message: string, level: string = 'info') {
  const logData = {
    type: 'log',
    message,
    level,
    timestamp: new Date().toISOString(),
  };
  
  const data = JSON.stringify(logData);
  let sentCount = 0;
  
  activeConnections.forEach((socket) => {
    try {
      if (socket.readyState === 1) { 
        socket.send(data);
        sentCount++;
      }
    } catch (error) {
      logger.error({ error }, 'Error broadcasting log');
      activeConnections.delete(socket);
    }
  });
  
  if (sentCount > 0) {
    logger.debug({ sentCount, totalConnections: activeConnections.size }, 'Log broadcasted');
  }
}

