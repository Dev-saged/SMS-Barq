package dev.saged.barq.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.saged.barq.R
import dev.saged.barq.ui.icons.BIcons
import dev.saged.barq.util.LocaleHelper

/** Language picker: "phone language" first, then each language by its own name beside its flag. */
@Composable
fun LanguageDialog(chosen: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LanguageRow(stringResource(R.string.language_system), chosen == null, { onPick(null) }) {
                    Box(Modifier.width(FLAG_W), contentAlignment = Alignment.Center) {
                        Icon(BIcons.Language, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                LocaleHelper.languages.forEach { lang ->
                    LanguageRow(lang.nativeName, chosen == lang.tag, { onPick(lang.tag) }) {
                        val shape = RoundedCornerShape(4.dp)
                        Image(
                            painterResource(lang.flag), null,
                            Modifier
                                .size(FLAG_W, 20.dp)
                                .clip(shape)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val FLAG_W = 30.dp

@Composable
private fun LanguageRow(label: String, selected: Boolean, onClick: () -> Unit, leading: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (selected) Icon(BIcons.Check, null, Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
