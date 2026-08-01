/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ScheduleConfiguration {

    @Value("${service.schedule.mcmc:false}")
    boolean chainlength_active;
    @Value("${service.schedule.mcmcrate:500}")
    Long mcmcrate;

    @Value("${service.schedule.reward:true}")
    boolean reward_active;

    @Value("${service.schedule.blockbatch:false}")
    boolean blockBatchService_active;

    @Value("${service.schedule.blockbatchrate:50000}")
    Long blockbatchrate;

    @Value("${service.schedule.syncrate:50000}")
    Long syncrate;

    @Value("${service.schedule.initsync:false}")
    boolean initSync;

    @Value("${service.schedule.microbatch:false}")
    boolean microBatch_active;

    @Value("${pos.slotIntervalMs:12000}")
    long posSlotIntervalMs;
    @Value("${pos.slotsPerEpoch:32}")
    long posSlotsPerEpoch;

    public boolean isChainlength_active() {
        return chainlength_active;
    }

    public boolean isReward_active() {
        return reward_active;
    }

    public void setChainlength_active(boolean chainlength_active) {
        this.chainlength_active = chainlength_active;
    }

    public boolean isBlockBatchService_active() {
        return blockBatchService_active;
    }

    public void setBlockBatchService_active(boolean blockBatchService_active) {
        this.blockBatchService_active = blockBatchService_active;
    }

    public Long getBlockbatchrate() {
        return blockbatchrate;
    }

    public void setBlockbatchrate(Long blockbatchrate) {
        this.blockbatchrate = blockbatchrate;
    }

    public Long getMcmcrate() {
        return mcmcrate;
    }

    public void setMcmcrate(Long mcmcrate) {
        this.mcmcrate = mcmcrate;
    }

    public Long getSyncrate() {
        return syncrate;
    }

    public void setSyncrate(Long syncrate) {
        this.syncrate = syncrate;
    }

	public boolean isInitSync() {
		return initSync;
	}

	public void setInitSync(boolean initSync) {
		this.initSync = initSync;
	}

    public boolean isMicroBatch_active() {
        return microBatch_active;
    }

    public void setMicroBatch_active(boolean microBatch_active) {
        this.microBatch_active = microBatch_active;
    }

    public boolean isPosEnabled() { return true; }
    public void setPosEnabled(boolean v) { }
    public long getPosSlotIntervalMs() { return posSlotIntervalMs; }
    public void setPosSlotIntervalMs(long v) { this.posSlotIntervalMs = v; }
    public long getPosSlotsPerEpoch() { return posSlotsPerEpoch; }
    public void setPosSlotsPerEpoch(long v) { this.posSlotsPerEpoch = v; }

	@Override
	public String toString() {
		return "ScheduleConfiguration [chainlength_active=" + chainlength_active + ", mcmcrate=" + mcmcrate
				+ ", blockBatchService_active=" + blockBatchService_active + ", blockbatchrate=" + blockbatchrate
				+ ", syncrate=" + syncrate + ", initSync=" + initSync
				+ ", microBatch_active=" + microBatch_active + "]";
	}

 

}