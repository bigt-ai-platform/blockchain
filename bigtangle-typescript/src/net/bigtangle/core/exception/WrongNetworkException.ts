export class WrongNetworkException extends Error {
    constructor(
        public version: number,
        public acceptableVersions: number[]
    ) {
        super(`Version ${version} is not in the list of acceptable versions: [${acceptableVersions.join(', ')}]`);
        this.name = 'WrongNetworkException';
    }
}
