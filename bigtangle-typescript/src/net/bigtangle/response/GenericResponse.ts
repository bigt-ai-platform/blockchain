import { AbstractResponse } from './AbstractResponse';

export class GenericResponse<T> extends AbstractResponse {
    private data: T | null = null;

    public set(data: T): void {
        this.data = data;
    }

    public get(): T | null {
        return this.data;
    }
}
