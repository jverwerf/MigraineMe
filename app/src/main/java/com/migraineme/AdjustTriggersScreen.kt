package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
import androidx.compose.material3.SwipeToDismissBoxValue.Settled
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustTriggersScreen(
    navController: NavController,
    vm: TriggerViewModel,
    authVm: AuthViewModel
) {
    val authState by authVm.state.collectAsState()
    val pool by vm.pool.collectAsState()
    val frequent by vm.frequent.collectAsState()

    var newLabel by remember { mutableStateOf("") }

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { vm.loadAll(it) }
    }

    Scaffold { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(inner)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Back arrow card
            item {
                Card(
                    onClick = { navController.popBackStack() },
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Back")
                    }
                }
            }

            // New trigger input
            item {
                Column {
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text("New trigger label") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
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
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add to list")
                        }
                    }
                }
            }

            // Frequent header
            item { Text("Frequent", style = MaterialTheme.typography.titleMedium) }

            // Frequent items
            items(frequent, key = { it.id }) { pref ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(pref.trigger?.label ?: "", style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = {
                            val token = authState.accessToken ?: return@IconButton
                            vm.removeFromFrequent(token, pref.id)
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove from Frequent")
                        }
                    }
                }
            }

            // All Triggers header
            item { Text("All Triggers", style = MaterialTheme.typography.titleMedium) }

            // All Triggers with swipe-to-delete
            val frequentIds = frequent.map { it.triggerId }.toSet()
            val remaining = pool.filter { it.id !in frequentIds }

            items(remaining, key = { it.id }) { trig ->
                val dismissState = rememberSwipeToDismissBoxState(
                    initialValue = Settled,
                    confirmValueChange = { value ->
                        if (value == EndToStart) {
                            val token = authState.accessToken ?: return@rememberSwipeToDismissBoxState false
                            vm.removeFromPool(token, trig.id)
                            true
                        } else false
                    }
                )

                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = true,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (dismissState.targetValue == EndToStart)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
                                )
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = Color.White
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(trig.label, style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = {
                                val token = authState.accessToken ?: return@IconButton
                                vm.addToFrequent(token, trig.id)
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
}
