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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun MonitorNutritionScreen(
    navController: NavController,
    authVm: AuthViewModel
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val authState by authVm.state.collectAsState()
    
    val searchService = remember { USDAFoodSearchService(context) }
    val offService = remember { OpenFoodFactsService() }

    // Display metrics from shared MetricRegistry
    val displayKeys = remember { MetricDisplayStore.getDisplayMetrics(context, "nutrition") }
    
    // Today's logged items
    var todayItems by remember { mutableStateOf<List<NutritionLogItem>>(emptyList()) }
    var isLoadingToday by remember { mutableStateOf(true) }
    
    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<USDAFoodSearchResult>>(emptyList()) }
    var foodRisks by remember { mutableStateOf<Map<Int, FoodRiskResult>>(emptyMap()) }
    var isClassifyingSearchRisks by remember { mutableStateOf(false) }
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
    var selectedFoodRisks by remember { mutableStateOf<FoodRiskResult?>(null) }
    var isClassifyingRisks by remember { mutableStateOf(false) }
    
    // Edit dialog state
    var editingItem by remember { mutableStateOf<NutritionLogItem?>(null) }
    var editMealType by remember { mutableStateOf("lunch") }

    // Barcode scan state
    var scannedProduct by remember { mutableStateOf<OFFProduct?>(null) }
    var scanGrams by remember { mutableStateOf(100.0) }
    var scanMealType by remember { mutableStateOf("lunch") }
    var isLookingUpBarcode by remember { mutableStateOf(false) }
    var barcodeNotFound by remember { mutableStateOf<String?>(null) }
    var isAddingScan by remember { mutableStateOf(false) }
    var scanRisks by remember { mutableStateOf<FoodRiskResult?>(null) }
    var isClassifyingScanRisks by remember { mutableStateOf(false) }
    
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

    // ── Auto-type "red wine" during tour to showcase tyramine classification ──
    LaunchedEffect(Unit) {
        if (TourManager.isActive()) {
            kotlinx.coroutines.delay(800L)
            val demoQuery = "red wine"
            for (i in demoQuery.indices) {
                searchQuery = demoQuery.substring(0, i + 1)
                kotlinx.coroutines.delay(80L)
            }
            kotlinx.coroutines.delay(400L)
            // Trigger search
            isSearching = true
            searchError = null
            foodRisks = emptyMap()
            searchResults = searchService.searchFoods(searchQuery)
            if (searchResults.isEmpty()) searchError = "No foods found for \"$searchQuery\""
            isSearching = false
            // Auto-classify risks for results
            val token = authState.accessToken
            if (token != null && searchResults.isNotEmpty()) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    isClassifyingSearchRisks = true
                    val classifier = FoodRiskClassifierService()
                    val risks = mutableMapOf<Int, FoodRiskResult>()
                    for (food in searchResults) {
                        try { risks[food.fdcId] = classifier.classify(token, food.description) } catch (_: Exception) {}
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { foodRisks = risks; isClassifyingSearchRisks = false }
                }
            }
        }
    }
    
    fun performSearch() {
        if (searchQuery.isBlank()) return
        focusManager.clearFocus()
        scope.launch {
            isSearching = true
            searchError = null
            foodRisks = emptyMap()
            searchResults = searchService.searchFoods(searchQuery)
            if (searchResults.isEmpty()) searchError = "No foods found for \"$searchQuery\""
            isSearching = false

            // Classify food risks in background on IO thread
            if (searchResults.isNotEmpty()) {
                isClassifyingSearchRisks = true
                val classifier = FoodRiskClassifierService()
                val token = authState.accessToken ?: run { isClassifyingSearchRisks = false; return@launch }
                val risks = mutableMapOf<Int, FoodRiskResult>()
                searchResults.forEach { food ->
                    try {
                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            classifier.classify(token, food.description)
                        }
                        risks[food.fdcId] = result
                        foodRisks = risks.toMap()
                    } catch (e: Exception) {
                        android.util.Log.e("NutritionScreen", "Risk classify failed: ${e.message}", e)
                    }
                }
                isClassifyingSearchRisks = false
            }
        }
    }
    
    fun startBarcodeScan() {
        scope.launch {
            try {
                val options = GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(
                        Barcode.FORMAT_EAN_13,
                        Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A,
                        Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_CODE_128,
                        Barcode.FORMAT_CODE_39
                    )
                    .build()
                val scanner = GmsBarcodeScanning.getClient(context, options)
                val result = scanner.startScan().await()
                val raw = result.rawValue ?: return@launch

                isLookingUpBarcode = true
                val product = offService.fetchProduct(raw)
                isLookingUpBarcode = false

                if (product == null) {
                    barcodeNotFound = raw
                    return@launch
                }

                scannedProduct = product
                scanGrams = product.servingQuantityGrams ?: 100.0
                scanRisks = null
                isClassifyingScanRisks = true

                val token = authState.accessToken
                if (token != null) {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val displayName = listOfNotNull(product.brand, product.name).joinToString(" ").ifBlank { product.name }
                        val classified = try {
                            FoodRiskClassifierService().classify(token, displayName)
                        } catch (_: Exception) { FoodRiskResult() }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            scanRisks = classified
                            isClassifyingScanRisks = false
                        }
                    }
                } else {
                    isClassifyingScanRisks = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                isLookingUpBarcode = false
                android.util.Log.e("NutritionScreen", "Barcode scan failed: ${e.message}", e)
            }
        }
    }

    fun selectFood(food: USDAFoodSearchResult) {
        selectedFood = food
        selectedFoodDetails = null
        selectedServings = 1.0
        isLoadingDetails = true
        selectedFoodRisks = null
        isClassifyingRisks = true
        scope.launch {
            selectedFoodDetails = searchService.getFoodDetails(food.fdcId)
            isLoadingDetails = false
        }
        // Check if we already have risks from search results
        val cached = foodRisks[food.fdcId]
        if (cached != null) {
            selectedFoodRisks = cached
            isClassifyingRisks = false
        } else {
            scope.launch {
                try {
                    val token = authState.accessToken ?: return@launch
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        FoodRiskClassifierService().classify(token, food.description)
                    }
                    selectedFoodRisks = result
                } catch (e: Exception) {
                    selectedFoodRisks = FoodRiskResult()
                    android.util.Log.e("NutritionScreen", "Risk classify failed: ${e.message}")
                } finally {
                    isClassifyingRisks = false
                }
            }
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
            monitorMetrics = displayKeys.map { MetricRegistry.nutritionLegacyKey(it) },
            tyramineRisk = selectedFoodRisks?.tyramine,
            alcoholRisk = selectedFoodRisks?.alcohol,
            glutenRisk = selectedFoodRisks?.gluten,
            histamineRisk = selectedFoodRisks?.histamine,
            isClassifyingRisks = isClassifyingRisks,
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
    
    if (scannedProduct != null) {
        BarcodeAddFoodDialog(
            product = scannedProduct!!,
            grams = scanGrams,
            onGramsChange = { scanGrams = it },
            mealType = scanMealType,
            onMealTypeChange = { scanMealType = it },
            isAdding = isAddingScan,
            monitorMetrics = displayKeys.map { MetricRegistry.nutritionLegacyKey(it) },
            tyramineRisk = scanRisks?.tyramine,
            alcoholRisk = scanRisks?.alcohol,
            glutenRisk = scanRisks?.gluten,
            histamineRisk = scanRisks?.histamine,
            isClassifyingRisks = isClassifyingScanRisks,
            onDismiss = { scannedProduct = null; scanRisks = null },
            onConfirm = {
                scope.launch {
                    isAddingScan = true
                    val (success, errorMsg) = searchService.addFoodFromOFF(
                        product = scannedProduct!!,
                        gramsConsumed = scanGrams,
                        mealType = scanMealType
                    )
                    isAddingScan = false
                    if (success) {
                        addSuccess = scannedProduct!!.name
                        scannedProduct = null
                        scanRisks = null
                        reloadTodayItems()
                    } else {
                        addError = errorMsg ?: "Failed to add scanned food"
                    }
                }
            }
        )
    }

    if (barcodeNotFound != null) {
        AlertDialog(
            onDismissRequest = { barcodeNotFound = null },
            title = { Text("Product not found", color = AppTheme.TitleColor) },
            text = {
                Text(
                    "Barcode ${barcodeNotFound} is not in the Open Food Facts database. Try searching by name instead.",
                    color = AppTheme.BodyTextColor
                )
            },
            confirmButton = {
                TextButton(onClick = { barcodeNotFound = null }) {
                    Text("OK", color = AppTheme.AccentPurple)
                }
            },
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
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
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
            // Search
            BaseCard {
                Text("Add Food", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(4.dp))
                Text("Search USDA database or scan a barcode", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                        modifier = Modifier.weight(1f)
                    )

                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppTheme.AccentPurple.copy(alpha = 0.18f))
                            .clickable(enabled = !isLookingUpBarcode) { startBarcodeScan() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLookingUpBarcode) {
                            CircularProgressIndicator(Modifier.size(20.dp), AppTheme.AccentPurple, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Outlined.QrCodeScanner,
                                contentDescription = "Scan barcode",
                                tint = AppTheme.AccentPurple,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

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
                    searchResults.forEach { food ->
                        FoodSearchResultItem(
                            food = food,
                            foodRisks = foodRisks[food.fdcId],
                            isClassifyingRisks = isClassifyingSearchRisks && foodRisks[food.fdcId] == null,
                            onClick = { selectFood(food) }
                        )
                    }
                }
            }
            
            // Today's Log
            BaseCard {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { if (PremiumManager.isPremium) navController.navigate(Routes.NUTRITION_HISTORY) else navController.navigate(Routes.PAYWALL) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Today's Log", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    if (PremiumManager.isPremium) { Text("History →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall) } else { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Icon(Icons.Outlined.Lock, contentDescription = "Premium", tint = AppTheme.AccentPurple, modifier = Modifier.size(14.dp)); Text("History", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall) } }
                }
                Spacer(Modifier.height(8.dp))
                
                when {
                    isLoadingToday -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), AppTheme.AccentPurple, strokeWidth = 2.dp)
                    }
                    todayItems.isEmpty() -> Text("No food logged today", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    else -> {
                        val selectedMetrics = displayKeys.take(3)
                        val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))

                        // Top 3 selected metrics
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            selectedMetrics.forEachIndexed { index, registryKey ->
                                val legacyKey = MetricRegistry.nutritionLegacyKey(registryKey)
                                val total = todayItems.sumOf { it.metricValue(legacyKey) ?: 0.0 }
                                val label = MetricRegistry.label(registryKey)
                                val unit = MetricRegistry.unit(registryKey)
                                val formatted = if (total >= 10) "${total.toInt()}$unit" else String.format("%.1f$unit", total)
                                NutritionSummaryValue(formatted, label, slotColors.getOrElse(index) { slotColors.last() })
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                        Spacer(Modifier.height(4.dp))

                        // Individual food items
                        todayItems.forEach { item ->
                            TodayLogItem(
                                item = item,
                                onEdit = if (item.source == "manual_usda" || item.source == "barcode_off") {{ editingItem = item; editMealType = item.mealType }} else null,
                                onDelete = if (item.source == "manual_usda" || item.source == "barcode_off") {{ scope.launch { searchService.deleteNutritionItem(item.id); reloadTodayItems() } }} else null
                            )
                        }

                        // All metrics breakdown
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                        Spacer(Modifier.height(8.dp))
                        Text("All Nutrients", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(4.dp))

                        val allNutritionKeys = MetricRegistry.byGroup("nutrition").map { it.key }
                        allNutritionKeys.forEach { registryKey ->
                            val legacyKey = MetricRegistry.nutritionLegacyKey(registryKey)
                            val isRisk = legacyKey in setOf("tyramine_exposure", "alcohol_exposure", "gluten_exposure", "histamine_exposure")
                            val total = if (isRisk) {
                                todayItems.maxOfOrNull { it.metricValue(legacyKey) ?: 0.0 } ?: 0.0
                            } else {
                                todayItems.sumOf { it.metricValue(legacyKey) ?: 0.0 }
                            }
                            val label = MetricRegistry.label(registryKey)
                            val unit = MetricRegistry.unit(registryKey)
                            if (isRisk) {
                                val (levelText, valueColor) = RiskColors.formatRiskLevel(legacyKey, total.toInt())
                                val level = when (total.toInt()) { 3 -> "high"; 2 -> "medium"; 1 -> "low"; else -> "none" }
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        when (legacyKey) {
                                            "tyramine_exposure" -> CheeseIcon(valueColor, 12.dp)
                                            "alcohol_exposure" -> WineGlassIcon(valueColor, 12.dp)
                                            "gluten_exposure" -> WheatIcon(valueColor, 12.dp)
                                            "histamine_exposure" -> FlaskIcon(valueColor, 12.dp)
                                        }
                                        Spacer(Modifier.width(5.dp))
                                        Text(label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(levelText, color = valueColor, style = MaterialTheme.typography.bodySmall)
                                        if (level != "none") {
                                            Spacer(Modifier.width(4.dp))
                                            RiskBar(valueColor, level, maxHeight = 12.dp)
                                        }
                                    }
                                }
                            } else {
                                val formatted = if (total > 0) {
                                    if (total >= 10) "${total.toInt()} $unit" else "${String.format("%.1f", total)} $unit"
                                } else "—"
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                                    Text(formatted, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            
            // History Graph — premium only
            PremiumGate(
                message = "Unlock Nutrition Trends",
                subtitle = "Track your nutrition patterns over time",
                onUpgrade = { navController.navigate(Routes.PAYWALL) }
            ) {
                NutritionHistoryGraph(
                    days = 14,
                    onClick = { navController.navigate(Routes.FULL_GRAPH_NUTRITION) }
                )
            }
        }
    }
}

@Composable
private fun NutritionSummaryValue(value: String, label: String, color: Color = AppTheme.TitleColor) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
    }
}

// Risk metric color scheme
// Defined in RiskColors.kt


