package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.Image

/**
 * Language picker. Used from the drawer and, in a compact form, on the first
 * onboarding page (see LanguagePickerRow) where it has to work before an account
 * exists.
 *
 * Each language is listed in its OWN language — a user who has landed in the
 * wrong one cannot read the list otherwise, which is the whole reason they are
 * on this screen. So no translation of these labels, ever.
 *
 * Changing the selection recomposes every visible screen immediately, because
 * t() reads LangPrefs as state. There is no restart and no activity recreate.
 */
@Composable
fun LanguageScreen() {
    val current by LangPrefs.lang.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(AppTheme.FadeColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            t("Your migraine data is not affected by this."),
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(16.dp))

        Lang.entries.forEach { lang ->
            LanguageRow(
                lang = lang,
                selected = lang == current,
                onClick = { LangPrefs.set(lang) }
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LanguageRow(lang: Lang, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) AppTheme.AccentPurple.copy(alpha = 0.20f)
                else Color.White.copy(alpha = 0.04f),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            lang.endonym,
            color = if (selected) AppTheme.TitleColor else AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        )
        if (selected) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = AppTheme.AccentPurple)
        }
    }
}

/**
 * Compact language picker for the sign-in and onboarding screens.
 *
 * A dropdown rather than a row of chips: seven languages wrap onto two rows and
 * dominate a screen whose job is signing in. Collapsed, it is one control
 * showing the current language.
 *
 * Entries are listed in their OWN language, because someone stranded in a
 * language they cannot read has to recognise their own by sight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerRow() {
    val current by LangPrefs.lang.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = current.endonym,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AppTheme.TitleColor,
                unfocusedTextColor = AppTheme.TitleColor,
                focusedBorderColor = AppTheme.AccentPurple.copy(alpha = 0.6f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.18f),
                focusedTrailingIconColor = AppTheme.AccentPurple,
                unfocusedTrailingIconColor = AppTheme.SubtleTextColor,
                focusedContainerColor = Color.White.copy(alpha = 0.04f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
            ),
            shape = RoundedCornerShape(14.dp),
            // 52.dp and a 14.dp corner match AuthButton on the sign-in screen,
            // so the language control reads as one of the same stack of cards
            // rather than a form field that wandered in.
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .height(52.dp)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(AppTheme.FadeColor)
        ) {
            Lang.entries.forEach { lang ->
                DropdownMenuItem(
                    text = {
                        Text(
                            lang.endonym,
                            color = if (lang == current) AppTheme.TitleColor else AppTheme.BodyTextColor,
                            fontWeight = if (lang == current) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    trailingIcon = {
                        if (lang == current) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = AppTheme.AccentPurple)
                        }
                    },
                    onClick = {
                        LangPrefs.set(lang)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Compact flag button for the onboarding hero, where the full-width dropdown
 * pushed the whole page down and undid the layout work. Same picker, one tap,
 * no vertical cost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageFlagButton() {
    val current by LangPrefs.lang.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            color = Color.White.copy(alpha = 0.14f),
            shape = CircleShape,
            modifier = Modifier.clickable { expanded = true }
        ) {
            Text(
                current.flag,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(AppTheme.FadeColor)
        ) {
            Lang.entries.forEach { lang ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${lang.flag}  ${lang.endonym}",
                            color = if (lang == current) AppTheme.TitleColor else AppTheme.BodyTextColor,
                            fontWeight = if (lang == current) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        LangPrefs.set(lang)
                        expanded = false
                    }
                )
            }
        }
    }
}
