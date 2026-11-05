package org.francasio.sing

import org.francasio.sing.util.AppleMusicConnection
import org.francasio.sing.util.AppleMusicConnectionResolver
import org.francasio.sing.util.AppleMusicInstallation
import org.francasio.sing.util.AppleMusicPatch
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppleMusicConnectionTest {
    private val now = 1_000_000L
    private val patched = AppleMusicInstallation(true, AppleMusicPatch.SING_EMBEDDED, 100L)

    private fun resolve(
        installation: AppleMusicInstallation = patched,
        heartbeat: Long = 0L,
        version: String? = "1.2",
        frameworkReady: Boolean = false
    ) = AppleMusicConnectionResolver.resolve(installation, heartbeat, version, "1.2", frameworkReady, now)

    @Test
    fun missingAppleMusicTakesPriorityOverOldConnectionData() {
        assertEquals(AppleMusicConnection.NOT_INSTALLED, resolve(AppleMusicInstallation(false), now - 1, frameworkReady = true))
    }

    @Test
    fun embeddedPatchWaitsForActualHostContact() {
        assertEquals(AppleMusicConnection.WAITING_FOR_CONNECTION, resolve())
    }

    @Test
    fun unpatchedInstallAndPatchWithoutSingHaveDifferentInstructions() {
        assertEquals(AppleMusicConnection.PATCH_REQUIRED, resolve(AppleMusicInstallation(true, AppleMusicPatch.NONE)))
        assertEquals(AppleMusicConnection.SING_NOT_EMBEDDED, resolve(AppleMusicInstallation(true, AppleMusicPatch.OTHER)))
        assertEquals(AppleMusicConnection.INSTALLATION_UNREADABLE, resolve(AppleMusicInstallation(true)))
    }

    @Test
    fun companionFrameworkBindingDoesNotClaimHostConnection() {
        assertEquals(AppleMusicConnection.WAITING_FOR_CONNECTION, resolve(AppleMusicInstallation(true, AppleMusicPatch.NONE), frameworkReady = true))
    }

    @Test
    fun recentHeartbeatConfirmsConnectionForRootlessAndRootedInstalls() {
        assertEquals(AppleMusicConnection.CONNECTED, resolve(heartbeat = now - 1))
        assertEquals(AppleMusicConnection.CONNECTED, resolve(AppleMusicInstallation(true, AppleMusicPatch.NONE), now - 1))
    }

    @Test
    fun oldHeartbeatDoesNotSuddenlyBecomeModuleNotLoaded() {
        assertEquals(AppleMusicConnection.PREVIOUSLY_CONNECTED, resolve(heartbeat = now - 120_000))
        assertEquals(AppleMusicConnection.CONNECTED, resolve(heartbeat = now - 119_999))
    }

    @Test
    fun earlierInstallAndFutureTimestampsDoNotProveConnection() {
        assertEquals(AppleMusicConnection.WAITING_FOR_CONNECTION, resolve(heartbeat = 99L))
        assertEquals(AppleMusicConnection.WAITING_FOR_CONNECTION, resolve(heartbeat = now + 1))
    }

    @Test
    fun liveEngineVersionMismatchIsReported() {
        assertEquals(AppleMusicConnection.VERSION_MISMATCH, resolve(heartbeat = now - 1, version = "1.1"))
        assertEquals(AppleMusicConnection.VERSION_MISMATCH, resolve(heartbeat = now - 1, version = null))
    }

    @Test
    fun apkProbeRequiresBothLoaderAndEmbeddedModule() {
        val apk = File.createTempFile("sing-connection-", ".apk")
        try {
            fun write(vararg names: String) {
                ZipOutputStream(apk.outputStream()).use { zip ->
                    names.forEach { name -> zip.putNextEntry(ZipEntry(name)); zip.closeEntry() }
                }
            }
            write("AndroidManifest.xml")
            assertEquals(AppleMusicPatch.NONE, AppleMusicConnectionResolver.inspectApk(apk.path))
            write("assets/lspatch/config.json")
            assertEquals(AppleMusicPatch.OTHER, AppleMusicConnectionResolver.inspectApk(apk.path))
            write("assets/lspatch/config.json", "assets/lspatch/modules/org.francasio.sing.apk")
            assertEquals(AppleMusicPatch.SING_EMBEDDED, AppleMusicConnectionResolver.inspectApk(apk.path))
            apk.writeText("invalid ZIP")
            assertEquals(AppleMusicPatch.UNKNOWN, AppleMusicConnectionResolver.inspectApk(apk.path))
        } finally {
            apk.delete()
        }
    }
}
