import apiClient from './client';

export interface ParsedData {
  id: string;
  taskId: string;
  url: string;
  adUrl: string | null;
  adDomain: string | null;
  screenshotPath: string | null;
  parsedAt: string;
  task?: {
    id: string;
    name: string;
    type: string;
  };
}

export const parsedDataApi = {
  getAll: async (params?: {
    taskId?: string;
    adDomain?: string;
    limit?: number;
    offset?: number;
  }): Promise<{ data: ParsedData[]; pagination: any }> => {
    const response = await apiClient.get<{ data: ParsedData[]; pagination: any }>('/parsed-data', {
      params,
    });
    return response.data;
  },
  getById: async (id: string): Promise<ParsedData> => {
    const response = await apiClient.get<{ data: ParsedData }>(`/parsed-data/${id}`);
    return response.data.data;
  },
  exportCSV: async (params?: { dateFrom?: string; dateTo?: string }): Promise<Blob> => {
    const response = await apiClient.get('/parsed-data/export/csv', {
      params,
      responseType: 'blob',
    });
    return response.data;
  },
  exportJSON: async (params?: { dateFrom?: string; dateTo?: string }): Promise<ParsedData[]> => {
    const response = await apiClient.get<{ data: ParsedData[] }>('/parsed-data/export/json', {
      params,
    });
    return response.data.data;
  },
  delete: async (id: string): Promise<void> => {
    await apiClient.delete(`/parsed-data/${id}`);
  },
  deleteByTask: async (taskId: string): Promise<void> => {
    await apiClient.delete(`/parsed-data/task/${taskId}`);
  },
};

