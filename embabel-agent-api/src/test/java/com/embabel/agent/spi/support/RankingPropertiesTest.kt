package com.embabel.agent.spi.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class RankingPropertiesTest {
    @Test
    fun `should log how to configure maximum attempt`(output: CapturedOutput) {
        val properties = RankingProperties(maxAttempts = 1)
        val retryTemplate = properties.retryTemplate("test")
        var attemptCount = 0
        assertThrows<RuntimeException> {
            retryTemplate.execute<Unit, RuntimeException> {
                attemptCount++
                throw RuntimeException("Dummy error")
            }
        }
        // Should only attempt once
        assertEquals(1, attemptCount)
        // Checking default values.
        assertEquals(100, properties.backoffMillis)
        assertEquals(5.0, properties.backoffMultiplier)
        assertEquals(180000, properties.backoffMaxInterval)
        assertTrue(output.out.contains("Maximum attempts of 1 have reached. The maximum attempt can be configured using property embabel.agent.platform.ranking.max-attempts"))
    }
}