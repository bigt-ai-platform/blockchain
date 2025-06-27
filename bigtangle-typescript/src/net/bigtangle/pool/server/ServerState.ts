export class ServerState {
    public serverurl: string | null = null;
    public status: string | null = null;
    public chainlength: number | null = null;
    public responseTime: number | null = null;

    public getServerurl(): string | null {
        return this.serverurl;
    }

    public setServerurl(serverurl: string | null): void {
        this.serverurl = serverurl;
    }

    public getStatus(): string | null {
        return this.status;
    }

    public setStatus(status: string | null): void {
        this.status = status;
    }

    public getChainlength(): number | null {
        return this.chainlength;
    }

    public setChainlength(chainlength: number | null): void {
        this.chainlength = chainlength;
    }

    public getResponseTime(): number | null {
        return this.responseTime;
    }

    public setResponseTime(responseTime: number | null): void {
        this.responseTime = responseTime;
    }

    public toString(): string {
        return `ServerState [serverurl=${this.serverurl}, status=${this.status}, chainlength=${this.chainlength}` +
               `, responseTime=${this.responseTime}]`;
    }

    public hashCode(): number {
        const prime = 31;
        let result = 1;
        result = prime * result + (this.chainlength == null ? 0 : this.chainlength.hashCode());
        result = prime * result + (this.responseTime == null ? 0 : this.responseTime.hashCode());
        result = prime * result + (this.serverurl == null ? 0 : this.serverurl.hashCode());
        result = prime * result + (this.status == null ? 0 : this.status.hashCode());
        return result;
    }

    public equals(obj: any): boolean {
        if (this === obj) return true;
        if (obj == null || this.constructor !== obj.constructor) return false;
        const other = obj as ServerState;
        if (this.chainlength == null) {
            if (other.chainlength != null) return false;
        } else if (this.chainlength !== other.chainlength) return false;
        if (this.responseTime == null) {
            if (other.responseTime != null) return false;
        } else if (this.responseTime !== other.responseTime) return false;
        if (this.serverurl == null) {
            if (other.serverurl != null) return false;
        } else if (this.serverurl !== other.serverurl) return false;
        if (this.status == null) {
            if (other.status != null) return false;
        } else if (this.status !== other.status) return false;
        return true;
    }
}
