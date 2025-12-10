import { prisma } from '../server';
import { config } from '../config';
import { logger } from '../utils/logger';
import { semrushService } from './semrush.service';

export interface DomainCheckResult {
  domain: string;
  isValid: boolean;
  exists: boolean;
  metrics?: {
    domainRank?: number;
    organicKeywords?: number;
    organicTraffic?: number;
    backlinks?: number;
  };
  source?: 'semrush' | 'ahrefs' | 'cache';
  error?: string;
}

export class DomainCheckerService {
  private readonly ahrefsApiKey?: string;
  private readonly ahrefsBaseUrl = 'https://api.ahrefs.com/v3';

  constructor() {
    this.ahrefsApiKey = process.env.AHREFS_API_KEY;
    if (!this.ahrefsApiKey && !config.semrush.apiKey) {
      logger.warn('Neither Ahrefs nor Semrush API key configured');
    }
  }

  /**
   * Check domain via Semrush or Ahrefs API
   * Returns null if domain doesn't exist or is invalid
   */
  async checkDomain(domain: string): Promise<DomainCheckResult | null> {
    if (!domain || !domain.trim()) {
      return null;
    }

    // Normalize domain
    const normalizedDomain = this.normalizeDomain(domain);
    if (!normalizedDomain) {
      return null;
    }

    // Check cache first
    const cached = await this.getCachedCheck(normalizedDomain);
    if (cached) {
      return cached;
    }

    // Try Semrush first, then Ahrefs
    let result: DomainCheckResult | null = null;

    if (config.semrush.apiKey) {
      try {
        result = await this.checkViaSemrush(normalizedDomain);
      } catch (error) {
        logger.warn({ error, domain: normalizedDomain }, 'Semrush check failed, trying Ahrefs');
      }
    }

    if (!result && this.ahrefsApiKey) {
      try {
        result = await this.checkViaAhrefs(normalizedDomain);
      } catch (error) {
        logger.warn({ error, domain: normalizedDomain }, 'Ahrefs check failed');
      }
    }

    // Cache the result
    if (result) {
      await this.cacheCheck(result);
    }

    return result;
  }

  /**
   * Check multiple domains in batch
   */
  async checkDomains(domains: string[]): Promise<Map<string, DomainCheckResult | null>> {
    const results = new Map<string, DomainCheckResult | null>();
    
    // Normalize and deduplicate domains
    const normalizedDomains = domains
      .map(d => this.normalizeDomain(d))
      .filter((d): d is string => d !== null);
    const uniqueDomains = [...new Set(normalizedDomains)];

    if (uniqueDomains.length === 0) {
      return results;
    }

    // Check cache first
    const cachedResults = await Promise.all(
      uniqueDomains.map(async (domain) => {
        const cached = await this.getCachedCheck(domain);
        if (cached) {
          results.set(domain, cached);
        }
        return { domain, cached: !!cached };
      })
    );

    const domainsToCheck = cachedResults
      .filter(({ cached }) => !cached)
      .map(({ domain }) => domain);

    // Check remaining domains
    for (const domain of domainsToCheck) {
      try {
        const result = await this.checkDomain(domain);
        results.set(domain, result);
        // Add small delay to avoid rate limiting
        await new Promise(resolve => setTimeout(resolve, 100));
      } catch (error) {
        logger.error({ error, domain }, 'Error checking domain');
        results.set(domain, null);
      }
    }

    return results;
  }

  /**
   * Check if domain should be saved (exists and has metrics)
   */
  async shouldSaveDomain(domain: string): Promise<boolean> {
    const result = await this.checkDomain(domain);
    if (!result) {
      return false;
    }

    // Domain is valid if it exists and has at least some metrics
    return result.exists && (result.metrics?.domainRank !== undefined || 
                             result.metrics?.organicKeywords !== undefined ||
                             result.metrics?.backlinks !== undefined);
  }

