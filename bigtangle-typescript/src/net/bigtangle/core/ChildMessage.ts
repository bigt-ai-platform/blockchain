import { Message } from './Message';

export class ChildMessage extends Message {
    protected parse(payload?: Buffer, offset: number = 0, length?: number): void {
        // Placeholder implementation
    }
    
    protected bitcoinSerializeToStream(stream: any): void {
        // Placeholder implementation
    }
}
