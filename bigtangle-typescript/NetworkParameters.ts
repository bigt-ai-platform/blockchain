export abstract class NetworkParameters {
    public static ID_MAINNET: string = "Mainnet";
    public static ID_UNITTESTNET: string = "Test";

    protected addressHeader!: number;
    protected p2shHeader!: number;
    protected dumpedPrivateKeyHeader!: number;
    protected acceptableAddressCodes!: number[];
    protected id!: string;
    protected genesisPub!: string;

    constructor() {
        // Initialize in child classes
    }

    public abstract getAddressHeader(): number;
    public abstract getP2SHHeader(): number;
    public getDumpedPrivateKeyHeader(): number {
        return this.dumpedPrivateKeyHeader;
    }
    public abstract getAcceptableAddressCodes(): number[];

    public getId(): string {
        return this.id;
    }

    public static fromID(id: string): NetworkParameters | null {
        if (id === NetworkParameters.ID_MAINNET) {
            // Return mainnet params once implemented
            return null;
        } else if (id === NetworkParameters.ID_UNITTESTNET) {
            // Return testnet params once implemented
            return null;
        } else {
            return null;
        }
    }

    public equals(o: any): boolean {
        if (this === o) return true;
        if (!(o instanceof NetworkParameters)) return false;
        return this.getId() === o.getId();
    }

    public hashCode(): number {
        return this.getId().charCodeAt(0); // Simplified for now
    }
}
