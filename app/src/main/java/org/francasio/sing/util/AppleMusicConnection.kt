package org.francasio.sing.util

import java.io.IOException
import java.util.zip.ZipFile

enum class AppleMusicConnection {
    CHECKING,
    NOT_INSTALLED,
    INSTALLATION_UNREADABLE,
    PATCH_REQUIRED,
    SING_NOT_EMBEDDED,
    WAITING_FOR_CONNECTION,
    CONNECTED,
    PREVIOUSLY_CONNECTED,
    VERSION_MISMATCH
}

internal enum class AppleMusicPatch { NONE, SING_EMBEDDED, OTHER, UNKNOWN }

internal data class AppleMusicInstallation(
    val installed: Boolean,
    val patch: AppleMusicPatch = AppleMusicPatch.UNKNOWN,
    val lastUpdateTime: Long = 0L
)

/** Keep installation evidence separate from a confirmed connection to the host. */
internal object AppleMusicConnectionResolver {
    fun inspectApk(path: String): AppleMusicPatch = try {
        ZipFile(path).use { zip ->
            when {
                zip.getEntry("assets/lspatch/config.json") == null -> AppleMusicPatch.NONE
                zip.getEntry("assets/lspatch/modules/org.francasio.sing.apk") != null -> AppleMusicPatch.SING_EMBEDDED
                else -> AppleMusicPatch.OTHER
            }
        }
    } catch (_: IOException) {
        AppleMusicPatch.UNKNOWN
    } catch (_: SecurityException) {
        AppleMusicPatch.UNKNOWN
    }

    fun resolve(
        installation: AppleMusicInstallation,
        heartbeatTime: Long,
        engineVersion: String?,
        appVersion: String,
        frameworkReady: Boolean,
        now: Long = System.currentTimeMillis()
    ): AppleMusicConnection {
        if (!installation.installed) return AppleMusicConnection.NOT_INSTALLED
        // A heartbeat from an older installation or a future clock is not evidence
        // that the currently installed Apple Music has loaded our engine.
        val hasHeartbeat = heartbeatTime > 0L && heartbeatTime >= installation.lastUpdateTime && heartbeatTime <= now
        if (hasHeartbeat && now - heartbeatTime < 120_000L) {
            return if (engineVersion != appVersion) AppleMusicConnection.VERSION_MISMATCH else AppleMusicConnection.CONNECTED
        }
        if (hasHeartbeat) return AppleMusicConnection.PREVIOUSLY_CONNECTED
        // Rooted installs can legitimately use an unmodified Apple Music APK.
        // A bound framework in the companion alone does not prove host activation.
        if (frameworkReady) return AppleMusicConnection.WAITING_FOR_CONNECTION
        return when (installation.patch) {
            AppleMusicPatch.SING_EMBEDDED -> AppleMusicConnection.WAITING_FOR_CONNECTION
            AppleMusicPatch.OTHER -> AppleMusicConnection.SING_NOT_EMBEDDED
            AppleMusicPatch.NONE -> AppleMusicConnection.PATCH_REQUIRED
            AppleMusicPatch.UNKNOWN -> AppleMusicConnection.INSTALLATION_UNREADABLE
        }
    }
}
