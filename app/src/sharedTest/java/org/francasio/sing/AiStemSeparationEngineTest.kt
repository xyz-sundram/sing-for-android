package org.francasio.sing

import org.francasio.sing.audio.AiStemSeparationEngine
import org.francasio.sing.audio.backend.InferenceBackend
import org.francasio.sing.util.ModelLoader
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Runs with real DSP on Android or the host JNI build; inference is a controlled fake. */
class AiStemSeparationEngineTest {
    @Before fun requireHostDsp() {
        assumeTrue(java.lang.Boolean.getBoolean("sing.native.tests"))
    }

    private class Backend(private val blockedCall: Int = -1) : InferenceBackend {
        override val providerName = "test"
        override val shortProviderName = "test"
        override val modelInfo = ModelLoader.ModelInfo(
            id = "test", modelId = "test", modelDisplayName = "test", displayName = "test",
            targetStem = "vocal", networkType = "time_domain", lookaheadSize = 0,
            fileName = "test.vinet", fileSizeDesc = ""
        )
        val calls = AtomicInteger()
        val blocked = CountDownLatch(1)
        val proceed = Semaphore(0)
        override fun runTimeDomain(inPcm: FloatArray, outPcm: FloatArray, frameCount: Int) {
            if (calls.incrementAndGet() == blockedCall) {
                blocked.countDown()
                check(proceed.tryAcquire(5, TimeUnit.SECONDS)) { "Test inference timed out" }
            }
            System.arraycopy(inPcm, 0, outPcm, 0, frameCount * 2)
        }
        override fun close() {}
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Timed out waiting for worker", condition())
    }

    @Test fun underrunRetainsUnprocessedFramesAndEosBecomesIdle() {
        val backend = Backend(blockedCall = 2)
        val engine = AiStemSeparationEngine(backend)
        try {
            engine.ecoMode = false
            engine.activate()
            engine.pushInput(FloatArray(8192 * 2) { (it / 2) / 10000f }, 8192)
            assertTrue(backend.blocked.await(3, TimeUnit.SECONDS))
            val first = FloatArray(6144 * 2)
            assertEquals(4096, engine.popVocalAndBlend(first, 6144, 1f))
            assertEquals(0, engine.popVocalAndBlend(first, 6144, 1f))
            backend.proceed.release()
            awaitCondition { engine.availableVocalFrames == 4096L }
            val last = FloatArray(4096 * 2)
            assertEquals(4096, engine.popVocalAndBlend(last, 4096, 1f))
            assertEquals(0.4096f, last[0], 0.00001f)
            assertEquals(0, engine.popVocalAndBlend(last, 4096, 1f))
            engine.onEndOfStream()
            awaitCondition { engine.isEnded() }
            Thread.sleep(60)
            assertEquals("EOS must not keep running inference", 2, backend.calls.get())
        } finally {
            backend.proceed.release()
            engine.release()
        }
    }

    @Test fun mutedPlaybackNeverLeaksOriginalVocalsDuringAnInferenceUnderrun() {
        val backend = Backend(blockedCall = 2)
        val engine = AiStemSeparationEngine(backend)
        try {
            engine.ecoMode = false
            engine.activate()
            engine.pushInput(FloatArray(8192 * 2) { 0.5f }, 8192)
            assertTrue(backend.blocked.await(3, TimeUnit.SECONDS))
            val output = FloatArray(8192 * 2)
            // The backend returns the entire input as the vocal stem. Muting it
            // must stay silent even while the second block is still computing.
            assertEquals(4096, engine.popVocalAndBlend(output, 8192, 0f))
            assertTrue(output.take(4096 * 2).all { kotlin.math.abs(it) < 0.00001f })
            assertEquals(0, engine.popVocalAndBlend(output, 4096, 0f))
            backend.proceed.release()
            awaitCondition { engine.availableVocalFrames == 4096L }
            assertEquals(4096, engine.popVocalAndBlend(output, 4096, 0f))
            assertTrue(output.take(4096 * 2).all { kotlin.math.abs(it) < 0.00001f })
        } finally {
            backend.proceed.release()
            engine.release()
        }
    }

