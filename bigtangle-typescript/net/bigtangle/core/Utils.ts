/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
/*
 * Copyright 2011 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
import { Sha256Hash } from './Sha256Hash';
import { Base58 } from './utils/Base58';
import { VarInt } from './VarInt';
import { VerificationException } from './exception/VerificationException';
import { AddressFormatException } from './exception/AddressFormatException';
import crypto from 'crypto';

/**
 * A collection of various utility methods that are helpful for working with the
 * Bitcoin protocol.
 */
export class Utils {
  /** The string that prefixes all text messages signed using Bitcoin keys. */
  static BITCOIN_SIGNED_MESSAGE_HEADER = "Bitcoin Signed Message:\n";
  static BITCOIN_SIGNED_MESSAGE_HEADER_BYTES = Buffer.from(Utils.BITCOIN_SIGNED_MESSAGE_HEADER, 'utf-8');

  private static SPACE_JOINER = ' ';

  private static mockSleepQueue: BlockingQueue<boolean> | null = null;
  private static mockTime: Date | null = null;

  /**
   * @param b        the integer to format into a byte array
   * @param numBytes the desired size of the resulting byte array
   * @return numBytes byte long array.
   */
  static bigIntegerToBytes(b: bigint | null, numBytes: number): Buffer | null {
    if (b === null) return null;
    
    const bytes = Buffer.alloc(numBytes);
    const biBytes = this.toBytes(b);
    const start = (biBytes.length === numBytes + 1) ? 1 : 0;
    const length = Math.min(biBytes.length, numBytes);
    biBytes.copy(bytes, numBytes - length, start, start + length);
    return bytes;
  }

  static uint32ToByteArrayBE(val: number, out: Buffer, offset: number): void {
    out.writeUInt32BE(val, offset);
  }

  static uint32ToByteArrayLE(val: number, out: Buffer, offset: number): void {
    out.writeUInt32LE(val, offset);
  }

  static uint64ToByteArrayLE(val: bigint, out: Buffer, offset: number): void {
    out.writeBigUInt64LE(val, offset);
  }

  static bytesToByteStream(b: Buffer, stream: NodeJS.WritableStream): void {
    stream.write(b);
  }

  static uint32ToByteStreamLE(val: number, stream: NodeJS.WritableStream): void {
    const buf = Buffer.alloc(4);
    buf.writeUInt32LE(val);
    stream.write(buf);
  }

  static int64ToByteStreamLE(val: bigint, stream: NodeJS.WritableStream): void {
    const buf = Buffer.alloc(8);
    buf.writeBigInt64LE(val);
    stream.write(buf);
  }

  static uint64ToByteStreamLE(val: bigint, stream: NodeJS.WritableStream): void {
    const buf = Buffer.alloc(8);
    buf.writeBigUInt64LE(val);
    stream.write(buf);
  }

  static isLessThanUnsigned(n1: bigint, n2: bigint): boolean {
    return n1 < n2;
  }

  static isLessThanOrEqualToUnsigned(n1: bigint, n2: bigint): boolean {
    return n1 <= n2;
  }

  /**
   * Returns a copy of the given byte array in reverse order.
   */
  static reverseBytes(bytes: Buffer): Buffer {
    return Buffer.from(bytes).reverse();
  }

  /**
   * Parse 4 bytes from the byte array (starting at the offset) as unsigned 32-bit
   * integer in little endian format.
   */
  static readUint32(bytes: Buffer, offset: number): number {
    return bytes.readUInt32LE(offset);
  }

  /**
   * Parse 8 bytes from the byte array (starting at the offset) as signed 64-bit
   * integer in little endian format.
   */
  static readInt64(bytes: Buffer, offset: number): bigint {
    return bytes.readBigInt64LE(offset);
  }

  /**
   * Parse 4 bytes from the byte array (starting at the offset) as unsigned 32-bit
   * integer in big endian format.
   */
  static readUint32BE(bytes: Buffer, offset: number): number {
    return bytes.readUInt32BE(offset);
  }

  static readNBytesString(dis: any): string | null {
    throw new Error("DataInputStream not available in TypeScript");
  }

  static readNBytes(dis: any): Buffer | null {
    throw new Error("DataInputStream not available in TypeScript");
  }

  static writeNBytesString(dos: any, message: string | null): void {
    throw new Error("DataOutputStream not available in TypeScript");
  }

  static writeLong(dos: any, message: bigint | null): void {
    throw new Error("DataOutputStream not available in TypeScript");
  }

  static readLong(dis: any): bigint | null {
    throw new Error("DataInputStream not available in TypeScript");
  }

  static writeNBytes(dos: any, message: Buffer | null): void {
    throw new Error("DataOutputStream not available in TypeScript");
  }

