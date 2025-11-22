import apiClient from './client';

export interface Device {
  id: string;
  name: string | null;
  androidId: string;
  aaid: string | null;
  ipAddress: string | null;
  status: 'online' | 'offline' | 'busy' | 'error';
  lastHeartbeat: string | null;
  browserType: 'chrome' | 'webview' | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateDeviceData {
  name: string;
  androidId: string;
  aaid?: string;
  browserType: 'chrome' | 'webview';
}

export const devicesApi = {
  getAll: async (): Promise<Device[]> => {
    const response = await apiClient.get<{ devices: Device[] }>('/devices');
    return response.data.devices;
  },
  getById: async (id: string): Promise<Device> => {
    const response = await apiClient.get<{ device: Device }>(`/devices/${id}`);
    return response.data.device;
  },
  create: async (data: CreateDeviceData): Promise<Device> => {
    const response = await apiClient.post<{ device: Device }>('/devices', data);
    return response.data.device;
  },
  update: async (id: string, data: Partial<CreateDeviceData>): Promise<Device> => {
    const response = await apiClient.patch<{ device: Device }>(`/devices/${id}`, data);
    return response.data.device;
  },
  delete: async (id: string): Promise<void> => {
    await apiClient.delete(`/devices/${id}`);
  },
};

