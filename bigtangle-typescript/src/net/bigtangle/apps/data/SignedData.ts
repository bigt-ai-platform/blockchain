// TypeScript translation of SignedData.java

export interface SignedData {
    dataClassName?: string;
    serializedData?: string;
    signerpubkey?: Uint8Array;
    signature?: string;
    validtodate?: number;
}

// --- Functions translated from Java ---

export class SignedDataClass {
    dataClassName?: string;
    serializedData?: string;
    signerpubkey?: Uint8Array;
    signature?: string;
    validtodate?: number;

    verify(ecKey: any): boolean {
        // Implement ECKey.fromPublicOnly(signerpubkey).verifyMessage(serializedData, signature)
        return ecKey.verifyMessage(this.serializedData!, this.signature!);
    }

    signMessage(ecKey: any): void {
        this.signature = ecKey.signMessage(this.serializedData!);
    }

    setSerializedData(byteData: Uint8Array): void {
        this.serializedData = Buffer.from(byteData).toString('hex');
    }

    // Stub: encryptToMemo
    encryptToMemo(userkey: any): any /* MemoInfo */ {
        // TODO: Implement ECIES encryption and MemoInfo logic
        throw new Error('encryptToMemo not implemented');
    }

    // Stub: decryptFromMemo
    static decryptFromMemo(userkey: any, memoInfo: any): SignedDataClass {
        // TODO: Implement ECIES decryption and MemoInfo logic
        throw new Error('decryptFromMemo not implemented');
    }

    // Stub: toTokenKeyValues
    toTokenKeyValues(key: any, userkey: any): any /* TokenKeyValues */ {
        // TODO: Implement ECIES encryption and TokenKeyValues logic
        throw new Error('toTokenKeyValues not implemented');
    }

    // signData method
    signData(signkey: any, originalData: Uint8Array, dataClassname: string): void {
        this.setSerializedData(originalData);
        this.signerpubkey = signkey.getPubKey();
        this.dataClassName = dataClassname;
        this.signMessage(signkey);
    }

    // --- Serialization and parsing methods translated from Java ---
    toByteArray(): Uint8Array {
        // NOTE: This is a simplified version. For full compatibility, use a binary serialization library.
        const dataClassName = this.dataClassName ?? '';
        const serializedData = this.serializedData ?? '';
        const signerpubkey = this.signerpubkey ? Array.from(this.signerpubkey) : [];
        const signature = this.signature ?? '';
        const validtodate = this.validtodate ?? null;
        const obj = { dataClassName, serializedData, signerpubkey, signature, validtodate };
        return new TextEncoder().encode(JSON.stringify(obj));
    }

    static parse(buf: Uint8Array): SignedDataClass {
        // NOTE: This is a simplified version. For full compatibility, use a binary serialization library.
        const json = new TextDecoder().decode(buf);
        const obj = JSON.parse(json);
        const sdata = new SignedDataClass();
        sdata.dataClassName = obj.dataClassName;
        sdata.serializedData = obj.serializedData;
        sdata.signerpubkey = obj.signerpubkey ? new Uint8Array(obj.signerpubkey) : undefined;
        sdata.signature = obj.signature;
        sdata.validtodate = obj.validtodate;
        return sdata;
    }

    // Getters and setters
    getSignature(): string | undefined {
        return this.signature;
    }
    setSignature(signature: string): void {
        this.signature = signature;
    }
    getSerializedData(): string | undefined {
        return this.serializedData;
    }
    setSerializedDataString(serializedData: string): void {
        this.serializedData = serializedData;
    }
    getDataClassName(): string | undefined {
        return this.dataClassName;
    }
    setDataClassName(dataClassName: string): void {
        this.dataClassName = dataClassName;
    }
    getSignerpubkey(): Uint8Array | undefined {
        return this.signerpubkey;
    }
    setSignerpubkey(signerpubkey: Uint8Array): void {
        this.signerpubkey = signerpubkey;
    }
    getValidtodate(): number | undefined {
        return this.validtodate;
    }
    setValidtodate(validtodate: number): void {
        this.validtodate = validtodate;
    }
}
