import { Device, DeviceStatus, BrowserType } from '@prisma/client';

export type DeviceCreateInput = {
  name?: string;
  androidId: string;
  aaid?: string;
  browserType?: BrowserType;
};

export type DeviceUpdateInput = {
  name?: string;
  status?: DeviceStatus;
  browserType?: BrowserType;
  lastHeartbeat?: Date;
  ipAddress?: string;
};

export type DeviceWithRelations = Device & {
  deviceFingerprints?: any[];
  tasks?: any[];
};

