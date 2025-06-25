import { AddressFormatException } from './AddressFormatException';

export class WrongNetworkException extends AddressFormatException {
    verCode: number;
    acceptableVersions: number[];

    constructor(verCode: number, acceptableVersions: number[]) {
        super(`Version code of address did not match acceptable versions for network: ${verCode} not in [${acceptableVersions.join(', ')}]`);
        this.name = "WrongNetworkException";
        this.verCode = verCode;
        this.acceptableVersions = acceptableVersions;
        Object.setPrototypeOf(this, WrongNetworkException.prototype);
    }
}
