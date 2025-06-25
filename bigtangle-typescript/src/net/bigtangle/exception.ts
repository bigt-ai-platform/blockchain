export class AddressFormatException extends Error {
    constructor(message?: string) {
        super(message || "Address format exception");
        this.name = "AddressFormatException";
    }
}

export class WrongNetworkException extends Error {
    acceptableVersions: number[];
    version: number;

    constructor(version: number, acceptableVersions: number[]) {
        super(`Version ${version} not in acceptable versions: ${acceptableVersions.join(', ')}`);
        this.name = "WrongNetworkException";
        this.version = version;
        this.acceptableVersions = acceptableVersions;
    }
}
