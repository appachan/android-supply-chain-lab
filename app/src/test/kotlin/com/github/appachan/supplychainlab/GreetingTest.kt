package com.github.appachan.supplychainlab

import com.github.appachan.supplychainlab.shared.domain.greeting
import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {
    @Test
    fun `greeting includes the given name`() {
        assertEquals("Hello, lab!", greeting("lab"))
    }
}
