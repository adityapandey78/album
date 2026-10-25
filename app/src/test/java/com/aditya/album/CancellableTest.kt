package com.aditya.album

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

class CancellableTest {
    @Test fun cancellingSignalsTheBlockingWorkWhileItIsStillRunning() = runBlocking {
        val started = CountDownLatch(1)
        val signalled = CountDownLatch(1)
        val load = launch(Dispatchers.IO) {
            cancellable(onCancel = signalled::countDown) {
                started.countDown()
                // Stands in for loadThumbnail: only stops early if the signal arrives while it's blocked
                check(signalled.await(5, SECONDS)) { "not signalled while running" }
            }
        }
        started.await()
        load.cancel()
        assertTrue(signalled.await(5, SECONDS))
        load.join()
    }

    @Test fun finishingNormallyNeverSignals() = runBlocking {
        var signals = 0
        assertEquals(42, cancellable(onCancel = { signals++ }) { 42 })
        assertEquals(0, signals)
    }
}
