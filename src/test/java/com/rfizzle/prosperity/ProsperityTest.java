package com.rfizzle.prosperity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProsperityTest {
    @Test
    void modIdIsProsperity() {
        // MOD_ID is a compile-time String constant, so referencing it here does
        // not trigger Fabric classloading (fabric-api is off the test classpath).
        assertEquals("prosperity", Prosperity.MOD_ID);
    }
}
