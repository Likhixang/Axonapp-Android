package cc.khixang.axonhub.gateway

import cc.khixang.axonhub.network.AxonException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GatewayBatchTest {
    @Test fun `serial batch preserves unique target order and continues after partial failure`() = runTest {
        val visited = mutableListOf<String>()
        val result = runGatewayBatch(listOf("first", "bad", "first", "last")) { id ->
            visited += id
            if (id == "bad") throw AxonException.Rejected
        }
        assertEquals(listOf("first", "bad", "last"), visited)
        assertEquals(visited, result.items.map { it.id })
        assertEquals(2, result.succeeded)
        assertEquals(1, result.failed)
        assertEquals(0, result.notAttempted)
        assertEquals(listOf(true, false, true), result.items.map { it.verified })
        assertNotNull(result.items[1].error)
    }

    @Test fun `target change and expired authentication stop remaining writes`() = runTest {
        listOf(AxonException.TargetChanged, AxonException.Unauthorized, AxonException.InvalidCredentials).forEach { failure ->
            val visited = mutableListOf<String>()
            val result = runGatewayBatch(listOf("done", "changed", "untouched")) { id ->
                visited += id
                if (id == "changed") throw failure
            }
            assertEquals(listOf("done", "changed"), visited)
            assertEquals(1, result.succeeded)
            assertEquals(1, result.failed)
            assertEquals(1, result.notAttempted)
            assertFalse(result.items.last().attempted)
        }
    }

    @Test fun `coroutine cancellation is not reported as a normal item failure`() = runTest {
        val cancellation = CancellationException("cancelled")
        var visited = 0
        try {
            runGatewayBatch(listOf("a", "b")) { visited++; throw cancellation }
            fail("Expected cancellation")
        } catch (caught: CancellationException) { assertSame(cancellation, caught) }
        assertEquals(1, visited)
    }

    @Test fun `empty id rejects whole batch before any write and empty selection is a no op`() = runTest {
        var writes = 0
        try {
            runGatewayBatch(listOf("a", "")) { writes++ }
            fail("Expected invalid input")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, writes)
        assertTrue(runGatewayBatch(emptyList()) { writes++ }.items.isEmpty())
    }

    @Test fun `untrusted exception prose never crosses Gateway UI boundary`() {
        val secret = "password=server-secret Bearer token-value https://user:pass@host"
        listOf(IllegalStateException(secret), IllegalArgumentException(secret), java.io.IOException(secret)).forEach {
            val message = gatewayErrorMessage(it)
            assertFalse(message.contains("server-secret"))
            assertFalse(message.contains("token-value"))
            assertFalse(message.contains("user:pass"))
        }
        assertTrue(gatewayErrorMessage(AxonException.VerificationFailed).contains("可能已生效"))
    }

    @Test fun `disabled filter does not silently include archived or unknown states`() {
        assertTrue(GatewayStatusFilter.DISABLED.matches("disabled"))
        assertFalse(GatewayStatusFilter.DISABLED.matches("archived"))
        assertFalse(GatewayStatusFilter.DISABLED.matches(""))
        assertTrue(GatewayStatusFilter.ENABLED.matches("enabled"))
        assertFalse(GatewayStatusFilter.ENABLED.matches("ENABLED"))
        assertTrue(GatewayStatusFilter.OTHER.matches("archived"))
        assertTrue(GatewayStatusFilter.OTHER.matches(""))
        assertFalse(GatewayStatusFilter.OTHER.matches("enabled"))
        assertTrue(GatewayStatusFilter.ALL.matches("future-server-status"))
    }
}
