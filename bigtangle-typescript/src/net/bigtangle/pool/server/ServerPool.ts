import { NetworkParameters } from '../../core/NetworkParameters';
import { TXReward } from '../../core/TXReward';
import { ReqCmd } from '../../params/ReqCmd'; // Placeholder
import { GetTXRewardResponse } from '../../response/GetTXRewardResponse';
import { ServerInfo } from '../../response/ServerInfo';
import { ServerinfoResponse } from '../../response/ServerinfoResponse';
import { Json } from '../../utils/Json';
import { OkHttp3Util } from '../../utils/OkHttp3Util'; // Placeholder
import { ServerState } from './ServerState';

/*
 * keep the potential list of servers and check the servers.
 * A List of server, which can provide block service
 * 1) check the server chain length 
 * 2) check the response speed of the server
 * 3) check the health of the server
 * 4) calculate balance of the server select for random 
 * 5) discover server start from NetworkParameter.getServers
 * 6) save servers with kafka server in Userdata Block to read
 * 7) 
 */
export class ServerPool {
    private servers: ServerState[] = [];
    protected readonly params: NetworkParameters;
    protected fixservers: string[] | null = null;

    constructor(params: NetworkParameters, fixservers?: string[]) {
        this.params = params;
        if (fixservers) {
            this.fixservers = fixservers;
            for (const fixserver of this.fixservers) {
                try {
                    this.addServer(fixserver);
                } catch (e: any) {
                    console.debug("", e);
                }
            }
        } else {
            try {
                const requestParam: { [key: string]: string } = {};
                // Assuming OkHttp3Util.post returns a Promise<Uint8Array>
                OkHttp3Util.post(params.serverSeeds()[0] + ReqCmd.serverinfolist, Json.jsonmapper().writeValueAsString(requestParam)).then(data => {
                    const response = Json.jsonmapper().readValue(new TextDecoder().decode(data), ServerinfoResponse);
                    if (response.getServerInfoList() !== null) {
                        for (const serverInfo of response.getServerInfoList()!) {
                            if (serverInfo.getStatus() === "inactive") {
                                continue;
                            }
                            try {
                                this.addServer(serverInfo.getUrl()!);
                            } catch (e: any) {
                                console.debug("", e);
                            }
                        }
                    }
                }).catch(e => {
                    console.debug("", e);
                });
            } catch (e: any) {
                console.debug("", e);
            }
        }
    }

    public serverSeeds(): void {
        for (const s of this.params.serverSeeds()) {
            try {
                this.addServer(s);
            } catch (e: any) {
                console.debug("", e);
            }
        }
    }

    public getServer(): ServerState {
        return this.servers[0];
    }

    public async addServer(s: string): Promise<void> {
        const time = Date.now();
        const serverState = new ServerState();
        serverState.setServerurl(s);
        serverState.setResponseTime(Date.now() - time);
        // serverState.setChainlength(chain.getChainLength()); // This needs getChainNumber to be implemented
        this.servers.push(serverState);
        // Collections.sort(servers, new SortbyChain()); // Sorting will be done separately
    }

    public checkServers(): void {
        for (let i = this.servers.length - 1; i >= 0; i--) {
            const a = this.servers[i];
            try {
                this.addServer(a.getServerurl()!);
            } catch (e: any) {
                console.debug("addServer failed and remove it", e);
                this.servers.splice(i, 1);
            }
        }
    }

    public removeServer(server: string): void {
        for (let i = this.servers.length - 1; i >= 0; i--) {
            const a = this.servers[i];
            if (a.getServerurl() === server) {
                this.servers.splice(i, 1);
            }
        }
    }

    public addServers(serverCandidates: string[]): void {
        this.servers = [];
        for (const s of serverCandidates) {
            try {
                this.addServer(s);
            } catch (e: any) {
                console.debug(e.toString());
            }
        }
    }

    public async getChainNumber(s: string): Promise<TXReward | null> {
        const requestParam: { [key: string]: string } = {};
        const response = await OkHttp3Util.postString(`${s.trim()}/${ReqCmd.getChainNumber}`, Json.jsonmapper().writeValueAsString(requestParam));
        const aTXRewardResponse = Json.jsonmapper().readValue(new TextDecoder().decode(response), GetTXRewardResponse);
        return aTXRewardResponse.getTxReward();
    }

    public getServers(): ServerState[] {
        return this.servers;
    }

    public setServers(servers: ServerState[]): void {
        this.servers = servers;
    }
}

export class SortbyChain implements Comparator<ServerState> {
    public compare(a: ServerState, b: ServerState): number {
        if (a.getChainlength() - b.getChainlength() <= 1) {
            return a.getResponseTime() > b.getResponseTime() ? 1 : -1;
        }
        return a.getChainlength() > b.getChainlength() ? -1 : 1;
    }
}

interface Comparator<T> {
    compare(a: T, b: T): number;
}
