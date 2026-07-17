package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.core.ECKey;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.Utils;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.service.CasperService;
import net.bigtangle.server.service.GhostService;
import net.bigtangle.server.service.SlotService;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.server.service.ValidatorDutyService;

public class ValidatorDutyTest extends AbstractIntegrationTest {

    @Autowired
    private ValidatorDutyService validatorDutyService;

    @Autowired
    private SlotService slotService;

    @Autowired
    private StakeService stakeService;

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    private ECKey validatorKey;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);
        validatorKey = new ECKey();
    }

    @Test
    public void testValidatorKeySetAndGet() {
        ECKey original = validatorDutyService.getValidatorKey();
        ECKey testKey = new ECKey();
        validatorDutyService.setValidatorKey(testKey);
        assertNotNull(validatorDutyService.getValidatorKey());
        assertEquals(Utils.HEX.encode(testKey.getPubKey()),
                Utils.HEX.encode(validatorDutyService.getValidatorKey().getPubKey()));
        validatorDutyService.setValidatorKey(original);
    }

    @Test
    public void testPerformDutyWithPosDisabled() throws Exception {
        boolean wasEnabled = scheduleConfiguration.isPosEnabled();
        scheduleConfiguration.setPosEnabled(false);
        validatorDutyService.setValidatorKey(validatorKey);
        try {
            validatorDutyService.performDuty();
        } finally {
            scheduleConfiguration.setPosEnabled(wasEnabled);
        }
    }

    @Test
    public void testPerformDutyWithoutKey() throws Exception {
        boolean wasEnabled = scheduleConfiguration.isPosEnabled();
        scheduleConfiguration.setPosEnabled(true);
        try {
            validatorDutyService.performDuty();
        } finally {
            scheduleConfiguration.setPosEnabled(wasEnabled);
        }
    }

    @Test
    public void testValidatorKeyInitFromConfig() {
        ECKey configured = validatorDutyService.getValidatorKey();
        ECKey testKey = new ECKey();
        validatorDutyService.setValidatorKey(testKey);
        assertNotNull(validatorDutyService.getValidatorKey());
        assertEquals(Utils.HEX.encode(testKey.getPubKey()),
                Utils.HEX.encode(validatorDutyService.getValidatorKey().getPubKey()));
        validatorDutyService.setValidatorKey(configured);
    }
}
