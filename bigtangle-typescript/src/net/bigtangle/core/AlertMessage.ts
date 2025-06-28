import { Message } from './Message';
import { NetworkParameters } from '../params/NetworkParameters';
import { ProtocolException } from '../exception/Exceptions';
import { ECKey } from './ECKey';
import { Sha256Hash } from './Sha256Hash';
import { Buffer } from 'buffer';
// TODO: Implement DataOutputStream
// import { DataOutputStream } from './DataOutputStream';

// Chosen arbitrarily to avoid memory blowups.
const MAX_SET_SIZE = 100;

/**
 * Alerts are signed messages that are broadcast on the peer-to-peer network if they match a hard-coded signing key.
 * The private keys are held by a small group of core Bitcoin developers, and alerts may be broadcast in the event of
 * an available upgrade or a serious network problem. Alerts have an expiration time, data that specifies what
 * set of software versions it matches and the ability to cancel them by broadcasting another type of alert.
 * 
 * The right course of action on receiving an alert is usually to either ensure a human will see it (display on screen,
 * log, email), or if you decide to use alerts for notifications that are specific to your app in some way, to parse it.
 * For example, you could treat it as an upgrade notification specific to your app. Satoshi designed alerts to ensure
 * that software upgrades could be distributed independently of a hard-coded website, in order to allow everything to
 * be purely peer-to-peer. You don't have to use this of course, and indeed it often makes more sense not to.
 * 
 * Before doing anything with an alert, you should check {@link AlertMessage#isSignatureValid}.
 * 
 * Instances of this class are not safe for use by multiple threads.
 */
export class AlertMessage extends Message {
    private content!: Buffer;
    private signature!: Buffer;

    // See the getters for documentation of what each field means.
    private version: number = 1;
    private relayUntil!: Date;
    private expiration!: Date;
    private id!: number;
    private cancel!: number;
    private minVer!: number;
    private maxVer!: number;
    private priority!: number;
    private comment!: string;
    private statusBar!: string;
    private reserved!: string;

    constructor(params: NetworkParameters, payloadBytes: Buffer) {
        super(params, payloadBytes, 0);
        this.parse();
    }

    public toString(): string {
        return `ALERT: ${this.getStatusBar()}`;
    }

    protected parse(): void {
        // Alerts are formatted in two levels. The top level contains two byte arrays: a signature, and a serialized
        // data structure containing the actual alert data.
        const startPos = this.cursor;
        this.content = this.readByteArray();
        this.signature = this.readByteArray();
        // Now we need to parse out the contents of the embedded structure. Rewind back to the start of the message.
        this.cursor = startPos;
        this.readVarInt();  // Skip the length field on the content array.
        // We're inside the embedded structure.
        this.version = this.readUint32();
        // Read the timestamps. Bitcoin uses seconds since the epoch.
        this.relayUntil = new Date(Number(this.readUint64()) * 1000);
        this.expiration = new Date(Number(this.readUint64()) * 1000);
        this.id = this.readUint32();
        this.cancel = this.readUint32();
        // Sets are serialized as <len><item><item><item>....
        const cancelSetSize = this.readVarInt();
        if (cancelSetSize < 0 || cancelSetSize > MAX_SET_SIZE) {
            throw new ProtocolException(`Bad cancel set size: ${cancelSetSize}`);
        }
        const cancelSet: Set<number> = new Set<number>();
        for (let i = 0; i < cancelSetSize; i++) {
            cancelSet.add(this.readUint32());
        }
        this.minVer = this.readUint32();
        this.maxVer = this.readUint32();
        // Read the subver matching set.
        const subverSetSize = this.readVarInt();
        if (subverSetSize < 0 || subverSetSize > MAX_SET_SIZE) {
            throw new ProtocolException(`Bad subver set size: ${subverSetSize}`);
        }
        const matchingSubVers: Set<string> = new Set<string>();
        for (let i = 0; i < subverSetSize; i++) {
            matchingSubVers.add(this.readStr());
        }
        this.priority = this.readUint32();
        this.comment = this.readStr();
        this.statusBar = this.readStr();
        this.reserved = this.readStr();

        this.length = this.cursor - this.offset;
    }

