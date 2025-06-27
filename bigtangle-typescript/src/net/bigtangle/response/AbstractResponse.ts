export abstract class AbstractResponse {
    private errorcode: number | null = null;
    private message: string | null = null;
    private duration: number | null = null;

    public getMessage(): string | null {
        return this.message;
    }

    public setMessage(message: string | null): void {
        this.message = message;
    }

    public getErrorcode(): number | null {
        return this.errorcode;
    }

    public setErrorcode(errorcode: number | null): void {
        this.errorcode = errorcode;
    }

    public static createEmptyResponse(): AbstractResponse {
        return new Emptyness();
    }

    public getDuration(): number {
        return this.duration === null ? 0 : this.duration;
    }

    public setDuration(duration: number | null): void {
        this.duration = duration;
    }
}

class Emptyness extends AbstractResponse {
    // No additional properties or methods needed for an empty response
}
