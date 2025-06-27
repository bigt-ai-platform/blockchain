import { AbstractResponse } from './AbstractResponse';

export class ExceptionResponse extends AbstractResponse {
    private exception: string | null = null;

    public static create(exception: string): ExceptionResponse {
        const res = new ExceptionResponse();
        res.exception = exception;
        return res;
    }

    public getException(): string | null {
        return this.exception;
    }
}
