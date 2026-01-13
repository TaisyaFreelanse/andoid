import apiClient from './client';

export interface Artifact {
  id: string;
  taskId: string;
  deviceId: string;
  path: string;
  type: string;
  size: number | null;
  mimeType: string | null;
  url: string | null; // URL страницы, на которой был сделан скриншот
  imageUrl: string | null; // Presigned URL для доступа к самому скриншоту
  metadata: {
    device_android_id?: string;
    device_aaid?: string;
    device_user_agent?: string;
    device_model?: string;
    device_manufacturer?: string;
    device_timezone?: string;
    device_locale?: string;
    proxy_id?: string;
    proxy_host?: string;
    proxy_port?: number;
    proxy_type?: string;
    proxy_country?: string;
    proxy_state?: string;
    proxy_ip?: string;
    proxy_location_country?: string;
    proxy_location_state?: string;
    proxy_location_city?: string;
    proxy_location_timezone?: string;
    proxy_location_latitude?: number;
    proxy_location_longitude?: number;
    proxy_location_ip?: string;
  } | null;
  capturedAt: string;
  createdAt: string;
}

export interface ArtifactsResponse {
  artifacts: Artifact[];
  pagination: {
    total: number;
    limit: number;
    offset: number;
    hasMore: boolean;
  };
}

export interface ArtifactsStats {
  total: number;
  recent24h: number;
  byDevice: Record<string, number>;
  totalSizeBytes: number;
  totalSizeMB: number;
}

export const artifactsApi = {
  getAll: async (params?: {
    deviceId?: string;
    taskId?: string;
    type?: string;
    limit?: number;
    offset?: number;
    dateFrom?: string;
    dateTo?: string;
  }): Promise<ArtifactsResponse> => {
    const searchParams = new URLSearchParams();
    if (params?.deviceId) searchParams.append('deviceId', params.deviceId);
    if (params?.taskId) searchParams.append('taskId', params.taskId);
    if (params?.type) searchParams.append('type', params.type);
    if (params?.limit) searchParams.append('limit', params.limit.toString());
    if (params?.offset) searchParams.append('offset', params.offset.toString());
    if (params?.dateFrom) searchParams.append('dateFrom', params.dateFrom);
    if (params?.dateTo) searchParams.append('dateTo', params.dateTo);

    const response = await apiClient.get(`/artifacts?${searchParams.toString()}`);
    return response.data;
  },

  getStats: async (params?: {
    deviceId?: string;
    type?: string;
  }): Promise<{ stats: ArtifactsStats }> => {
    const searchParams = new URLSearchParams();
    if (params?.deviceId) searchParams.append('deviceId', params.deviceId);
    if (params?.type) searchParams.append('type', params.type);

    const response = await apiClient.get(`/artifacts/stats?${searchParams.toString()}`);
    return response.data;
  },

  delete: async (path: string): Promise<void> => {
    await apiClient.delete(`/artifacts/by-path?path=${encodeURIComponent(path)}`);
  },
};
