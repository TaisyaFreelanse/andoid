import { Client } from 'minio';
import { config } from '../config';
import { logger } from '../utils/logger';
import { Readable } from 'stream';

export interface StorageStats {
  isEnabled: boolean;
  bucketName: string;
  endpoint: string;
  totalFiles?: number;
  totalSize?: number;
}

export interface FileInfo {
  name: string;
  path: string;
  size: number;
  lastModified: Date;
  etag?: string;
}

export class StorageService {
  private client: Client | null = null;
  private bucketName: string;
  private isEnabled: boolean;

  constructor() {
    // Проверяем, настроен ли MinIO/S3
    this.isEnabled = !!(
      config.minio.accessKey &&
      config.minio.secretKey &&
      config.minio.endpoint &&
      config.minio.endpoint !== 'localhost'
    );

    this.bucketName = config.minio.bucketName;

    if (this.isEnabled) {
      try {
        this.client = new Client({
          endPoint: config.minio.endpoint,
          port: config.minio.port,
          useSSL: config.minio.useSSL,
          accessKey: config.minio.accessKey,
          secretKey: config.minio.secretKey,
        });
        this.ensureBucket();
        logger.info({ 
          endpoint: config.minio.endpoint, 
          bucket: this.bucketName 
        }, 'MinIO client initialized');
      } catch (error) {
        logger.warn({ error }, 'Failed to initialize MinIO client, storage will be disabled');
        this.isEnabled = false;
        this.client = null;
      }
    } else {
      logger.warn('MinIO/S3 not configured, file storage will be disabled');
    }
  }

  private isAvailable(): boolean {
    return this.isEnabled && this.client !== null;
  }

  private async ensureBucket() {
    if (!this.client) return;
    
    try {
      const exists = await this.client.bucketExists(this.bucketName);
      if (!exists) {
        await this.client.makeBucket(this.bucketName);
        logger.info({ bucket: this.bucketName }, 'Created MinIO bucket');
      }
    } catch (error) {
      logger.error({ error }, 'Error ensuring bucket exists');
    }
  }

  // Get storage stats
  async getStats(): Promise<StorageStats> {
    const stats: StorageStats = {
      isEnabled: this.isEnabled,
      bucketName: this.bucketName,
      endpoint: config.minio.endpoint,
    };

    if (!this.isAvailable()) {
      return stats;
    }

    try {
      let totalFiles = 0;
      let totalSize = 0;

      const objectsStream = this.client!.listObjectsV2(this.bucketName, '', true);
      
      for await (const obj of objectsStream) {
        totalFiles++;
        totalSize += obj.size || 0;
      }

      stats.totalFiles = totalFiles;
      stats.totalSize = totalSize;
    } catch (error) {
      logger.warn({ error }, 'Failed to get storage stats');
    }

    return stats;
  }

  async uploadFile(
    fileStream: Readable | Buffer,
    fileName: string,
    contentType?: string
  ): Promise<string> {
    if (!this.isAvailable()) {
      logger.warn({ fileName }, 'Storage not available, file upload skipped');
      throw new Error('Storage service is not configured');
    }

    try {
      const objectName = fileName;

      if (Buffer.isBuffer(fileStream)) {
        await this.client!.putObject(
          this.bucketName,
          objectName,
          fileStream,
          fileStream.length,
          {
            'Content-Type': contentType || 'application/octet-stream',
          }
        );
      } else {
        await this.client!.putObject(
          this.bucketName,
          objectName,
          fileStream,
          {
            'Content-Type': contentType || 'application/octet-stream',
          }
        );
      }

      logger.info({ objectName }, 'File uploaded to MinIO');
      return objectName;
    } catch (error) {
      logger.error({ error, fileName }, 'Error uploading file to MinIO');
      throw error;
    }
  }

  async getPresignedUrl(objectName: string, expiresIn: number = 3600): Promise<string> {
    if (!this.isAvailable()) {
      throw new Error('Storage service is not configured');
    }

    try {
      const url = await this.client!.presignedGetObject(
        this.bucketName,
        objectName,
        expiresIn
      );
      return url;
    } catch (error) {
      logger.error({ error, objectName }, 'Error generating presigned URL');
      throw error;
    }
  }

  async getPresignedUploadUrl(objectName: string, expiresIn: number = 3600): Promise<string> {
    if (!this.isAvailable()) {
      throw new Error('Storage service is not configured');
    }

    try {
      const url = await this.client!.presignedPutObject(
        this.bucketName,
        objectName,
        expiresIn
      );
      return url;
    } catch (error) {
      logger.error({ error, objectName }, 'Error generating presigned upload URL');
      throw error;
    }
  }

  async deleteFile(objectName: string): Promise<void> {
    if (!this.isAvailable()) {
      logger.warn({ objectName }, 'Storage not available, file deletion skipped');
      return;
    }

    try {
      await this.client!.removeObject(this.bucketName, objectName);
      logger.info({ objectName }, 'File deleted from MinIO');
    } catch (error) {
      logger.error({ error, objectName }, 'Error deleting file from MinIO');
      throw error;
    }
  }