    @Test fun intermediateSliderLevelsMixBothStemTypesContinuously() {
        val levels = listOf(0f, 0.01f, 0.05f, 0.25f, 0.5f, 0.75f, 1f)
        for (instrumental in listOf(false, true)) for (twoWay in listOf(false, true)) {
            val backend = object : InferenceBackend {
                override val providerName = "controlled stems"
                override val shortProviderName = "test"
                override val modelInfo = ModelLoader.ModelInfo(
                    id = "test", modelId = "test", modelDisplayName = "test", displayName = "test",
                    targetStem = if (instrumental) "instrumental" else "vocal",
                    networkType = "time_domain", lookaheadSize = 0,
                    fileName = "test.vinet", fileSizeDesc = ""
                )
                override fun runTimeDomain(inPcm: FloatArray, outPcm: FloatArray, frameCount: Int) {
                    for (i in 0 until frameCount * 2) outPcm[i] = if (instrumental) 0.2f else 0.3f
                }
                override fun close() {}
            }
            val engine = AiStemSeparationEngine(backend)
            try {
                engine.ecoMode = false
                engine.twoWaySlider = twoWay
                engine.activate()
                val frames = 4096 * levels.size
                engine.pushInput(FloatArray(frames * 2) { 0.5f }, frames)
                engine.onEndOfStream()
                awaitCondition { engine.availableVocalFrames == frames.toLong() }
                for (level in levels) {
                    val output = FloatArray(4096 * 2)
                    assertEquals(4096, engine.popVocalAndBlend(output, 4096, level))
                    val expected = when {
                        !twoWay -> 0.2f + 0.3f * level
                        level <= 0.5f -> 0.2f + 0.6f * level
                        else -> 0.3f + 0.2f * (2f - 2f * level)
                    }
                    for (sample in output) assertEquals("level=$level, instrumental=$instrumental, twoWay=$twoWay", expected, sample, 0.00001f)
                }
            } finally {
                engine.release()
            }
        }
    }

    @Test fun resetWaitsForInferenceBeforeClearingWorkerBuffers() {
        val backend = Backend(blockedCall = 1)
        val engine = AiStemSeparationEngine(backend)
        val resetDone = CountDownLatch(1)
        try {
            engine.activate()
            engine.pushInput(FloatArray(4096 * 2) { 0.25f }, 4096)
            assertTrue(backend.blocked.await(3, TimeUnit.SECONDS))
            val resetThread = Thread {
                try { engine.reset() } finally { resetDone.countDown() }
            }
            resetThread.start()
            assertFalse(resetDone.await(50, TimeUnit.MILLISECONDS))
            backend.proceed.release()
            assertTrue(resetDone.await(3, TimeUnit.SECONDS))
            assertEquals(0L, engine.availableVocalFrames)
            assertEquals(0L, engine.cushionFrames)
        } finally {
            backend.proceed.release()
            engine.release()
        }
    }

    @Test fun deactivationDoesNotWaitForInferenceOnTheUiThread() {
        val backend = Backend(blockedCall = 1)
        val engine = AiStemSeparationEngine(backend)
        try {
            engine.activate()
            engine.pushInput(FloatArray(4096 * 2), 4096)
            assertTrue(backend.blocked.await(3, TimeUnit.SECONDS))
            val deactivated = CountDownLatch(1)
            Thread { engine.deactivate(); deactivated.countDown() }.start()
            assertTrue(deactivated.await(1, TimeUnit.SECONDS))
            assertFalse(engine.active)
            assertEquals(0L, engine.availableVocalFrames)
        } finally {
            backend.proceed.release()
            engine.release()
        }
    }

    @Test fun releaseJoinsInferenceBeforeFreeingNativeDsp() {
        val backend = Backend(blockedCall = 1)
        val engine = AiStemSeparationEngine(backend)
        val released = CountDownLatch(1)
        try {
            engine.activate()
            engine.pushInput(FloatArray(4096 * 2), 4096)
            assertTrue(backend.blocked.await(3, TimeUnit.SECONDS))
            Thread { engine.release(); released.countDown() }.start()
            assertFalse(released.await(50, TimeUnit.MILLISECONDS))
            backend.proceed.release()
            assertTrue(released.await(3, TimeUnit.SECONDS))
            assertFalse(engine.active)
            engine.release() // Idempotent.
        } finally {
            backend.proceed.release()
            engine.release()
        }
    }

