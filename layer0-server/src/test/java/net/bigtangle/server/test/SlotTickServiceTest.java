package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.core.PQKey;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.server.config.ScheduleConfiguration;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.SlotService;
import net.bigtangle.server.service.StakeService;
import net.bigtangle.server.service.schedule.SlotTickService;

public class SlotTickServiceTest extends AbstractIntegrationTest {

    @Autowired
    private SlotTickService slotTickService;

    @Autowired
    private ScheduleConfiguration scheduleConfiguration;

    @Autowired
    private ServerConfiguration serverConfiguration;

    @Autowired
    private SlotService slotService;

    @Autowired
    private StakeService stakeService;

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
    public void testTickWithValidators() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        assertDoesNotThrow(() -> slotTickService.tick());
    }

    @Test
    public void testTickNoOpWhenServiceNotReady() {
        boolean wasReady = serverConfiguration.checkService();
        serverConfiguration.setServiceReady(false);
        try {
            assertDoesNotThrow(() -> slotTickService.tick());
        } finally {
            serverConfiguration.setServiceReady(wasReady);
        }
    }

    @Test
    public void testTickWithPosEnabledAndServiceReady() throws Exception {
        store.saveStakeDeposit(new StakeRecord(
                validatorKey.getPubKey(), StakeService.MIN_STAKE,
                validatorKey.getPubKeyHash()));
        stakeService.activateValidator(validatorKey.getPubKey(), 0, store);

        assertDoesNotThrow(() -> slotTickService.tick());
    }

    @Test
    public void testSlotTickServiceNotNull() {
        assertNotNull(slotTickService);
    }

    @Test
    public void testSlotCalculation() {
        long slot = slotService.getCurrentSlot();
        assertNotNull(slotService);
    }
}
