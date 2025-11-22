import { FastifyRequest, FastifyReply } from 'fastify';

export interface AuthenticatedRequest extends FastifyRequest {
  user: {
    id: string;
    username: string;
    email: string;
    role: string;
  };
}

export async function authenticate(
  request: FastifyRequest,
  reply: FastifyReply
): Promise<void> {
  try {
    await request.jwtVerify();
    
    (request as AuthenticatedRequest).user = request.user as any;
  } catch (error) {
    reply.status(401).send({
      error: {
        message: 'Unauthorized',
        code: 'UNAUTHORIZED',
      },
    });
  }
}