  /**
   * Parse 2 bytes from the byte array (starting at the offset) as unsigned 16-bit
   * integer in big endian format.
   */
  static readUint16BE(bytes: Buffer, offset: number): number {
    return bytes.readUInt16BE(offset);
  }

  /**
   * Calculates RIPEMD160(SHA256(input)). This is used in Address calculations.
   */
  static sha256hash160(input: Buffer): Buffer {
    const sha256 = Sha256Hash.hash(input);
    const ripemd160 = crypto.createHash('ripemd160');
    ripemd160.update(sha256);
    return ripemd160.digest();
  }

  /**
   * MPI encoded numbers are produced by the OpenSSL BN_bn2mpi function.
   */
  static decodeMPI(mpi: Buffer, hasLength: boolean): bigint {
    let buf: Buffer;
    if (hasLength) {
      const length = this.readUint32BE(mpi, 0);
      buf = Buffer.alloc(length);
      mpi.copy(buf, 0, 4, 4 + length);
    } else {
      buf = mpi;
    }
    
    if (buf.length === 0) return BigInt(0);
    
    const isNegative = (buf[0] & 0x80) === 0x80;
    if (isNegative) buf[0] &= 0x7f;
    
    let result = BigInt('0x' + buf.toString('hex'));
    return isNegative ? -result : result;
  }

  /**
   * MPI encoded numbers are produced by the OpenSSL BN_bn2mpi function.
   */
  static encodeMPI(value: bigint, includeLength: boolean): Buffer {
    if (value === BigInt(0)) {
      return includeLength ? Buffer.alloc(4) : Buffer.alloc(0);
    }

    const isNegative = value < 0;
    if (isNegative) value = -value;

    let hex = value.toString(16);
    if (hex.length % 2 !== 0) hex = '0' + hex;
    let array = Buffer.from(hex, 'hex');

    if (includeLength) {
      const result = Buffer.alloc(array.length + 4);
      result.writeUInt32BE(array.length, 0);
      array.copy(result, 4);
      if (isNegative) result[4] |= 0x80;
      return result;
    } else {
      if (isNegative) array[0] |= 0x80;
      return array;
    }
  }

  static decodeCompactBits(compact: number): bigint {
    const size = (compact >> 24) & 0xFF;
    const bytes = Buffer.alloc(4 + size);
    bytes.writeUInt32BE(size, 0);
    
    if (size >= 1) bytes[4] = (compact >> 16) & 0xFF;
    if (size >= 2) bytes[5] = (compact >> 8) & 0xFF;
    if (size >= 3) bytes[6] = compact & 0xFF;
    
    return this.decodeMPI(bytes, true);
  }

  static encodeCompactBits(value: bigint): number {
    let result: number;
    const array = this.toBytes(value);
    let size = array.length; // Changed from const to let
    
    if (size <= 3) {
      result = Number(value) << (8 * (3 - size));
    } else {
      result = Number(value >> BigInt(8 * (size - 3)));
    }
    
    if ((result & 0x00800000) !== 0) {
      result >>= 8;
      size++;
    }
    
    result |= size << 24;
    result |= value < 0 ? 0x00800000 : 0;
    return result;
  }

  static rollMockClock(seconds: number): Date {
    return this.rollMockClockMillis(seconds * 1000);
  }

  static rollMockClockMillis(millis: number): Date {
    if (!Utils.mockTime) throw new Error("Use setMockClock() first");
    Utils.mockTime = new Date(Utils.mockTime.getTime() + millis);
    return Utils.mockTime;
  }

  static unsetMockClock(): void {
    Utils.mockTime = null;
  }

  static setMockClock(): void {
    Utils.mockTime = new Date();
  }

  static setMockClockSeconds(mockClockSeconds: number): void {
    Utils.mockTime = new Date(mockClockSeconds * 1000);
  }

  static now(): Date {
    return Utils.mockTime || new Date();
  }

  static currentTimeMillis(): number {
    return Utils.mockTime ? Utils.mockTime.getTime() : Date.now();
  }

  static currentTimeSeconds(): number {
    return Math.floor(this.currentTimeMillis() / 1000);
  }

  static dateTimeFormat(dateTime: Date): string {
    return dateTime.toISOString();
  }

  static join<T>(items: Iterable<T>): string {
    return Array.from(items).join(' ');
  }

  static copyOf(inBuf: Buffer, length: number): Buffer {
    const out = Buffer.alloc(length);
    inBuf.copy(out, 0, 0, Math.min(length, inBuf.length));
    return out;
  }

  static appendByte(bytes: Buffer, b: number): Buffer {
    return Buffer.concat([bytes, Buffer.from([b])]);
  }

