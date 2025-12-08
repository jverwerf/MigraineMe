package com.migraineme

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
import androidx.compose.material3.SwipeToDismissBoxValue.Settled
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustMedicinesScreen(
    navController: NavController,
    vm: MedicineViewModel,
    authVm: AuthViewModel
) {
    val authState by authVm.state.collectAsState()
    val pool by vm.pool.collectAsState()
    val frequent by vm.frequent.collectAsState()

    var newLabel by remember { mutableStateOf("") }

    LaunchedEffect(authState.accessToken) {
        Log.d("AdjustMedicines", "token present? ${authState.accessToken != null}")
        authState.accessToken?.let { vm.loadAll(it) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Back
        item {
            ElevatedCard(
                onClick = { navController.popBackStack() },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    Spacer(Modifier.width(8.dp))
                    Text("Back")
                }
            }
        }

        // Add new to pool
        item {
            Column {
                OutlinedTextField(
                    value = newLabel,
                    onValueChange = { newLabel = it },
                    label = { Text("New medicine label") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val token = authState.accessToken ?: return@Button
                        val label = newLabel.trim()
                        if (label.isNotEmpty()) {
                            vm.addNewToPool(token, label)
                            newLabel = ""
                        }
                    },
                    enabled = newLabel.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add to Pool")
                    Spacer(Modifier.width(8.dp))
                    Text("Add to list")
                }
            }
        }

        // Frequent
        item { Text("Frequent", style = MaterialTheme.typography.titleMedium) }
        items(frequent, key = { it.id }) { pref ->
            ElevatedCard(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(pref.medicine?.label ?: "", style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = {
                        val token = authState.accessToken ?: return@IconButton
                        vm.removeFromFrequent(token, pref.id)
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove from Frequent")
                    }
                }
            }
        }

        // All medicines
        item { Text("All Medicines", style = MaterialTheme.typography.titleMedium) }

        val frequentIds = frequent.map { it.medicineId }.toSet()
        val remaining = pool.filter { it.id !in frequentIds }

        items(remaining, key = { it.id }) { med ->
            val dismissState = rememberSwipeToDismissBoxState(
                initialValue = Settled,
                confirmValueChange = { value ->
                    if (value == EndToStart) {
                        val token = authState.accessToken
                            ?: return@rememberSwipeToDismissBoxState false
                        vm.removeFromPool(token, med.id)
                        true
                    } else false
                }
            )
            SwipeToDismissBox(
                state = dismissState,
                enableDismissFromStartToEnd = false,
                enableDismissFromEndToStart = true,
                backgroundContent = { }
            ) {
                ElevatedCard(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(med.label, style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = {
                            val token = authState.accessToken ?: run {
                                Log.d("AdjustMedicines", "token null on + click")
                                return@IconButton
                            }
                            Log.d("AdjustMedicines", "addToFrequent id=${med.id}")
                            vm.addToFrequent(token, med.id)
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add to Frequent")
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
