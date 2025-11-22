import { FastifyRequest, FastifyReply } from 'fastify';
import { AuthenticatedRequest } from './auth.middleware';

type UserRole = 'admin' | 'operator' | 'viewer';

export function requireRole(...allowedRoles: UserRole[]) {
  return async (
    request: FastifyRequest,
    reply: FastifyReply
  ): Promise<void> => {
    const authRequest = request as AuthenticatedRequest;
    
    if (!authRequest.user) {
      reply.status(401).send({
        error: {
          message: 'Unauthorized',
          code: 'UNAUTHORIZED',
        },
      });
      return;
    }

    const userRole = authRequest.user.role as UserRole;
    
    if (!allowedRoles.includes(userRole)) {
      reply.status(403).send({
        error: {
          message: 'Forbidden: Insufficient permissions',
          code: 'FORBIDDEN',
        },
      });
      return;
    }
  };
}

