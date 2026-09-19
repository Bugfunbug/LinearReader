package com.bugfunbug.linearreader.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeLinearConfigTest {

    @Test
    void configKeysStayAtTomlRootToMatchForgeRewrite() {
        Map<String, Object> values = ForgeLinearConfig.SPEC.getValues().valueMap();

        assertTrue(values.containsKey("compressionLevel"));
        assertTrue(values.containsKey("backupEnabled"));
        assertTrue(values.containsKey("recompressMinFreeRamPercent"));
        assertFalse(values.containsKey("general"));
    }
}
