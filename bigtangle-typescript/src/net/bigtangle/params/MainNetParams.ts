import { NetworkParameters } from '../core/NetworkParameters';

export class MainNetParams extends NetworkParameters {
    constructor() {
        super();
        this.id = NetworkParameters.ID_MAINNET;
        this.addressHeader = 0;
        this.p2shHeader = 5;
        this.dumpedPrivateKeyHeader = 128;
        this.acceptableAddressCodes = [this.addressHeader, this.p2shHeader];
    }

    public getAddressHeader(): number {
        return this.addressHeader;
    }

    public getP2SHHeader(): number {
        return this.p2shHeader;
    }

    public getDumpedPrivateKeyHeader(): number {
        return this.dumpedPrivateKeyHeader;
    }

    public getAcceptableAddressCodes(): number[] {
        return this.acceptableAddressCodes;
    }

    public static get(): MainNetParams {
        return new MainNetParams();
    }
}
