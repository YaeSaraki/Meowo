package com.meowo.shared

import com.meowo.shared.lifecycle.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContextManagerTest {
    private fun context(name: String, log: MutableList<String>, failStart: Boolean = false, failClose: Boolean = false) = object : Context {
        override val id = name
        override fun start() { log += "start:$id"; if (failStart) error("start") }
        override fun close() { log += "close:$id"; if (failClose) error("close") }
    }
    @Test fun `reverse shutdown and failed startup rollback`() {
        val log = mutableListOf<String>(); val failures = mutableListOf<String>()
        val manager = ContextManager { id, _ -> failures += id }
        assertTrue(manager.enable(context("one",log)))
        assertFalse(manager.enable(context("bad",log,true)))
        assertTrue(manager.enable(context("two",log,failClose=true)))
        assertThrows(IllegalStateException::class.java) { manager.enable(context("one",log)) }
        manager.close(); manager.close()
        assertEquals(listOf("start:one","start:bad","close:bad","start:two","close:two","close:one"),log)
        assertEquals(listOf("bad","two"),failures); assertTrue(manager.ids().isEmpty())
    }
    @Test fun `disable one context leaves others active`() {
        val log = mutableListOf<String>(); val manager = ContextManager { _, e -> throw e }
        manager.enable(context("one",log)); manager.enable(context("two",log)); manager.disable("one")
        assertFalse(manager.isEnabled("one")); assertTrue(manager.isEnabled("two")); manager.close()
    }
}