  async deleteFiles(objectNames: string[]): Promise<{ deleted: number; failed: number }> {
    if (!this.isAvailable()) {
      logger.warn({ count: objectNames.length }, 'Storage not available, file deletion skipped');
      return { deleted: 0, failed: objectNames.length };
    }

    let deleted = 0;
    let failed = 0;

    for (const objectName of objectNames) {
      try {
        await this.client!.removeObject(this.bucketName, objectName);
        deleted++;
      } catch (error) {
        logger.warn({ error, objectName }, 'Failed to delete file');
        failed++;
      }
    }

    logger.info({ deleted, failed }, 'Bulk delete completed');
    return { deleted, failed };
  }

  async fileExists(objectName: string): Promise<boolean> {
    if (!this.isAvailable()) {
      return false;
    }

    try {
      await this.client!.statObject(this.bucketName, objectName);
      return true;
    } catch (error) {
      return false;
    }
  }

  async getFileInfo(objectName: string): Promise<FileInfo | null> {
    if (!this.isAvailable()) {
      return null;
    }

    try {
      const stat = await this.client!.statObject(this.bucketName, objectName);
      return {
        name: objectName.split('/').pop() || objectName,
        path: objectName,
        size: stat.size,
        lastModified: stat.lastModified,
        etag: stat.etag,
      };
    } catch (error) {
      return null;
    }
  }

  async listFiles(prefix: string = '', limit: number = 100): Promise<FileInfo[]> {
    if (!this.isAvailable()) {
      return [];
    }

    try {
      const files: FileInfo[] = [];
      const objectsStream = this.client!.listObjectsV2(this.bucketName, prefix, true);
      
      for await (const obj of objectsStream) {
        if (files.length >= limit) break;
        
        files.push({
          name: obj.name?.split('/').pop() || obj.name || '',
          path: obj.name || '',
          size: obj.size || 0,
          lastModified: obj.lastModified || new Date(),
          etag: obj.etag,
        });
      }

      return files;
    } catch (error) {
      logger.error({ error, prefix }, 'Error listing files');
      return [];
    }
  }

  async listFilesByDevice(deviceId: string, limit: number = 100): Promise<FileInfo[]> {
    return this.listFiles(`screenshots/${deviceId}/`, limit);
  }

  async listFilesByTask(deviceId: string, taskId: string): Promise<FileInfo[]> {
    return this.listFiles(`screenshots/${deviceId}/${taskId}/`, 1000);
  }

  async getFileContent(objectName: string): Promise<Buffer | null> {
    if (!this.isAvailable()) {
      return null;
    }

    try {
      const stream = await this.client!.getObject(this.bucketName, objectName);
      const chunks: Buffer[] = [];
      
      for await (const chunk of stream) {
        chunks.push(chunk);
      }
      
      return Buffer.concat(chunks);
    } catch (error) {
      logger.error({ error, objectName }, 'Error getting file content');
      return null;
    }
  }

  async copyFile(sourceObject: string, destObject: string): Promise<boolean> {
    if (!this.isAvailable()) {
      return false;
    }

    try {
      // CopyConditions is required for copyObject
      const conds = new (require('minio').CopyConditions)();
      await this.client!.copyObject(
        this.bucketName,
        destObject,
        `/${this.bucketName}/${sourceObject}`,
        conds
      );
      logger.info({ sourceObject, destObject }, 'File copied');
      return true;
    } catch (error) {
      logger.error({ error, sourceObject, destObject }, 'Error copying file');
      return false;
    }
  }

  generateScreenshotPath(deviceId: string, taskId: string, timestamp: Date): string {
    const date = timestamp.toISOString().split('T')[0];
    const time = timestamp.toISOString().replace(/[:.]/g, '-');
    return `screenshots/${deviceId}/${taskId}/${date}/${time}.png`;
  }

  generateArtifactPath(type: string, id: string, filename: string): string {
    const date = new Date().toISOString().split('T')[0];
    return `${type}/${date}/${id}/${filename}`;
  }

  // Clean up old files (for maintenance)
  async cleanupOldFiles(prefix: string, olderThanDays: number): Promise<number> {
    if (!this.isAvailable()) {
      return 0;
    }

    try {
      const cutoffDate = new Date();
      cutoffDate.setDate(cutoffDate.getDate() - olderThanDays);

      const filesToDelete: string[] = [];
      const objectsStream = this.client!.listObjectsV2(this.bucketName, prefix, true);
      
      for await (const obj of objectsStream) {
        if (obj.lastModified && obj.lastModified < cutoffDate) {
          filesToDelete.push(obj.name || '');
        }
      }

      if (filesToDelete.length > 0) {
        const result = await this.deleteFiles(filesToDelete);
        logger.info({ 
          prefix, 
          olderThanDays, 
          deleted: result.deleted,
          failed: result.failed 
        }, 'Cleanup completed');
        return result.deleted;
      }

      return 0;
    } catch (error) {
      logger.error({ error, prefix, olderThanDays }, 'Error during cleanup');
      return 0;
    }
  }

  // Get total size for a prefix
  async getTotalSize(prefix: string): Promise<number> {
    if (!this.isAvailable()) {
      return 0;
    }

    try {
      let totalSize = 0;
      const objectsStream = this.client!.listObjectsV2(this.bucketName, prefix, true);
      
      for await (const obj of objectsStream) {
        totalSize += obj.size || 0;
      }

      return totalSize;
    } catch (error) {
      logger.error({ error, prefix }, 'Error getting total size');
      return 0;
    }
  }
}

export const storageService = new StorageService();
