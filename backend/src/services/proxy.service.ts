import { ProxyStatus, ProxyType, Proxy as PrismaProxy } from '@prisma/client';
import bcrypt from 'bcrypt';
import { prisma } from '../server';

export class ProxyService {
  async getAllProxies() {
    return prisma.proxy.findMany({
      orderBy: { createdAt: 'desc' },
    });
  }

  async getProxyById(id: string) {
    return prisma.proxy.findUnique({
      where: { id },
    });
  }

  async getActiveProxies() {
    return prisma.proxy.findMany({
      where: {
        status: 'active',
      },
    });
  }

  async getProxyByCountry(country: string) {
    return prisma.proxy.findMany({
      where: {
        status: 'active',
        country,
      },
    });
  }

  async createProxy(data: {
    host: string;
    port: number;
    username?: string;
    password?: string;
    type: ProxyType;
    country?: string;
    timezone?: string;
  }) {
    let passwordEncrypted: string | null = null;
    if (data.password) {
      passwordEncrypted = await bcrypt.hash(data.password, 10);
    }

    return prisma.proxy.create({
      data: {
        host: data.host,
        port: data.port,
        username: data.username,
        passwordEncrypted,
        type: data.type,
        country: data.country,
        timezone: data.timezone,
      },
    });
  }

  async updateProxy(id: string, data: {
    host?: string;
    port?: number;
    username?: string;
    password?: string;
    status?: ProxyStatus;
    country?: string;
    timezone?: string;
  }) {
    const updateData: any = { ...data };
    
    if (data.password) {
      updateData.passwordEncrypted = await bcrypt.hash(data.password, 10);
      delete updateData.password;
    }

    return prisma.proxy.update({
      where: { id },
      data: updateData,
    });
  }

  async markProxyAsUsed(id: string) {
    return prisma.proxy.update({
      where: { id },
      data: {
        lastUsed: new Date(),
      },
    });
  }

  async getRandomProxy(country?: string) {
    const where: any = {
      status: 'active',
    };

    if (country) {
      where.country = country;
    }

    const proxies = await prisma.proxy.findMany({
      where,
      orderBy: {
        lastUsed: 'asc',
      },
      take: 1,
    });

    return proxies[0] || null;
  }

  async verifyProxyPassword(proxy: PrismaProxy, password: string): Promise<boolean> {
    if (!proxy.passwordEncrypted) {
      return !password;
    }
    return bcrypt.compare(password, proxy.passwordEncrypted);
  }
}

export const proxyService = new ProxyService();

