package com.bobot.iptvapp.data.logout

/**
 * [SessionWriteGate] test double: lets a write through only while [generation] still matches the
 * one it was started with and [open] holds.
 */
class FakeSessionWriteGate(var generation: Int = 0, var open: Boolean = true) : SessionWriteGate {

    override suspend fun <T : Any> runInSession(generation: Int, block: suspend () -> T): T? =
        if (open && generation == this.generation) block() else null
}