  static toString(bytes: Buffer, charsetName: string): string {
    return bytes.toString(charsetName as BufferEncoding);
  }

  static toBytes(str: string, charsetName: string = 'utf8'): Buffer {
    return Buffer.from(str, charsetName as BufferEncoding);
  }

  static parseAsHexOrBase58(data: string): Buffer | null {
    try {
      return Buffer.from(data, 'hex');
    } catch {
      try {
        return Base58.decodeChecked(data);
      } catch {
        return null;
      }
    }
  }

  static isWindows(): boolean {
    return process.platform === 'win32';
  }

  static formatMessageForSigning(message: string): Buffer {
    const header = Utils.BITCOIN_SIGNED_MESSAGE_HEADER_BYTES;
    const messageBytes = Buffer.from(message, 'utf8');
    const varInt = new VarInt(messageBytes.length);
    const encodedVarInt = varInt.encode();
    
    // Create a buffer for the entire message
    const result = Buffer.alloc(
      1 + header.length + encodedVarInt.length + messageBytes.length
    );
    
    let offset = 0;
    result[offset++] = header.length;
    header.copy(result, offset);
    offset += header.length;
    Buffer.from(encodedVarInt).copy(result, offset);
    offset += encodedVarInt.length;
    messageBytes.copy(result, offset);
    
    return result;
  }

  private static bitMask = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80];

  static checkBitLE(data: Buffer, index: number): boolean {
    return (data[index >>> 3] & this.bitMask[7 & index]) !== 0;
  }

  static setBitLE(data: Buffer, index: number): void {
    data[index >>> 3] |= this.bitMask[7 & index];
  }

  static sleep(millis: number): void {
    if (!Utils.mockSleepQueue) {
      const start = Date.now();
      while (Date.now() - start < millis) {
        // Busy wait (not ideal but works for mock)
      }
    } else {
      try {
        // Refactored to avoid linting false positive
        Utils.mockSleepQueue.take().then((isMultiPass) => {
          this.rollMockClockMillis(millis);
          if (isMultiPass) Utils.mockSleepQueue?.offer(true);
        });
      } catch (e) {
        // Ignore
      }
    }
  }

  static setMockSleep(enable: boolean): void {
    if (enable) {
      Utils.mockSleepQueue = new BlockingQueue<boolean>();
      Utils.mockTime = new Date();
    } else {
      Utils.mockSleepQueue = null;
    }
  }

  static passMockSleep(): void {
    if (Utils.mockSleepQueue) Utils.mockSleepQueue.offer(false);
  }

  static finishMockSleep(): void {
    if (Utils.mockSleepQueue) Utils.mockSleepQueue.offer(true);
  }

  static isAndroidRuntime(): boolean {
    return process.env.JAVA_RUNTIME === 'Android Runtime';
  }

  static maxOfMostFreq(...items: number[]): number {
    if (items.length === 0) return 0;
    
    const freqMap = new Map<number, number>();
    for (const item of items) {
      freqMap.set(item, (freqMap.get(item) || 0) + 1);
    }
    
    let maxCount = 0;
    let maxItem = 0;
    for (const [item, count] of freqMap) {
      if (count > maxCount || (count === maxCount && item > maxItem)) {
        maxCount = count;
        maxItem = item;
      }
    }
    return maxItem;
  }

  static xor(a: Buffer, b: Buffer): Buffer {
    if (a.length !== b.length) throw new Error("Buffers must be same length");
    const result = Buffer.alloc(a.length);
    for (let i = 0; i < a.length; i++) {
      result[i] = a[i] ^ b[i];
    }
    return result;
  }

  static isBlank(cs: string | null): boolean {
    return !cs || /^\s*$/.test(cs);
  }

  static addAll(array1: Buffer, array2: Buffer): Buffer {
    return Buffer.concat([array1, array2]);
  }

  static toBytes(n: bigint): Buffer {
    let hex = n.toString(16);
    if (hex.length % 2) hex = '0' + hex;
    return Buffer.from(hex, 'hex');
  }

  static findContractValue(t: any, key: string): string | null {
    for (const kv of t.keyvalues) {
      if (kv.key === key) return kv.value;
    }
    return null;
  }
}

// Simple blocking queue implementation for TypeScript
class BlockingQueue<T> {
  private queue: T[] = [];
  private resolvers: ((value: T) => void)[] = [];

  offer(item: T): void {
    if (this.resolvers.length > 0) {
      const resolve = this.resolvers.shift()!;
      resolve(item);
    } else {
      this.queue.push(item);
    }
  }

  take(): Promise<T> {
    return new Promise(resolve => {
      if (this.queue.length > 0) {
        resolve(this.queue.shift()!);
      } else {
        this.resolvers.push(resolve);
      }
    });
  }
}
