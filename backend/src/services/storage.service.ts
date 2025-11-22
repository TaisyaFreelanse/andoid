import { Client } from 'minio';
import { config } from '../config';
import { logger } from '../utils/logger';
import { Readable } from 'stream';

export class StorageService {
  private client: Client;
  private bucketName: string;

  constructor() {
    this.client = new Client({
      endPoint: config.minio.endpoint,
      port: config.minio.port,
      useSSL: config.minio.useSSL,
      accessKey: config.minio.accessKey,
      secretKey: config.minio.secretKey,
    });

    this.bucketName = config.minio.bucketName;
    this.ensureBucket();
  }

  private async ensureBucket() {
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
    try {
      const objectName = fileName;

      if (Buffer.isBuffer(fileStream)) {
        await this.client.putObject(
          this.bucketName,
          objectName,
          fileStream,
          fileStream.length,
          {
            'Content-Type': contentType || 'application/octet-stream',
          }
        );
      } else {
        await this.client.putObject(
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
    try {
      const url = await this.client.presignedGetObject(
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
    try {
      await this.client.removeObject(this.bucketName, objectName);
      logger.info({ objectName }, 'File deleted from MinIO');
    } catch (error) {
      logger.error({ error, objectName }, 'Error deleting file from MinIO');
      throw error;
    }
  }

  async fileExists(objectName: string): Promise<boolean> {
    try {
      await this.client.statObject(this.bucketName, objectName);
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

