export class ScriptBuilder {
    build(): { getProgram(): Buffer } {
        return {
            getProgram: () => Buffer.alloc(0)
        };
    }

    createOutputScript(pubKey: Buffer): { getProgram(): Buffer } {
        return {
            getProgram: () => Buffer.alloc(0)
        };
    }

    createMultiSigOutputScript(threshold: number, keys: any[]): { getProgram(): Buffer } {
        return {
            getProgram: () => Buffer.alloc(0)
        };
    }
}
