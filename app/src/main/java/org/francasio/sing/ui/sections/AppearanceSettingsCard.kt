package org.francasio.sing.ui.sections

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.francasio.sing.R
import org.francasio.sing.ui.components.GroupedSection
import org.francasio.sing.ui.components.SettingSwitchRow

@Composable
fun AppearanceSettingsCard(
    appleRedTint: Boolean,
    liquidGlass: Boolean,
    onAppleRedTintChange: (Boolean) -> Unit,
    onLiquidGlassChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    GroupedSection(
        title = stringResource(R.string.title_apple_music_appearance),
        modifier = modifier
    ) {
        item {
            SettingSwitchRow(
                icon = painterResource(R.drawable.ic_palette),
                title = stringResource(R.string.setting_apple_red_tint_title),
                description = stringResource(R.string.setting_apple_red_tint_desc),
                checked = appleRedTint,
                onCheckedChange = onAppleRedTintChange
            )
        }
        item {
            SettingSwitchRow(
                icon = painterResource(R.drawable.ic_layers_3),
                title = stringResource(R.string.setting_liquid_glass_title),
                description = stringResource(R.string.setting_liquid_glass_desc),
                checked = liquidGlass,
                onCheckedChange = onLiquidGlassChange
            )
        }
    }
}
