import { FastifyInstance } from 'fastify';
import { logger } from '../utils/logger';


const activeConnections = new Set<any>();

export async function logsRoutes(fastify: FastifyInstance) {
  
  fastify.get('/stream', { websocket: true }, (socket, request) => {
    logger.info({ url: request.url, readyState: socket.readyState }, 'WebSocket connection handler called');
    
   
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
    }, 5000);

    
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

