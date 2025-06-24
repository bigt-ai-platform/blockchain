import axios, { AxiosInstance, AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import * as zlib from 'zlib';
import { promisify } from 'util';
import * as https from 'https';
import { Buffer } from 'buffer';
const gzip = promisify(zlib.gzip);
const gunzip = promisify(zlib.gunzip);

export class OkHttp3Util {
  private static logger = console;
  public static timeoutMinute = 45;
  private static instance: AxiosInstance;
  public static pubkey: string | null = null;
  public static signHex: string | null = null;
  public static contentHex: string | null = null;

  private static getAxiosInstance(): AxiosInstance {
    if (!this.instance) {
      // Create custom agent to bypass SSL verification
      const agent = new https.Agent({
        rejectUnauthorized: false
      });

      this.instance = axios.create({
        httpsAgent: agent,
        timeout: this.timeoutMinute * 60 * 1000,
        responseType: 'arraybuffer',
        headers: {
          'Content-Type': 'application/octet-stream; charset=utf-8'
        }
      });

      // Add request interceptor for headers and compression
      this.instance.interceptors.request.use(async (config: InternalAxiosRequestConfig) => {
        // Add authentication headers
        if (this.pubkey) config.headers['pubkey'] = this.pubkey;
        if (this.signHex) config.headers['signHex'] = this.signHex;
        if (this.contentHex) config.headers['contentHex'] = this.contentHex;

        // Compress request body if needed
        if (config.data && !config.headers['Content-Encoding']) {
          config.data = await gzip(config.data as Buffer);
          config.headers['Content-Encoding'] = 'gzip';
        }

        return config;
      });

      // Add response interceptor for decompression
      this.instance.interceptors.response.use(async (response: AxiosResponse) => {
        if (response.headers['content-encoding'] === 'gzip') {
          response.data = await gunzip(response.data as Buffer);
        }
        return response;
      });
    }
    return this.instance;
  }

  public static async post(urls: string[], data: Buffer): Promise<Buffer>;
  public static async post(urls: string[], data: Buffer, index: number): Promise<Buffer>;
  public static async post(urls: string[], data: Buffer, index = 0): Promise<Buffer> {
    if (index < urls.length) {
      try {
        return await this.postSingle(urls[index], data);
      } catch (e) {
        return this.post(urls, data, index + 1);
      }
    } else {
      throw new Error(`All servers failed: ${urls.join(', ')}`);
    }
  }

  public static async postString(urls: string[], data: string): Promise<Buffer>;
  public static async postString(urls: string[], data: string, index: number): Promise<Buffer>;
  public static async postString(urls: string[], data: string, index = 0): Promise<Buffer> {
    if (index < urls.length) {
      try {
        return await this.postAndGetBlock(urls[index], data);
      } catch (e) {
        return this.postString(urls, data, index + 1);
      }
    } else {
      throw new Error(`All servers failed: ${urls.join(', ')}`);
    }
  }

  public static async postSingle(url: string, data: Buffer): Promise<Buffer> {
    this.logger.debug(`POST to ${url}`);
    const response = await this.getAxiosInstance().post(url, data);
    this.checkResponse(response, url);
    return response.data;
  }

  public static async postAndGetBlock(url: string, data: string): Promise<Buffer> {
    const response = await this.postStringSingle(url, data);
    const json = JSON.parse(response.toString());
    const dataHex = json.dataHex;
    return dataHex ? Buffer.from(dataHex, 'hex') : Buffer.alloc(0);
  }

  public static async postStringSingle(url: string, data: string): Promise<Buffer> {
    this.logger.debug(`POST to ${url}`);
    const response = await this.getAxiosInstance().post(url, data);
    this.checkResponse(response, url);
    return response.data;
  }

  private static checkResponse(response: AxiosResponse, url: string): void {
    if (response.status < 200 || response.status >= 300) {
      throw new Error(`Server: ${url} HTTP Error: ${response.status}`);
    }

    if (!response.data || response.data.length === 0) return;

    try {
      const json = JSON.parse(response.data.toString());
      if (json.errorcode) {
        const error = json.errorcode;
        const message = json.message || `Server Error: ${error}`;
        throw new Error(`Server: ${url} Server Error: ${message}`);
      }
    } catch (e) {
      // Not a JSON response, continue without error check
    }
  }

  public static async postWithHeader(url: string, data: Buffer, header: string): Promise<Buffer> {
    this.logger.debug(`POST to ${url} with header`);
    const config: AxiosRequestConfig = {
      headers: {
        'accessToken': header
      }
    };
    const response = await this.getAxiosInstance().post(url, data, config);
    this.checkResponse(response, url);
    return response.data;
  }

  public static async postStringWithHeader(url: string, data: string, header: string): Promise<Buffer> {
    this.logger.debug(`POST to ${url} with header`);
    const config: AxiosRequestConfig = {
      headers: {
        'accessToken': header
      }
    };
    const response = await this.getAxiosInstance().post(url, data, config);
    this.checkResponse(response, url);
    return response.data;
  }

  public static async postAndGetBlockWithHeader(url: string, data: string, header: string): Promise<Buffer> {
    const response = await this.postStringWithHeader(url, data, header);
    if (!response || response.length === 0) return Buffer.alloc(0);
    
    const json = JSON.parse(response.toString());
    const dataHex = json.dataHex;
    return dataHex ? Buffer.from(dataHex, 'hex') : Buffer.alloc(0);
  }
}
