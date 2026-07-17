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
    boolean milestone_active;
    @Value("${service.schedule.mcmcrate:500}")
    Long mcmcrate;
    @Value("${service.schedule.mining:false}")
    boolean mining;

    @Value("${service.schedule.blockbatch:false}")
    boolean blockBatchService_active;

    @Value("${service.schedule.miningrate:50000}")
    Long miningrate;
    @Value("${service.schedule.blockbatchrate:50000}")
    Long blockbatchrate;

    @Value("${service.schedule.syncrate:50000}")
    Long syncrate;

    @Value("${service.schedule.initsync:false}")
    boolean initSync;

    @Value("${service.schedule.microbatch:false}")
    boolean microBatch_active;

    @Value("${pos.enabled:false}")
    boolean posEnabled;
    @Value("${pos.slotIntervalMs:12000}")
    long posSlotIntervalMs;
    @Value("${pos.slotsPerEpoch:32}")
    long posSlotsPerEpoch;

    public boolean isMilestone_active() {
        return milestone_active;
    }

    public void setMilestone_active(boolean milestone_active) {
        this.milestone_active = milestone_active;
    }

    public boolean isBlockBatchService_active() {
        return blockBatchService_active;
    }

    public void setBlockBatchService_active(boolean blockBatchService_active) {
        this.blockBatchService_active = blockBatchService_active;
    }

    public boolean isMining() {
        return mining;
    }

    public void setMining(boolean mining) {
        this.mining = mining;
    }

    public Long getMiningrate() {
        return miningrate;
    }

    public void setMiningrate(Long miningrate) {
        this.miningrate = miningrate;
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

    public boolean isPosEnabled() { return posEnabled; }
    public void setPosEnabled(boolean v) { this.posEnabled = v; }
    public long getPosSlotIntervalMs() { return posSlotIntervalMs; }
    public void setPosSlotIntervalMs(long v) { this.posSlotIntervalMs = v; }
    public long getPosSlotsPerEpoch() { return posSlotsPerEpoch; }
    public void setPosSlotsPerEpoch(long v) { this.posSlotsPerEpoch = v; }

	@Override
	public String toString() {
		return "ScheduleConfiguration [milestone_active=" + milestone_active + ", mcmcrate=" + mcmcrate + ", mining="
				+ mining + ", blockBatchService_active=" + blockBatchService_active + ", miningrate=" + miningrate
				+ ", blockbatchrate=" + blockbatchrate + ", syncrate=" + syncrate + ", initSync=" + initSync
				+ ", microBatch_active=" + microBatch_active + "]";
	}

 

}