package com.migraineme

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun NutritionHistoryScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val scope = rememberCoroutineScope()
    
    // Use rememberSaveable for date to survive config changes
    var selectedDateStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val selectedDate = LocalDate.parse(selectedDateStr)
    
    var items by remember { mutableStateOf<List<NutritionLogItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Edit dialog state
    var editingItem by remember { mutableStateOf<NutritionLogItem?>(null) }
    var editMealType by remember { mutableStateOf("lunch") }
    
    val searchService = remember { USDAFoodSearchService(context) }
    
    val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")
    val today = LocalDate.now()
    
    fun loadItems() {
        scope.launch {
            isLoading = true
            items = searchService.getNutritionItemsForDate(selectedDate.toString())
            isLoading = false
        }
    }
    
    // Load items when date changes - but don't reset scroll
    LaunchedEffect(selectedDateStr) {
        loadItems()
    }
    
    // Edit dialog
    if (editingItem != null) {
        HistoryEditFoodDialog(
            item = editingItem!!,
            mealType = editMealType,
            onMealTypeChange = { editMealType = it },
            onDismiss = { editingItem = null },
            onSave = { servingsMultiplier ->
                scope.launch {
                    val success = searchService.updateNutritionItem(
                        id = editingItem!!.id,
                        mealType = editMealType,
                        servingsMultiplier = if (servingsMultiplier != 1.0) servingsMultiplier else null
                    )
                    if (success) {
                        editingItem = null
                        loadItems()
                    }
                }
            },
            onDelete = {
                scope.launch {
                    searchService.deleteNutritionItem(editingItem!!.id)
                    editingItem = null
                    loadItems()
                }
            }
        )
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
            // Back navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }
            
            // Header
            HeroCard {
                Text(
                    "Nutrition History",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "View and manage your food log",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            // Date navigation
            BaseCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { selectedDateStr = selectedDate.minusDays(1).toString() }
                    ) {
                        Icon(
                            Icons.Default.ChevronLeft,
                            contentDescription = "Previous day",
                            tint = AppTheme.AccentPurple
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when {
                                selectedDate == today -> "Today"
                                selectedDate == today.minusDays(1) -> "Yesterday"
                                else -> selectedDate.format(dateFormatter)
                            },
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        if (selectedDate != today && selectedDate != today.minusDays(1)) {
                            Text(
                                selectedDate.format(DateTimeFormatter.ofPattern("yyyy")),
                                color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    
                    IconButton(
                        onClick = { selectedDateStr = selectedDate.plusDays(1).toString() },
                        enabled = selectedDate < today
                    ) {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "Next day",
                            tint = if (selectedDate < today) AppTheme.AccentPurple else AppTheme.SubtleTextColor.copy(alpha = 0.3f)
                        )
                    }
                }
            }
            
            // Items for selected date
            BaseCard {
                if (isLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = AppTheme.AccentPurple,
                            strokeWidth = 2.dp
                        )
                    }
                } else if (items.isEmpty()) {
                    Text(
                        "No food logged for this day",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    )
                } else {
                    // Group by meal type
                    val grouped = items.groupBy { it.mealType }
                    val mealOrder = listOf("breakfast", "lunch", "dinner", "snack", "unknown")
                    
                    var totalCalories = 0.0
                    items.forEach { item -> item.calories?.let { totalCalories += it } }
                    
                    // Summary row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${items.size} items",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "${totalCalories.toInt()} cal total",
                            color = Color(0xFFFFB74D),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                        )
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    
                    mealOrder.forEach { mealType ->
                        val mealItems = grouped[mealType] ?: return@forEach
                        
                        Text(
                            mealType.replaceFirstChar { it.uppercase() },
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        
                        Spacer(Modifier.height(4.dp))
                        
                        mealItems.forEach { item ->
                            HistoryLogItem(
                                item = item,
                                onEdit = if (item.source == "manual_usda") {
                                    {
                                        editingItem = item
                                        editMealType = item.mealType
                                    }
                                } else null,
                                onDelete = if (item.source == "manual_usda") {
                                    {
                                        scope.launch {
                                            searchService.deleteNutritionItem(item.id)
                                            loadItems()
                                        }
                                    }
                                } else null
                            )
                        }
                        
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryLogItem(
    item: NutritionLogItem,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.foodName,
                color = AppTheme.BodyTextColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2
            )
            Row {
                item.calories?.let {
                    Text(
                        "${it.toInt()} cal",
                        color = Color(0xFFFFB74D),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (item.source == "manual_usda") {
                    Text(
                        " • Manual",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (item.source.isNotBlank()) {
                    Text(
                        " • ${item.source}",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        
        if (onEdit != null) {
            Text(
                "✎",
                color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .clickable { onEdit() }
                    .padding(8.dp)
            )
        }
        
        if (onDelete != null) {
            Text(
                "✕",
                color = Color(0xFFE57373),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .clickable { onDelete() }
                    .padding(8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryEditFoodDialog(
    item: NutritionLogItem,
    mealType: String,
    onMealTypeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val mealTypes = listOf("breakfast", "lunch", "dinner", "snack")
    var servingsMultiplier by remember { mutableStateOf(1.0) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Food", color = AppTheme.TitleColor) },
        text = {
            Column {
                Text(
                    item.foodName,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                )
                
                item.calories?.let {
                    val adjustedCalories = (it * servingsMultiplier).toInt()
                    Text(
                        "$adjustedCalories calories${if (servingsMultiplier != 1.0) " (was ${it.toInt()})" else ""}",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                Spacer(Modifier.height(16.dp))
                
                Text("Adjust Servings", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "−",
                        color = if (servingsMultiplier > 0.5) AppTheme.AccentPurple else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .clickable(enabled = servingsMultiplier > 0.5) { servingsMultiplier = (servingsMultiplier - 0.5).coerceAtLeast(0.5) }
                            .padding(horizontal = 12.dp)
                    )
                    
                    Text(
                        "${if (servingsMultiplier == servingsMultiplier.toLong().toDouble()) servingsMultiplier.toLong() else String.format("%.1f", servingsMultiplier)}x",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    
                    Text(
                        "+",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .clickable { servingsMultiplier += 0.5 }
                            .padding(horizontal = 12.dp)
                    )
                }
                
                Spacer(Modifier.height(16.dp))
                
                Text("Meal Type", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = mealType.replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                            focusedTextColor = AppTheme.TitleColor,
                            unfocusedTextColor = AppTheme.TitleColor
                        ),
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        mealTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.replaceFirstChar { it.uppercase() }) },
                                onClick = { onMealTypeChange(type); expanded = false }
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                Text(
                    "Delete this entry",
                    color = Color(0xFFE57373),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { onDelete() }.padding(vertical = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(servingsMultiplier) }) {
                Text("Save", color = AppTheme.AccentPurple)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppTheme.SubtleTextColor)
            }
        },
        containerColor = Color(0xFF1E0A2E)
    )
}
