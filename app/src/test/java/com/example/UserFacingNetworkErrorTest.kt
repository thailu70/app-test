package com.example

import com.example.core.network.userFacingNetworkError
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UserFacingNetworkErrorTest {
    @Test fun networkAndInfrastructureErrorsUseOnePrivateMessage() {
        val expected = "Unable to connect. Please connect to the internet."
        assertEquals(expected, userFacingNetworkError(IOException("failed to connect")))
        assertEquals(expected, userFacingNetworkError(IllegalStateException("Service unavailable")))
        assertEquals(expected, userFacingNetworkError(IllegalStateException("See https://passo.live/api")))
        assertEquals(expected, userFacingNetworkError(IllegalStateException("VPS timeout")))
    }

    @Test fun passengerAndDriverActionsKeepUsefulValidationMessages() {
        val validation = "Choose a different pickup and destination."
        assertEquals(validation, userFacingNetworkError(IllegalArgumentException(validation)))
        assertNotEquals("Unable to connect. Please connect to the internet.", userFacingNetworkError(IllegalStateException("Fare changed. Refresh the estimate.")))
    }
}
