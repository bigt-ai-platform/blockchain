import { NetworkParameters } from './NetworkParameters';

export class TestParams extends NetworkParameters {
    constructor() {
        super();
    }

    private static instance: TestParams;

    public static get(): TestParams {
        if (!TestParams.instance) {
            TestParams.instance = new TestParams();
        }
        return TestParams.instance;
    }

    getAddressHeader(): number {
        return 111;
    }

    getP2SHHeader(): number {
        return 196;
    }

    getAcceptableAddressCodes(): number[] {
        return [this.getAddressHeader(), this.getP2SHHeader()];
    }
}
