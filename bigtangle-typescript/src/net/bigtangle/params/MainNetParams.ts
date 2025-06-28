// MainNetParams: Parameters for the main production network
// Adapted from Java: net.bigtangle.params.MainNetParams

import { NetworkParameters } from './NetworkParameters';

export class MainNetParams extends NetworkParameters {
    private static instance: MainNetParams | undefined;

    private constructor() {
        super();
        this.maxTarget = BigInt('578960377169117509212217050695880916496095398817113098493422368414323410');
        this.maxTargetReward = BigInt('5789603771691175092122170506958809164960953988171130984934223684143236');
        this.dumpedPrivateKeyHeader = 128;
        this.addressHeader = 0;
        this.p2shHeader = 5;
        this.acceptableAddressCodes = [this.addressHeader, this.p2shHeader];
        this.packetMagic = 0xf9beb4d9;
        this.bip32HeaderPub = 0x0488B21E;
        this.bip32HeaderPriv = 0x0488ADE4;
        this.genesisPub = '03d6053241c5abca6621c238922e7473977320ef310be0a8538cc2df7ee5a0187c';
        this.permissionDomainname = [
            '0222c35110844bf00afd9b7f08788d79ef6edc0dce19be6182b44e07501e637a58'
        ];
        this.id = NetworkParameters.ID_MAINNET;
        this.spendableCoinbaseDepth = 100;
        this.dnsSeeds = [];
    }

    static get(): MainNetParams {
        if (!MainNetParams.instance) {
            MainNetParams.instance = new MainNetParams();
        }
        return MainNetParams.instance;
    }

    serverSeeds(): string[] {
        return ['https://81.169.156.203:8089/'];
    }
}
