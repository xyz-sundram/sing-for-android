package org.francasio.sing.ui.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.francasio.sing.R
import org.francasio.sing.ui.components.GroupedSection
import org.francasio.sing.ui.components.StatusInfoRow
import org.francasio.sing.ui.theme.*
import org.francasio.sing.util.ModelLoader
import org.francasio.sing.util.AppleMusicConnection

@Composable
fun StatusCard(
    connectionStatus: AppleMusicConnection,
    appVersion: String = "",
    moduleVersion: String = "",
    selectedModelId: String,
    currentModel: ModelLoader.ModelInfo?,
    threadCount: Int,
    onNavigateToModelSelection: (scrollToSelected: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isMaterialYou = LocalIsMaterialYou.current
    val isConnected = connectionStatus == AppleMusicConnection.CONNECTED
    val isVersionMismatch = connectionStatus == AppleMusicConnection.VERSION_MISMATCH
    val isWarning = !isConnected && connectionStatus != AppleMusicConnection.CHECKING
    val title = when (connectionStatus) {
        AppleMusicConnection.CHECKING -> R.string.connection_checking
        AppleMusicConnection.NOT_INSTALLED -> R.string.connection_not_installed
        AppleMusicConnection.INSTALLATION_UNREADABLE -> R.string.connection_unreadable
        AppleMusicConnection.PATCH_REQUIRED -> R.string.connection_patch_required
        AppleMusicConnection.SING_NOT_EMBEDDED -> R.string.connection_sing_not_embedded
        AppleMusicConnection.WAITING_FOR_CONNECTION -> R.string.connection_waiting
        AppleMusicConnection.CONNECTED -> R.string.connection_connected
        AppleMusicConnection.PREVIOUSLY_CONNECTED -> R.string.connection_previous
        AppleMusicConnection.VERSION_MISMATCH -> R.string.connection_version_mismatch
    }
    val description = when (connectionStatus) {
        AppleMusicConnection.CHECKING -> R.string.connection_checking_desc
        AppleMusicConnection.NOT_INSTALLED -> R.string.connection_not_installed_desc
        AppleMusicConnection.INSTALLATION_UNREADABLE -> R.string.connection_unreadable_desc
        AppleMusicConnection.PATCH_REQUIRED -> R.string.connection_patch_required_desc
        AppleMusicConnection.SING_NOT_EMBEDDED -> R.string.connection_sing_not_embedded_desc
        AppleMusicConnection.WAITING_FOR_CONNECTION -> R.string.connection_waiting_desc
        AppleMusicConnection.CONNECTED -> R.string.connection_connected_desc
        AppleMusicConnection.PREVIOUSLY_CONNECTED -> R.string.connection_previous_desc
        AppleMusicConnection.VERSION_MISMATCH -> R.string.connection_version_mismatch_desc
    }

    GroupedSection(title = null, modifier = modifier) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (isWarning) {
                                   if (isMaterialYou) DefaultYellowError else DefaultYellowError.copy(alpha = 0.2f)
                                } else if (isConnected) {
                                    if (isMaterialYou) Primary else TextPrimary.copy(alpha = 0.15f)
                                } else {
                                    TextSecondary.copy(alpha = 0.15f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = if (isWarning) painterResource(id = R.drawable.ic_warning_sign)
                            else if (isConnected) painterResource(id = R.drawable.ic_checkmark_circle) else painterResource(id = R.drawable.ic_music_app_icon),
                            contentDescription = null,
                            tint = if (isWarning) {
                                if (isMaterialYou) Accent2_900 else DefaultYellowError
                            } else if (isConnected) {
                                if (isMaterialYou) Accent2_900 else TextPrimary
                            } else {
                                TextSecondary
                            },
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(id = title),
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (isConnected || isVersionMismatch) {
                            Text(
                                text = if (isVersionMismatch) {
                                    stringResource(id = R.string.module_version_label, appVersion, moduleVersion)
                                } else {
                                    stringResource(R.string.connection_engine_version, moduleVersion)
                                },
                                color = TextSecondary,
                                fontSize = 12.5.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(id = description),
                    color = TextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp
                )
            }
        }
        item {
            Column(modifier = Modifier.padding(18.dp)) {
                StatusInfoRow(
                    label = stringResource(id = R.string.label_neural_model),
                    value = when {
                        selectedModelId.isEmpty() -> stringResource(id = R.string.status_no_model_selected)
                        currentModel != null -> stringResource(
                            id = R.string.status_model_format,
                            "${currentModel.modelDisplayName} (${currentModel.displayName})",
                            threadCount
                        )
                        else -> stringResource(id = R.string.status_model_format, selectedModelId, threadCount)
                    },
                    onClick = { onNavigateToModelSelection(true) }
                )
            }
        }
    }
}