    @Test fun spectralAndVrWorkersDrainPartialChunksAndBecomeIdle() {
        for (networkType in listOf("stft", "vr_3band")) {
            val calls = AtomicInteger()
            val backend = object : InferenceBackend {
                override val providerName = "test"
                override val shortProviderName = "test"
                override val modelInfo = ModelLoader.ModelInfo(
                    id = "test", modelId = "test", modelDisplayName = "test", displayName = "test",
                    targetStem = "vocal", networkType = networkType,
                    numBins = if (networkType == "stft") 2048 else 769,
                    numFrames = if (networkType == "stft") 256 else 512,
                    fileName = "test.onnx", fileSizeDesc = ""
                )
                override fun run(stftInput: FloatArray, vocalOutput: FloatArray) {
                    calls.incrementAndGet()
                    vocalOutput.fill(0f)
                }
                override fun close() {}
            }
            val engine = AiStemSeparationEngine(backend)
            try {
                engine.activate()
                engine.pushInput(FloatArray(4096 * 2) { 0.2f }, 4096)
                engine.onEndOfStream()
                awaitCondition { engine.availableVocalFrames == 4096L }
                assertEquals(4096, engine.popVocalAndBlend(FloatArray(4096 * 2), 4096, 1f))
                awaitCondition { engine.isEnded() }
                Thread.sleep(60)
                assertEquals("$networkType EOS must not keep running inference", 1, calls.get())
            } finally {
                engine.release()
            }
        }
    }

    @Test fun backpressureRetainsInputNeededBySlowInference() {
        val backend = Backend(blockedCall = 2)
        val engine = AiStemSeparationEngine(backend)
        try {
            engine.ecoMode = false
            engine.activate()
            engine.pushInput(FloatArray(8192 * 2), 8192)
            assertTrue(backend.blocked.await(3, TimeUnit.SECONDS))
            // Inference still needs its retained input while playback waits.
            // Further writes must not be allowed to wrap it.
            engine.popVocalAndBlend(FloatArray(8192 * 2), 8192, 1f)
            engine.pushInput(FloatArray(600000 * 2), 600000)
            assertFalse(engine.canAcceptInput(100000))
        } finally {
            backend.proceed.release()
            engine.release()
        }
    }

    @Test fun partialTimeDomainEosKeepsLookaheadFlushBlockAligned() {
        val previous = FloatArray(4096 * 2)
        val backend = object : InferenceBackend {
            override val providerName = "delayed test"
            override val shortProviderName = "test"
            override val modelInfo = ModelLoader.ModelInfo(
                id = "test", modelId = "test", modelDisplayName = "test", displayName = "test",
                targetStem = "vocal", networkType = "time_domain", lookaheadSize = 4096,
                fileName = "test.vinet", fileSizeDesc = ""
            )
            override fun runTimeDomain(inPcm: FloatArray, outPcm: FloatArray, frameCount: Int) {
                System.arraycopy(previous, 0, outPcm, 0, previous.size)
                System.arraycopy(inPcm, 0, previous, 0, previous.size)
            }
            override fun resetStates() { previous.fill(0f) }
            override fun close() {}
        }
        val engine = AiStemSeparationEngine(backend)
        try {
            engine.activate()
            engine.pushInput(FloatArray(5000 * 2) { (it / 2) / 10000f }, 5000)
            engine.onEndOfStream()
            awaitCondition { engine.availableVocalFrames == 5000L }
            assertFalse(engine.isEnded())
            val instrumental = FloatArray(5000 * 2)
            assertEquals(5000, engine.popVocalAndBlend(instrumental, 5000, 0f))
            // Identity vocal separation with one-block latency must subtract
            // exactly the matching source samples, including the partial tail.
            assertTrue(instrumental.all { kotlin.math.abs(it) < 0.00001f })
            assertTrue(engine.isEnded())
        } finally {
            engine.release()
        }
    }
}
