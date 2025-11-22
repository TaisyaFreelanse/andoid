import { Proxy, ProxyType, ProxyStatus } from '@prisma/client';

export type ProxyCreateInput = {
  host: string;
  port: number;
  username?: string;
  password?: string;
  type: ProxyType;
  country?: string;
  timezone?: string;
};

export type ProxyUpdateInput = {
  host?: string;
  port?: number;
  username?: string;
  password?: string;
  status?: ProxyStatus;
  country?: string;
  timezone?: string;
  lastUsed?: Date;
};

export type ProxySafe = Omit<Proxy, 'passwordEncrypted'>;

