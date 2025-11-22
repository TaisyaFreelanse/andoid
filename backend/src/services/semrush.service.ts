import { prisma } from '../server';
import { config } from '../config';
import { logger } from '../utils/logger';

export class SemrushService {
  private readonly apiKey: string;
  private readonly baseUrl = 'https://api.semrush.com';

  constructor() {
    this.apiKey = config.semrush.apiKey;
    if (!this.apiKey) {
      logger.warn('Semrush API key not configured');
    }
  }

  async checkDomain(domain: string): Promise<any> {
    
    const cached = await prisma.semrushData.findUnique({
      where: { domain },
    });

    if (cached && cached.expiresAt > new Date()) {
      logger.debug({ domain }, 'Using cached Semrush data');
      return cached.dataJson;
    }

    
    if (!this.apiKey) {
      throw new Error('Semrush API key not configured');
    }

    try {
      
      const url = `${this.baseUrl}/?key=${this.apiKey}&type=domain_ranks&domain=${domain}&export_columns=domain_rank,organic_keywords,organic_traffic,backlinks_num`;

      const response = await fetch(url);
      
      if (!response.ok) {
        throw new Error(`Semrush API error: ${response.statusText}`);
      }

      const data = await response.text();
      
      
      const lines = data.trim().split('\n');
      if (lines.length < 2) {
        throw new Error('Invalid Semrush API response');
      }

      const headers = lines[0].split(';').map(h => h.trim());
      const values = lines[1].split(';').map(v => v.trim());
      
      const result: any = {};
      headers.forEach((header, index) => {
        const value = values[index];
        
        if (value && !isNaN(Number(value))) {
          result[header] = Number(value);
        } else {
          result[header] = value || null;
        }
      });

      
      const expiresAt = new Date();
      expiresAt.setHours(expiresAt.getHours() + 24);

      await prisma.semrushData.upsert({
        where: { domain },
        update: {
          dataJson: result,
          checkedAt: new Date(),
          expiresAt,
        },
        create: {
          domain,
          dataJson: result,
          checkedAt: new Date(),
          expiresAt,
        },
      });

      logger.info({ domain }, 'Semrush data fetched and cached');

      return result;
    } catch (error) {
      logger.error({ error, domain }, 'Error fetching Semrush data');
      throw error;
    }
  }

  async getCachedDomain(domain: string) {
    const cached = await prisma.semrushData.findUnique({
      where: { domain },
    });

    if (cached && cached.expiresAt > new Date()) {
      return cached.dataJson;
    }

    return null;
  }

  async cleanupExpiredCache() {
    const deleted = await prisma.semrushData.deleteMany({
      where: {
        expiresAt: {
          lt: new Date(),
        },
      },
    });

    logger.info({ count: deleted.count }, 'Cleaned up expired Semrush cache');
    return deleted.count;
  }
}

export const semrushService = new SemrushService();

