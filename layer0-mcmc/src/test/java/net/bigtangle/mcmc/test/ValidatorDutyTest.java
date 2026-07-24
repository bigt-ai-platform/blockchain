package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.core.PQKey;
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

    private PQKey validatorKey;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);
        validatorKey = PQKey.createNew();
    }

	@Test
	public void testValidatorKeySetAndGet() {
		PQKey testKey = PQKey.createNew();
		validatorDutyService.setValidatorKey(testKey);
		assertNotNull(validatorDutyService.getValidatorKey());
		assertEquals(Utils.HEX.encode(testKey.getPubKey()),
				Utils.HEX.encode(validatorDutyService.getValidatorKey().getPubKey()));
	}

	@Test
	public void testPerformDutyWithoutKey() throws Exception {
		validatorDutyService.performDuty();
	}

	@Test
	public void testValidatorKeyInitFromConfig() {
		PQKey testKey = PQKey.createNew();
		validatorDutyService.setValidatorKey(testKey);
		assertNotNull(validatorDutyService.getValidatorKey());
		assertEquals(Utils.HEX.encode(testKey.getPubKey()),
				Utils.HEX.encode(validatorDutyService.getValidatorKey().getPubKey()));
	}
}
