import { ParsedData } from '@prisma/client';

export type ParsedDataCreateInput = {
  taskId: string;
  url: string;
  adUrl?: string;
  adDomain?: string;
  screenshotPath?: string;
};

export type ParsedDataWithRelations = ParsedData & {
  task?: {
    id: string;
    name: string;
    type: string;
  };
};

export type ParsedDataExportFilters = {
  taskId?: string;
  adDomain?: string;
  dateFrom?: string;
  dateTo?: string;
  limit?: number;
  offset?: number;
};

