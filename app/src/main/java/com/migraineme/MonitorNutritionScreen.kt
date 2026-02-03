package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@Composable
fun MonitorNutritionScreen(
    navController: NavController,
    authVm: AuthViewModel
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    
    val searchService = remember { USDAFoodSearchService(context) }
    val config = remember { MonitorCardConfigStore.load(context) }
    
    // Today's logged items
    var todayItems by remember { mutableStateOf<List<NutritionLogItem>>(emptyList()) }
    var isLoadingToday by remember { mutableStateOf(true) }
    
    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<USDAFoodSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    
    // Add food dialog state
    var selectedFood by remember { mutableStateOf<USDAFoodSearchResult?>(null) }
    var selectedFoodDetails by remember { mutableStateOf<USDAFoodDetailsFull?>(null) }
    var isLoadingDetails by remember { mutableStateOf(false) }
    var selectedMealType by remember { mutableStateOf("lunch") }
    var selectedServings by remember { mutableStateOf(1.0) }
    var isAdding by remember { mutableStateOf(false) }
    var addSuccess by remember { mutableStateOf<String?>(null) }
    var addError by remember { mutableStateOf<String?>(null) }
    
    // Edit dialog state
    var editingItem by remember { mutableStateOf<NutritionLogItem?>(null) }
    var editMealType by remember { mutableStateOf("lunch") }
    
    // Load today's items
    LaunchedEffect(Unit) {
        scope.launch {
            todayItems = searchService.getTodayNutritionItems()
            isLoadingToday = false
        }
    }
    
    fun reloadTodayItems() {
        scope.launch { todayItems = searchService.getTodayNutritionItems() }
    }
    
    fun performSearch() {
        if (searchQuery.isBlank()) return
        focusManager.clearFocus()
        scope.launch {
            isSearching = true
            searchError = null
            searchResults = searchService.searchFoods(searchQuery)
            if (searchResults.isEmpty()) searchError = "No foods found for \"$searchQuery\""
            isSearching = false
        }
    }
    
    fun selectFood(food: USDAFoodSearchResult) {
        selectedFood = food
        selectedFoodDetails = null
        selectedServings = 1.0
        isLoadingDetails = true
        scope.launch {
            selectedFoodDetails = searchService.getFoodDetails(food.fdcId)
            isLoadingDetails = false
        }
    }
    
    // Dialogs
    if (selectedFood != null) {
        AddFoodDialog(
            food = selectedFood!!,
            foodDetails = selectedFoodDetails,
            isLoadingDetails = isLoadingDetails,
            mealType = selectedMealType,
            onMealTypeChange = { selectedMealType = it },
            servings = selectedServings,
            onServingsChange = { selectedServings = it },
            isAdding = isAdding,
            monitorMetrics = config.nutritionDisplayMetrics,
            onDismiss = { selectedFood = null; selectedFoodDetails = null },
            onConfirm = {
                scope.launch {
                    isAdding = true
                    addError = null
                    val (success, errorMsg) = if (selectedFoodDetails != null) {
                        searchService.addFoodFromDetails(
                            foodDetails = selectedFoodDetails!!,
                            foodName = selectedFood!!.description,
                            mealType = selectedMealType,
                            servings = selectedServings
                        )
                    } else Pair(false, "No food details loaded")
                    isAdding = false
                    if (success) {
                        addSuccess = selectedFood!!.description
                        selectedFood = null
                        selectedFoodDetails = null
                        searchResults = emptyList()
                        searchQuery = ""
                        reloadTodayItems()
                    } else {
                        addError = errorMsg ?: "Failed to add food"
                    }
                }
            }
        )
    }
    
    if (addSuccess != null) {
        AlertDialog(
            onDismissRequest = { addSuccess = null },
            title = { Text("Food Added!", color = AppTheme.TitleColor) },
            text = { Text("$addSuccess has been added to your nutrition log.", color = AppTheme.BodyTextColor) },
            confirmButton = { TextButton(onClick = { addSuccess = null }) { Text("OK", color = AppTheme.AccentPurple) } },
            containerColor = Color(0xFF1E0A2E)
        )
    }
    
    if (addError != null) {
        AlertDialog(
            onDismissRequest = { addError = null },
            title = { Text("Error", color = Color(0xFFE57373)) },
            text = { Text(addError!!, color = AppTheme.BodyTextColor) },
            confirmButton = { TextButton(onClick = { addError = null }) { Text("OK", color = AppTheme.AccentPurple) } },
            containerColor = Color(0xFF1E0A2E)
        )
    }
    
    if (editingItem != null) {
        EditFoodDialog(
            item = editingItem!!,
            mealType = editMealType,
            onMealTypeChange = { editMealType = it },
            onDismiss = { editingItem = null },
            onSave = { multiplier ->
                scope.launch {
                    val success = searchService.updateNutritionItem(
                        id = editingItem!!.id,
                        mealType = editMealType,
                        servingsMultiplier = if (multiplier != 1.0) multiplier else null
                    )
                    if (success) { editingItem = null; reloadTodayItems() }
                }
            },
            onDelete = {
                scope.launch {
                    searchService.deleteNutritionItem(editingItem!!.id)
                    editingItem = null
                    reloadTodayItems()
                }
            }
        )
    }

    // UI
    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
            // Back
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
            }
            
         

            HeroCard(modifier = Modifier.clickable { navController.navigate(Routes.NUTRITION_CONFIG) }) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Tune, "Configure", tint = AppTheme.AccentPurple, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Customize Display", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text("Choose metrics to show on Monitor", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                }
            }
            BaseCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Certain foods and nutrients can trigger migraines. Tracking caffeine, sugar, tyramine, and sodium helps identify patterns.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            
            // Search
            BaseCard {
                Text("Add Food", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(4.dp))
                Text("Search USDA database to log food", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it; if (it.isBlank()) { searchResults = emptyList(); searchError = null } },
                    placeholder = { Text("Search foods") },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = AppTheme.SubtleTextColor) },
                    trailingIcon = { if (isSearching) CircularProgressIndicator(Modifier.size(20.dp), AppTheme.AccentPurple, strokeWidth = 2.dp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { performSearch() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                        focusedTextColor = AppTheme.TitleColor,
                        unfocusedTextColor = AppTheme.TitleColor,
                        cursorColor = AppTheme.AccentPurple,
                        focusedPlaceholderColor = AppTheme.SubtleTextColor,
                        unfocusedPlaceholderColor = AppTheme.SubtleTextColor
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                
                searchError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFE57373), style = MaterialTheme.typography.bodySmall)
                }
            }
            
            // Results
            if (searchResults.isNotEmpty()) {
                BaseCard {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Search Results", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text("✕", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable { searchResults = emptyList() })
                    }
                    Spacer(Modifier.height(8.dp))
                    searchResults.forEach { food -> FoodSearchResultItem(food) { selectFood(food) } }
                }
            }
            
            // Today's Log
            BaseCard {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { navController.navigate(Routes.NUTRITION_HISTORY) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Today's Log", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text("History →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                
                when {
                    isLoadingToday -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), AppTheme.AccentPurple, strokeWidth = 2.dp)
                    }
                    todayItems.isEmpty() -> Text("No food logged today", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    else -> todayItems.forEach { item ->
                        TodayLogItem(
                            item = item,
                            onEdit = if (item.source == "manual_usda") {{ editingItem = item; editMealType = item.mealType }} else null,
                            onDelete = if (item.source == "manual_usda") {{ scope.launch { searchService.deleteNutritionItem(item.id); reloadTodayItems() } }} else null
                        )
                    }
                }
            }
            
            // History Graph
            NutritionHistoryGraph(days = 14)
        }
    }
}
