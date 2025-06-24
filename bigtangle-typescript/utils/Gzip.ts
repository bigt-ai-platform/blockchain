import * as zlib from 'zlib';
import { promisify } from 'util';

const gunzip = promisify(zlib.gunzip);
const gzip = promisify(zlib.gzip);

export class Gzip {
  private static readonly EMPTY_BYTE_ARRAY = new Uint8Array(0);
  private static readonly BUFFER_SIZE = 8192;

  public static async decompressOut(contentBytes: Uint8Array): Promise<Uint8Array> {
    if (contentBytes.length === 0) {
      return this.EMPTY_BYTE_ARRAY;
    }
    return gunzip(contentBytes);
  }

  public static async compress(data: Uint8Array): Promise<Uint8Array> {
    return gzip(data);
  }
}