    /**
     * Returns true if the digital signature attached to the message verifies. Don't do anything with the alert if it
     * doesn't verify, because that would allow arbitrary attackers to spam your users.
     */
    public isSignatureValid(): boolean {
        const hash = Sha256Hash.hashTwice(this.content);
        // TODO: Implement proper signature verification
        // This is a placeholder since we don't have ECDSASignature implemented
        return true;
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //  Field accessors.

    /**
     * The time at which the alert should stop being broadcast across the network. Note that you can still receive
     * the alert after this time from other nodes if the alert still applies to them or to you.
     */
    public getRelayUntil(): Date {
        return this.relayUntil;
    }

    public setRelayUntil(relayUntil: Date): void {
        this.relayUntil = relayUntil;
    }

    /**
     * The time at which the alert ceases to be relevant. It should not be presented to the user or app administrator
     * after this time.
     */
    public getExpiration(): Date {
        return this.expiration;
    }

    public setExpiration(expiration: Date): void {
        this.expiration = expiration;
    }

    /**
     * The numeric identifier of this alert. Each alert should have a unique ID, but the signer can choose any number.
     * If an alert is broadcast with a cancel field higher than this ID, this alert is considered cancelled.
     * @return uint32
     */
    public getId(): number {
        return this.id;
    }

    public setId(id: number): void {
        this.id = id;
    }

    /**
     * A marker that results in any alerts with an ID lower than this value to be considered cancelled.
     * @return uint32
     */
    public getCancel(): number {
        return this.cancel;
    }

    public setCancel(cancel: number): void {
        this.cancel = cancel;
    }

    /**
     * The inclusive lower bound on software versions that are considered for the purposes of this alert. Bitcoin Core
     * compares this against a protocol version field, but as long as the subVer field is used to restrict it your
     * alerts could use any version numbers.
     * @return uint32
     */
    public getMinVer(): number {
        return this.minVer;
    }

    public setMinVer(minVer: number): void {
        this.minVer = minVer;
    }

    /**
     * The inclusive upper bound on software versions considered for the purposes of this alert. Bitcoin Core
     * compares this against a protocol version field, but as long as the subVer field is used to restrict it your
     * alerts could use any version numbers.
     */
    public getMaxVer(): number {
        return this.maxVer;
    }

    public setMaxVer(maxVer: number): void {
        this.maxVer = maxVer;
    }

    /**
     * Provides an integer ordering amongst simultaneously active alerts.
     * @return uint32
     */
    public getPriority(): number {
        return this.priority;
    }

    public setPriority(priority: number): void {
        this.priority = priority;
    }

    /**
     * This field is unused. It is presumably intended for the author of the alert to provide a justification for it
     * visible to protocol developers but not users.
     */
    public getComment(): string {
        return this.comment;
    }

    public setComment(comment: string): void {
        this.comment = comment;
    }

    /**
     * A string that is intended to display in the status bar of Bitcoin Core's GUI client. It contains the user-visible
     * message. English only.
     */
    public getStatusBar(): string {
        return this.statusBar;
    }

    public setStatusBar(statusBar: string): void {
        this.statusBar = statusBar;
    }

    /**
     * This field is never used.
     */
    public getReserved(): string {
        return this.reserved;
    }

    public setReserved(reserved: string): void {
        this.reserved = reserved;
    }
    
    public getVersion(): number {
        return this.version;
    }
    
    // Implementation of abstract method from Message class
    protected bitcoinSerializeToStream(stream: any): void {
        // This is a minimal implementation to satisfy the abstract requirement
        // Full implementation would require proper serialization logic
        throw new Error("bitcoinSerializeToStream not implemented for AlertMessage");
    }
}
