package com.github.appachan.supplychainlab

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {
    @Test
    fun `greeting includes the given name`() {
        assertEquals("Hello, lab!", greeting("lab"))
    }
}
