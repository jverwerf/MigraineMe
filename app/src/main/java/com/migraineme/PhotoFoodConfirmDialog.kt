package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * One row of the photo confirm list, as the user is editing it. Starts from
 * what the model returned and stays editable until they hit Add.
 */
data class PhotoFoodDraft(
    val name: String,
    val grams: Double,
    val confidence: String,
    val alternates: List<String>,
    val checked: Boolean = true,
    /** Set once the user picks a row out of the USDA search, so saving uses
     *  exactly the food they chose instead of re-searching the name and
     *  possibly landing on a different one. */
    val fdcId: Int? = null
)

/**
 * Shows everything the model saw in one photo as a tick-list. Nothing is
 * logged until the user confirms: they can untick a food, retype its name,
 * pick one of the model's alternates, and correct the estimated portion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoFoodConfirmDialog(
    drafts: List<PhotoFoodDraft>,
    onDraftsChange: (List<PhotoFoodDraft>) -> Unit,
    mealType: String,
    onMealTypeChange: (String) -> Unit,
    isAdding: Boolean,
    addedCount: Int,
    searchResults: List<USDAFoodSearchResult>,
    isSearching: Boolean,
    onSearchQueryChange: (String) -> Unit,
    risks: Map<String, FoodRiskResult>,
    classifying: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var mealExpanded by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf(-1) }
    val mealTypes = listOf("breakfast", "lunch", "dinner", "snack")
    val scrollState = rememberScrollState()
    val checkedCount = drafts.count { it.checked }

    fun update(index: Int, transform: (PhotoFoodDraft) -> PhotoFoodDraft) {
        onDraftsChange(drafts.mapIndexed { i, d -> if (i == index) transform(d) else d })
    }

    AlertDialog(
        onDismissRequest = { if (!isAdding) onDismiss() },
        title = { Text(t("What's on the plate?"), color = AppTheme.TitleColor) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    t("These are estimates. Tap a food to change it, untick anything we got wrong, and correct the portions before adding."),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(12.dp))

                FoodRiskLegend()

                Spacer(Modifier.height(4.dp))

                drafts.forEachIndexed { index, draft ->
                    if (index > 0) {
                        HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.15f))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = draft.checked,
                            onCheckedChange = { on -> update(index) { it.copy(checked = on) } },
                            enabled = !isAdding,
                            colors = CheckboxDefaults.colors(
                                checkedColor = AppTheme.AccentPurple,
                                uncheckedColor = AppTheme.SubtleTextColor,
                                checkmarkColor = Color.White
                            ),
                            modifier = Modifier.size(36.dp)
                        )

                        Spacer(Modifier.width(4.dp))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !isAdding) {
                                    editingIndex = if (editingIndex == index) -1 else index
                                    onSearchQueryChange("")
                                }
                        ) {
                            Text(
                                draft.name.replaceFirstChar { it.uppercase() },
                                color = if (draft.checked) AppTheme.BodyTextColor else AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when (draft.confidence) {
                                        "high" -> t("Fairly sure")
                                        "medium" -> t("Portion is a guess")
                                        else -> t("Not sure, please check")
                                    },
                                    color = AppTheme.SubtleTextColor.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodySmall
                                )

                                // Same four exposure badges the search results and
                                // the barcode sheet use. Each renders nothing at
                                // "none", so a clean food shows no clutter.
                                val key = draft.name.trim().lowercase()
                                val r = risks[key]
                                if (r != null) {
                                    TyramineRiskBadge(riskLevelColor(r.tyramine), r.tyramine)
                                    AlcoholRiskBadge(riskLevelColor(r.alcohol), r.alcohol)
                                    GlutenRiskBadge(riskLevelColor(r.gluten), r.gluten)
                                    HistamineRiskBadge(riskLevelColor(r.histamine), r.histamine)
                                } else if (key in classifying) {
                                    Spacer(Modifier.width(6.dp))
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(10.dp),
                                        color = AppTheme.AccentPurple,
                                        strokeWidth = 1.5.dp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        GramsField(
                            grams = draft.grams,
                            enabled = !isAdding && draft.checked,
                            onGramsChange = { g -> update(index) { it.copy(grams = g) } }
                        )
                    }

                    // Tapping the name opens the alternates the model offered,
                    // plus a free text box for anything it did not think of.
                    if (editingIndex == index) {
                        Column(modifier = Modifier.fillMaxWidth().padding(start = 44.dp, bottom = 8.dp)) {
                            if (draft.alternates.isNotEmpty()) {
                                Text(
                                    t("Or was it…"),
                                    color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Spacer(Modifier.height(4.dp))
                                draft.alternates.forEach { alt ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(AppTheme.AccentPurple.copy(alpha = 0.14f))
                                            .clickable {
                                                // Swapping to an alternate makes the old
                                                // name an alternate in turn, so nothing
                                                // the model offered is lost.
                                                update(index) { d ->
                                                    d.copy(
                                                        name = alt,
                                                        fdcId = null,
                                                        alternates = (listOf(d.name) + d.alternates.filter { it != alt }).distinct()
                                                    )
                                                }
                                                onSearchQueryChange("")
                                                editingIndex = -1
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            alt.replaceFirstChar { it.uppercase() },
                                            color = AppTheme.AccentPurple,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }

                            OutlinedTextField(
                                value = draft.name,
                                onValueChange = { txt ->
                                    // Typing means they are no longer on the food
                                    // they picked, so drop the pin and search again.
                                    update(index) { it.copy(name = txt, fdcId = null) }
                                    onSearchQueryChange(txt)
                                },
                                placeholder = { Text(t("Type to search foods")) },
                                singleLine = true,
                                trailingIcon = {
                                    if (isSearching) {
                                        CircularProgressIndicator(
                                            Modifier.size(16.dp), AppTheme.AccentPurple, strokeWidth = 2.dp
                                        )
                                    }
                                },
                                colors = purpleFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Live USDA hits for what they are typing. Picking one
                            // pins its fdcId so the nutrients come from that exact row.
                            if (searchResults.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    t("From the food database"),
                                    color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Spacer(Modifier.height(4.dp))
                                searchResults.forEach { hit ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(AppTheme.AccentPurple.copy(alpha = 0.10f))
                                            .clickable {
                                                update(index) {
                                                    it.copy(name = hit.description, fdcId = hit.fdcId)
                                                }
                                                onSearchQueryChange("")
                                                editingIndex = -1
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Column {
                                            Text(
                                                hit.description,
                                                color = AppTheme.BodyTextColor,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            hit.brandName?.takeIf { it.isNotBlank() }?.let { brand ->
                                                Text(
                                                    brand,
                                                    color = AppTheme.SubtleTextColor,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(t("Meal Type"), color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = mealExpanded,
                    onExpandedChange = { if (!isAdding) mealExpanded = !mealExpanded }
                ) {
                    OutlinedTextField(
                        value = t(mealType.replaceFirstChar { it.uppercase() }),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mealExpanded) },
                        colors = purpleFieldColors(),
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = mealExpanded, onDismissRequest = { mealExpanded = false }) {
                        mealTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(t(type.replaceFirstChar { it.uppercase() })) },
                                onClick = { onMealTypeChange(type); mealExpanded = false }
                            )
                        }
                    }
                }

                if (isAdding) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        t("Adding %1\$s of %2\$s…", addedCount + 1, checkedCount),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isAdding && checkedCount > 0) {
                if (isAdding) {
                    CircularProgressIndicator(Modifier.size(16.dp), AppTheme.AccentPurple, strokeWidth = 2.dp)
                } else {
                    Text(
                        if (checkedCount == 1) t("Add 1 food") else t("Add %s foods", checkedCount),
                        color = AppTheme.AccentPurple
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isAdding) {
                Text(t("Cancel"), color = AppTheme.SubtleTextColor)
            }
        },
        containerColor = Color(0xFF1E0A2E)
    )
}

@Composable
private fun GramsField(grams: Double, enabled: Boolean, onGramsChange: (Double) -> Unit) {
    var text by remember(grams) {
        mutableStateOf(if (grams == grams.toLong().toDouble()) grams.toLong().toString() else String.format("%.1f", grams))
    }
    OutlinedTextField(
        value = text,
        onValueChange = { txt ->
            text = txt
            txt.replace(",", ".").toDoubleOrNull()?.takeIf { it >= 0 }?.let(onGramsChange)
        },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        trailingIcon = { Text("g", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodySmall.copy(color = AppTheme.TitleColor),
        colors = purpleFieldColors(),
        modifier = Modifier.width(96.dp)
    )
}

@Composable
private fun purpleFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppTheme.AccentPurple,
    unfocusedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
    focusedTextColor = AppTheme.TitleColor,
    unfocusedTextColor = AppTheme.TitleColor,
    cursorColor = AppTheme.AccentPurple,
    focusedPlaceholderColor = AppTheme.SubtleTextColor,
    unfocusedPlaceholderColor = AppTheme.SubtleTextColor
)
