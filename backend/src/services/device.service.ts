import { DeviceStatus } from '@prisma/client';
import { prisma } from '../server';

export class DeviceService {
  async getAllDevices() {
    return prisma.device.findMany({
      orderBy: { createdAt: 'desc' },
    });
  }

  async getDeviceById(id: string) {
    return prisma.device.findUnique({
      where: { id },
      include: {
        deviceFingerprints: {
          orderBy: { createdAt: 'desc' },
          take: 10,
        },
      },
    });
  }

  async getDeviceByAndroidId(androidId: string) {
    return prisma.device.findUnique({
      where: { androidId },
    });
  }

  async updateDeviceStatus(id: string, status: DeviceStatus, ipAddress?: string) {
    return prisma.device.update({
      where: { id },
      data: {
        status,
        lastHeartbeat: new Date(),
        ipAddress,
      },
    });
  }

  async getOnlineDevices() {
    return prisma.device.findMany({
      where: {
        status: 'online',
      },
    });
  }

  async getAvailableDevices() {
    return prisma.device.findMany({
      where: {
        status: {
          in: ['online', 'offline'],
        },
      },
    });
  }
}

export const deviceService = new DeviceService();

