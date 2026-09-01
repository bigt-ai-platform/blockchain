package net.bigtangle.l1.social;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The fee-free contract of L1-SOCIAL: the server start must set
 * bigtangle.fee.default=0 before any code references Coin, whose FEE_DEFAULT
 * is a static final snapshot taken at class load. The block-level fee gate in
 * ServiceBaseCheck is skipped when FEE_DEFAULT is zero, so this property is
 * what makes the chain fee-free.
 */
public class SocialL1ServerStartTest {

    @Test
    public void testConfigureZeroFee() {
        System.clearProperty("bigtangle.fee.default");
        SocialL1ServerStart.configureZeroFee();
        assertEquals("0", System.getProperty("bigtangle.fee.default"));
    }
}