  private async checkViaSemrush(domain: string): Promise<DomainCheckResult | null> {
    try {
      const semrushData = await semrushService.checkDomain(domain);
      
      if (!semrushData || !semrushData.domain_rank) {
        return {
          domain,
          isValid: false,
          exists: false,
          source: 'semrush',
        };
      }

      return {
        domain,
        isValid: true,
        exists: true,
        metrics: {
          domainRank: semrushData.domain_rank as number,
          organicKeywords: semrushData.organic_keywords as number,
          organicTraffic: semrushData.organic_traffic as number,
          backlinks: semrushData.backlinks_num as number,
        },
        source: 'semrush',
      };
    } catch (error: any) {
      logger.error({ error, domain }, 'Semrush check error');
      return {
        domain,
        isValid: false,
        exists: false,
        source: 'semrush',
        error: error.message,
      };
    }
  }

  private async checkViaAhrefs(domain: string): Promise<DomainCheckResult | null> {
    if (!this.ahrefsApiKey) {
      return null;
    }

    try {
      const url = `${this.ahrefsBaseUrl}/site-explorer/site-metrics?target=${encodeURIComponent(domain)}&output=json`;
      
      const response = await fetch(url, {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${this.ahrefsApiKey}`,
          'Accept': 'application/json',
        },
      });

      if (!response.ok) {
        if (response.status === 404) {
          return {
            domain,
            isValid: false,
            exists: false,
            source: 'ahrefs',
          };
        }
        throw new Error(`Ahrefs API error: ${response.statusText}`);
      }

      const data = await response.json() as any;
      
      if (!data || !data.metrics) {
        return {
          domain,
          isValid: false,
          exists: false,
          source: 'ahrefs',
        };
      }

      const metrics = data.metrics as {
        domain_rating?: number;
        organic_keywords?: number;
        organic_traffic?: number;
        backlinks?: number;
      };
      return {
        domain,
        isValid: true,
        exists: true,
        metrics: {
          domainRank: metrics.domain_rating,
          organicKeywords: metrics.organic_keywords,
          organicTraffic: metrics.organic_traffic,
          backlinks: metrics.backlinks,
        },
        source: 'ahrefs',
      };
    } catch (error: any) {
      logger.error({ error, domain }, 'Ahrefs check error');
      return {
        domain,
        isValid: false,
        exists: false,
        source: 'ahrefs',
        error: error.message,
      };
    }
  }

  private normalizeDomain(domain: string): string | null {
    if (!domain) return null;

    let normalized = domain.trim().toLowerCase();
    
    // Remove protocol
    normalized = normalized.replace(/^https?:\/\//, '');
    
    // Remove path
    normalized = normalized.split('/')[0];
    
    // Remove port
    normalized = normalized.split(':')[0];
    
    // Remove www prefix for comparison
    normalized = normalized.replace(/^www\./, '');
    
    // Basic validation
    if (!/^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*\.[a-z]{2,}$/i.test(normalized)) {
      return null;
    }

    return normalized;
  }

  private async getCachedCheck(domain: string): Promise<DomainCheckResult | null> {
    try {
      const semrushData = await prisma.semrushData.findUnique({
        where: { domain },
      });

      if (semrushData && semrushData.expiresAt > new Date()) {
        const data = semrushData.dataJson as any;
        if (data && data.domain_rank) {
          return {
            domain,
            isValid: true,
            exists: true,
            metrics: {
              domainRank: data.domain_rank as number,
              organicKeywords: data.organic_keywords as number,
              organicTraffic: data.organic_traffic as number,
              backlinks: data.backlinks_num as number,
            },
            source: 'cache',
          };
        }
      }
    } catch (error) {
      // Ignore cache errors
    }

    return null;
  }

  private async cacheCheck(result: DomainCheckResult): Promise<void> {
    if (result.source === 'cache' || !result.metrics) {
      return;
    }

    try {
      // Cache is already handled by SemrushService
      // This is just for future Ahrefs caching if needed
    } catch (error) {
      // Ignore cache errors
    }
  }
}

export const domainCheckerService = new DomainCheckerService();

