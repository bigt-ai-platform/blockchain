import { Message } from './Message';
import { NetworkParameters } from './NetworkParameters';
import { MessageSerializer } from './MessageSerializer';
import { Buffer } from 'buffer';

/**
 * <p>Represents a Message type that can be contained within another Message.  ChildMessages that have a cached
 * backing byte array need to invalidate their parent's caches as well as their own if they are modified.</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
export abstract class ChildMessage extends Message {

    public parent: Message | null = null;

    constructor(params: NetworkParameters, payload?: Buffer, offset?: number, serializer?: MessageSerializer, length?: number) {
        super(params, payload, offset, serializer, length);
    }

    public setParent(parent: Message | null): void {
        if (this.parent !== null && this.parent !== parent && parent !== null) {
            // After old parent is unlinked it won't be able to receive notice if this ChildMessage
            // changes internally.  To be safe we invalidate the parent cache to ensure it rebuilds
            // manually on serialization.
            this.parent.unCache();
        }
        this.parent = parent;
    }

    public unCache(): void {
        super.unCache();
        if (this.parent !== null) {
            this.parent.unCache();
        }
    }
    
    public adjustLength(adjustment: number): void;
    public adjustLength(newArraySize: number, adjustment: number): void;
    public adjustLength(...args: any[]): void {
        if (args.length === 1) {
            super.adjustLength(0, args[0]);
        } else if (args.length === 2) {
            super.adjustLength(args[0], args[1]);
        }
        if (this.parent !== null) {
            if (args.length === 1) {
                (this.parent as any).adjustLength(0, args[0]);
            } else if (args.length === 2) {
                (this.parent as any).adjustLength(args[0], args[1]);
            }
        }
    }
}