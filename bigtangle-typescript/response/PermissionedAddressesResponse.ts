import { MultiSignAddress } from '../MultiSignAddress';

export class PermissionedAddressesResponse {
    private multiSignAddresses: MultiSignAddress[];
    private domainName: string;

    constructor(multiSignAddresses: MultiSignAddress[], domainName: string) {
        this.multiSignAddresses = multiSignAddresses;
        this.domainName = domainName;
    }

    getMultiSignAddresses(): MultiSignAddress[] {
        return this.multiSignAddresses;
    }

    setMultiSignAddresses(multiSignAddresses: MultiSignAddress[]): void {
        this.multiSignAddresses = multiSignAddresses;
    }

    getDomainName(): string {
        return this.domainName;
    }

    setDomainName(domainName: string): void {
        this.domainName = domainName;
    }

    isEmpty(): boolean {
        return this.multiSignAddresses == null || this.multiSignAddresses.length === 0;
    }
}
