export class PermissionDomainname {
    private domain: string;
    private tokenid: string;

    constructor(domain: string, tokenid: string) {
        this.domain = domain;
        this.tokenid = tokenid;
    }

    getDomain(): string {
        return this.domain;
    }

    setDomain(domain: string): void {
        this.domain = domain;
    }

    getTokenid(): string {
        return this.tokenid;
    }

    setTokenid(tokenid: string): void {
        this.tokenid = tokenid;
    }
}
