import { z } from 'zod';


export const uuidSchema = z.string().uuid();
export const emailSchema = z.string().email();
export const passwordSchema = z.string().min(8);


export const loginSchema = z.object({
  username: z.string().min(1),
  password: z.string().min(1),
});

export const refreshTokenSchema = z.object({
  refreshToken: z.string().min(1),
});


export const createDeviceSchema = z.object({
  name: z.string().optional(),
  androidId: z.string().min(1),
  aaid: z.string().optional(),
  browserType: z.enum(['chrome', 'webview']).optional(),
});

export const updateDeviceSchema = z.object({
  name: z.string().optional(),
  status: z.enum(['online', 'offline', 'busy', 'error']).optional(),
  browserType: z.enum(['chrome', 'webview']).optional(),
});


export const createTaskSchema = z.object({
  name: z.string().min(1),
  type: z.enum(['surfing', 'parsing', 'uniqueness', 'screenshot']),
  configJson: z.record(z.any()),
  deviceId: z.string().uuid().optional(),
  proxyId: z.string().uuid().optional(),
});

export const updateTaskSchema = z.object({
  status: z.enum(['pending', 'assigned', 'running', 'completed', 'failed', 'cancelled']).optional(),
  resultJson: z.record(z.any()).optional(),
});


export const createProxySchema = z.object({
  host: z.string().min(1),
  port: z.number().int().min(1).max(65535),
  username: z.string().optional(),
  password: z.string().optional(),
  type: z.enum(['http', 'https', 'socks5']),
  country: z.string().length(2).optional(),
  timezone: z.string().optional(),
});

export const updateProxySchema = z.object({
  status: z.enum(['active', 'inactive', 'error']).optional(),
  host: z.string().min(1).optional(),
  port: z.number().int().min(1).max(65535).optional(),
  username: z.string().optional(),
  password: z.string().optional(),
});


export const registerDeviceSchema = z.object({
  androidId: z.string().min(1),
  aaid: z.string().nullable().optional(),
  browserType: z.enum(['chrome', 'webview']).nullable().optional(),
  deviceInfo: z.record(z.any()).nullable().optional(),
  isRooted: z.boolean().nullable().optional(),
  existingDeviceId: z.string().nullable().optional(),
  rootCheckDetails: z.string().nullable().optional(),
  rootCheckMethods: z.string().nullable().optional(),
  // Additional fields from Android client
  manufacturer: z.string().nullable().optional(),
  model: z.string().nullable().optional(),
  version: z.string().nullable().optional(),
  userAgent: z.string().nullable().optional(),
  brand: z.string().nullable().optional(),
  country: z.string().nullable().optional(),
  language: z.string().nullable().optional(),
  timezone: z.string().nullable().optional(),
  screenWidth: z.number().nullable().optional(),
  screenHeight: z.number().nullable().optional(),
  sdkVersion: z.number().nullable().optional(),
});

export const heartbeatSchema = z.object({
  status: z.enum(['online', 'offline', 'busy', 'error']).optional(),
  ipAddress: z.string().optional(),
});

export const taskResultSchema = z.object({
  taskId: z.string().uuid(),
  status: z.enum(['completed', 'failed']),
  resultJson: z.record(z.any()).optional(),
  error: z.string().optional(),
});

