// TestParams: Parameters for the test network
// Adapted from Java: net.bigtangle.params.TestParams

import { NetworkParameters } from './NetworkParameters';

export class TestParams extends NetworkParameters {
    private static instance: TestParams | undefined;

    private constructor() {
        super();
        this.id = NetworkParameters.ID_UNITTESTNET;
        this.maxTarget = BigInt('578960377169117509212217050695880916496095398817113098493422368414323410000');
        this.maxTargetReward = this.maxTarget - BigInt(100);
        this.dumpedPrivateKeyHeader = 128;
        this.addressHeader = 0;
        this.p2shHeader = 5;
        this.acceptableAddressCodes = [this.addressHeader, this.p2shHeader];
        this.packetMagic = 0xf9beb4d9;
        this.bip32HeaderPub = 0x0488B21E;
        this.bip32HeaderPriv = 0x0488ADE4;
        this.genesisPub = '02721b5eb0282e4bc86aab3380e2bba31d935cba386741c15447973432c61bc975';
        this.permissionDomainname = [this.genesisPub];
        this.spendableCoinbaseDepth = 100;
        this.dnsSeeds = [];
    }

    static get(): TestParams {
        if (!TestParams.instance) {
            TestParams.instance = new TestParams();
        }
        return TestParams.instance;
    }

    serverSeeds(): string[] {
        return [];
    }
}
