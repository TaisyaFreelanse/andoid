import { FastifyInstance, FastifyRequest, FastifyReply } from 'fastify';
import bcrypt from 'bcrypt';
import { prisma } from '../server';
import { loginSchema, refreshTokenSchema } from '../utils/validator';
import { logger } from '../utils/logger';
import { config } from '../config';

export async function authRoutes(fastify: FastifyInstance) {
 
  fastify.post('/login', async (request: FastifyRequest, reply: FastifyReply) => {
    const body = loginSchema.parse(request.body);

    const user = await prisma.user.findFirst({
      where: {
        OR: [
          { username: body.username },
          { email: body.username },
        ],
      },
    });

    if (!user) {
      return reply.status(401).send({
        error: {
          message: 'Invalid credentials',
          code: 'INVALID_CREDENTIALS',
        },
      });
    }

    const isValidPassword = await bcrypt.compare(body.password, user.passwordHash);
    if (!isValidPassword) {
      return reply.status(401).send({
        error: {
          message: 'Invalid credentials',
          code: 'INVALID_CREDENTIALS',
        },
      });
    }

    const token = fastify.jwt.sign(
      { 
        id: user.id, 
        username: user.username, 
        email: user.email, 
        role: user.role 
      },
      { 
        expiresIn: config.jwt.expiresIn 
      }
    );

    const refreshToken = fastify.jwt.sign(
      { 
        id: user.id, 
        type: 'refresh' 
      },
      { 
        expiresIn: config.jwt.refreshExpiresIn 
      }
    );

    logger.info({ userId: user.id, username: user.username }, 'User logged in');

    return {
      token,
      refreshToken,
      user: {
        id: user.id,
        username: user.username,
        email: user.email,
        role: user.role,
      },
    };
  });

  
  fastify.post('/refresh', async (request: FastifyRequest, reply: FastifyReply) => {
    const body = refreshTokenSchema.parse(request.body);

    try {
      const decoded = fastify.jwt.verify<{ id: string; type: string }>(body.refreshToken);
      
      if (decoded.type !== 'refresh') {
        return reply.status(401).send({
          error: {
            message: 'Invalid refresh token',
            code: 'INVALID_REFRESH_TOKEN',
          },
        });
      }

      const user = await prisma.user.findUnique({
        where: { id: decoded.id },
      });

      if (!user) {
        return reply.status(401).send({
          error: {
            message: 'User not found',
            code: 'USER_NOT_FOUND',
          },
        });
      }

      const token = fastify.jwt.sign(
        { 
          id: user.id, 
          username: user.username, 
          email: user.email, 
          role: user.role 
        },
        { 
          expiresIn: config.jwt.expiresIn 
        }
      );

      return { token };
    } catch (error) {
      return reply.status(401).send({
        error: {
          message: 'Invalid refresh token',
          code: 'INVALID_REFRESH_TOKEN',
        },
      });
    }
  });
}

