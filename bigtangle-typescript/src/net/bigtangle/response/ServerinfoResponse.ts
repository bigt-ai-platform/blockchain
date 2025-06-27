import { AbstractResponse } from './AbstractResponse';
import { ServerInfo } from './ServerInfo';

export class ServerinfoResponse extends AbstractResponse {
    private serverInfoList: ServerInfo[] | null = null;

    public static create(serverInfoList: ServerInfo[]): ServerinfoResponse {
        const res = new ServerinfoResponse();
        res.serverInfoList = serverInfoList;
        return res;
    }

    public getServerInfoList(): ServerInfo[] | null {
        return this.serverInfoList;
    }

    public setServerInfoList(serverInfoList: ServerInfo[] | null): void {
        this.serverInfoList = serverInfoList;
    }
}
