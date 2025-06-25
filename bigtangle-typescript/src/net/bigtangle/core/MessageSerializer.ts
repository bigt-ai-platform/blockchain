import { NetworkParameters } from './NetworkParameters';
import { Message } from './Message';

export class MessageSerializer {
    protected params: NetworkParameters;
    protected parseLazy: boolean;

    constructor(params: NetworkParameters, parseLazy: boolean) {
        this.params = params;
        this.parseLazy = parseLazy;
    }

    // Placeholder for deserialize method
    public deserialize(payloadBytes: Uint8Array): Message {
        // This would typically involve reading the command and then delegating to specific message parsers
        throw new Error("Method not implemented.");
    }

    // Placeholder for serialize method
    public serialize(message: Message): Uint8Array {
        // This would typically involve writing the command and then the message payload
        throw new Error("Method not implemented.");
    }

    public writeUint32(stream: any, val: number): void {
        const buffer = new ArrayBuffer(4);
        const view = new DataView(buffer);
        view.setUint32(0, val, true);
        stream.write(new Uint8Array(buffer));
    }
}
