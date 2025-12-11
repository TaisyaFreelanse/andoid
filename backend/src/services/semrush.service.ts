import { prisma } from '../server';
import { config } from '../config';
import { logger } from '../utils/logger';

export class SemrushService {
  private readonly apiKey: string;
  private readonly rapidApiKey: string;
  private readonly useRapidApi: boolean;
  private readonly baseUrl: string;
  private readonly rapidApiHost = 'semrush8.p.rapidapi.com';

  constructor() {
    this.useRapidApi = config.semrush.useRapidApi;
    this.rapidApiKey = config.semrush.rapidApiKey;
    this.apiKey = config.semrush.apiKey;
    
    if (this.useRapidApi) {
      this.baseUrl = `https://${this.rapidApiHost}`;
      if (!this.rapidApiKey) {
        logger.warn('Semrush RapidAPI key not configured');
      }
    } else {
      this.baseUrl = 'https://api.semrush.com';
      if (!this.apiKey) {
        logger.warn('Semrush API key not configured');
      }
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

    
    if (this.useRapidApi && !this.rapidApiKey) {
      throw new Error('Semrush RapidAPI key not configured');
    }
    if (!this.useRapidApi && !this.apiKey) {
      throw new Error('Semrush API key not configured');
    }

    try {
      let response: Response | null = null;
      let data: any;

      if (this.useRapidApi) {
        // RapidAPI Semrush8 uses standard Semrush API format with RapidAPI headers
        // Based on logs: /traffic endpoint doesn't exist (404), but standard format works (429 rate limit)
        // Use standard Semrush API endpoint format with RapidAPI authentication headers
        const endpoints = [
          // Standard Semrush API format with database (most reliable based on logs)
          `${this.baseUrl}/?type=domain_ranks&domain=${encodeURIComponent(domain)}&export_columns=domain_rank,organic_keywords,organic_traffic,backlinks_num&database=us`,
          // Standard Semrush API format without database
          `${this.baseUrl}/?type=domain_ranks&domain=${encodeURIComponent(domain)}&export_columns=domain_rank,organic_keywords,organic_traffic,backlinks_num`,
        ];
        
        let lastError: Error | null = null;
        
        for (const url of endpoints) {
          try {
            const currentResponse = await fetch(url, {
              method: 'GET',
              headers: {
                'X-RapidAPI-Key': this.rapidApiKey,
                'X-RapidAPI-Host': this.rapidApiHost,
              },
            });
            
            response = currentResponse;
            
            if (response.ok) {
              break; // Success, exit loop
            }
            
            if (response.status === 404) {
              // Try next endpoint
              const errorText = await response.text().catch(() => '');
              lastError = new Error(`Endpoint not found: ${url}`);
              logger.warn({ url, status: response.status, errorText: errorText.substring(0, 200), endpointIndex: endpoints.indexOf(url) + 1, totalEndpoints: endpoints.length }, 'Trying next endpoint');
              response = null; // Reset for next iteration
              continue;
            }
            
            // For rate limit (429), log but don't fail immediately - might be temporary
            if (response.status === 429) {
              const errorText = await response.text().catch(() => '');
              logger.warn({ status: response.status, domain, url, errorText }, 'RapidAPI rate limit hit');
              throw new Error(`Semrush RapidAPI rate limit: ${response.status} ${response.statusText}`);
            }
            
            // For other errors (500, etc), throw immediately
            const errorText = await response.text().catch(() => '');
            logger.error({ status: response.status, statusText: response.statusText, errorText, domain, url }, 'RapidAPI error');
            throw new Error(`Semrush RapidAPI error: ${response.status} ${response.statusText}. ${errorText.substring(0, 200)}`);
          } catch (err: any) {
            if (err.message && err.message.includes('Endpoint not found')) {
              lastError = err;
              response = null; // Reset for next iteration
              continue;
            }
            throw err;
          }
        }
        
        if (!response || !response.ok) {
          if (lastError) {
            throw lastError;
          }
          throw new Error('All RapidAPI endpoints failed');
        }

        // At this point, response is guaranteed to be non-null and ok
        // TypeScript needs explicit check to narrow the type
        if (!response) {
          throw new Error('Response is null after endpoint attempts');
        }
        
        const responseText = await response.text();
        
        // RapidAPI Semrush8 returns JSON format based on documentation
        // Response fields: srDomain, srRank, srKeywords, srTraffic, srCosts, srUrlLinks, srHostLinks, srDomainLinks
        try {
          const jsonData = JSON.parse(responseText);
          // If JSON, try to extract data
          if (jsonData && typeof jsonData === 'object') {
            // Handle RapidAPI Semrush8 response format
            const result: any = {};
            
            // RapidAPI format: direct object with sr* fields
            if (jsonData.srDomain || jsonData.srRank !== undefined) {
              result.domain_rank = jsonData.srRank || jsonData.domain_rank || jsonData.domainRank || null;
              result.organic_keywords = jsonData.srKeywords || jsonData.organic_keywords || jsonData.organicKeywords || null;
              result.organic_traffic = jsonData.srTraffic || jsonData.organic_traffic || jsonData.organicTraffic || null;
              result.backlinks_num = jsonData.srDomainLinks || jsonData.srUrlLinks || jsonData.srHostLinks || jsonData.backlinks_num || jsonData.backlinks || jsonData.backlinksNum || null;
              result.domain = jsonData.srDomain || domain;
              result.costs = jsonData.srCosts || null;
            }
            // Alternative: data array format
            else if (jsonData.data && Array.isArray(jsonData.data) && jsonData.data.length > 0) {
              const firstItem = jsonData.data[0];
              result.domain_rank = firstItem.srRank || firstItem.domain_rank || firstItem.domainRank || null;
              result.organic_keywords = firstItem.srKeywords || firstItem.organic_keywords || firstItem.organicKeywords || null;
              result.organic_traffic = firstItem.srTraffic || firstItem.organic_traffic || firstItem.organicTraffic || null;
              result.backlinks_num = firstItem.srDomainLinks || firstItem.srUrlLinks || firstItem.srHostLinks || firstItem.backlinks_num || firstItem.backlinks || firstItem.backlinksNum || null;
              result.domain = firstItem.srDomain || domain;
              result.costs = firstItem.srCosts || null;
            }
            // Standard Semrush format fallback
            else if (jsonData.domain_rank !== undefined || jsonData.domainRank !== undefined) {
              result.domain_rank = jsonData.domain_rank || jsonData.domainRank;
              result.organic_keywords = jsonData.organic_keywords || jsonData.organicKeywords;
              result.organic_traffic = jsonData.organic_traffic || jsonData.organicTraffic;
              result.backlinks_num = jsonData.backlinks_num || jsonData.backlinks || jsonData.backlinksNum;
            }
            
            // If we got valid data, use it
            if (result.domain_rank !== undefined || result.organic_keywords !== undefined || result.backlinks_num !== undefined || result.domain) {
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

              logger.info({ domain, result }, 'Semrush data fetched and cached (RapidAPI JSON)');
              return result;
            }
          }
        } catch (jsonError) {
          // Not JSON, try CSV parsing below
          logger.debug({ domain, error: jsonError, responsePreview: responseText.substring(0, 200) }, 'Failed to parse JSON, trying CSV');
          data = responseText;
        }
      } else {
        // Standard Semrush API format
        const url = `${this.baseUrl}/?key=${this.apiKey}&type=domain_ranks&domain=${domain}&export_columns=domain_rank,organic_keywords,organic_traffic,backlinks_num`;
        
        response = await fetch(url);
        
        if (!response.ok) {
          throw new Error(`Semrush API error: ${response.statusText}`);
        }

        data = await response.text();
      }

      // Parse CSV-like response (works for both standard and RapidAPI)
      if (!data) {
        throw new Error('Empty response from Semrush API');
      }
      
      const lines = data.trim().split('\n');
      if (lines.length < 2) {
        throw new Error('Invalid Semrush API response');
      }

      const headers = lines[0].split(';').map((h: string) => h.trim());
      const values = lines[1].split(';').map((v: string) => v.trim());
      
      const result: any = {};
      headers.forEach((header: string, index: number) => {
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

