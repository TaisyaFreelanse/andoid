import { Client } from 'minio';
import { config } from '../config';
import { logger } from '../utils/logger';
import { Readable } from 'stream';

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

  generateScreenshotPath(deviceId: string, taskId: string, timestamp: Date): string {
    const date = timestamp.toISOString().split('T')[0];
    const time = timestamp.toISOString().replace(/[:.]/g, '-');
    return `screenshots/${deviceId}/${taskId}/${date}/${time}.png`;
  }
}

export const storageService = new StorageService();

