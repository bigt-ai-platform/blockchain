package net.bigtangle.server.service;

import java.math.BigInteger;

import org.springframework.stereotype.Service;

import net.bigtangle.core.Coin;
import net.bigtangle.params.NetworkParameters;

@Service
public class FeeService {

    public static final long GAS_LIMIT = 10_000_000L;
    public static final long TARGET_GAS = GAS_LIMIT / 2;
    public static final long BASE_FEE_MAX_CHANGE_DENOMINATOR = 8;

    private long baseFee = 1000; // starts at FEE_DEFAULT equivalent

    public synchronized long getBaseFee() {
        return baseFee;
    }

    public synchronized void updateBaseFee(long gasUsed) {
        if (gasUsed == TARGET_GAS) return;

        long delta = baseFee * Math.abs(gasUsed - TARGET_GAS) / TARGET_GAS;
        delta = delta / BASE_FEE_MAX_CHANGE_DENOMINATOR;
        if (delta < 1) delta = 1;

        if (gasUsed > TARGET_GAS) {
            baseFee += delta;
        } else {
            baseFee = Math.max(1, baseFee - delta);
        }
    }

    public long calculateTotalFee(long gasUsed, long maxPriorityFee) {
        return (baseFee + maxPriorityFee) * gasUsed;
    }
}
