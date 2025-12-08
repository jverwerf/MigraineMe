package com.migraineme

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustReliefsScreen(
    navController: NavController,
    vm: ReliefViewModel,
    authVm: AuthViewModel
) {
    val authState by authVm.state.collectAsState()
    val pool by vm.pool.collectAsState()
    val frequent by vm.frequent.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var newLabel by remember { mutableStateOf("") }

    LaunchedEffect(authState.accessToken) {
        Log.d("AdjustReliefs", "token=${authState.accessToken != null}")
        authState.accessToken?.let { vm.loadAll(it) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(inner)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ElevatedCard(
                    onClick = { navController.popBackStack() },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        Spacer(Modifier.width(8.dp))
                        Text("Back")
                    }
                }
            }

            item {
                Column {
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text("New relief label") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val token = authState.accessToken
                            val label = newLabel.trim()
                            if (token.isNullOrBlank() || label.isEmpty()) return@Button
                            runCatching {
                                vm.addNewToPool(token, label)
                                newLabel = ""
                            }.onFailure { e ->
                                e.printStackTrace()
                                scope.launch { snackbar.showSnackbar("Failed to add: ${e.message ?: "error"}") }
                            }
                        },
                        enabled = newLabel.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add to Pool")
                        Spacer(Modifier.width(8.dp))
                        Text("Add to list")
                    }
                }
            }

            item { Text("Frequent", style = MaterialTheme.typography.titleMedium) }
            items(frequent, key = { it.id }) { pref ->
                ElevatedCard(
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(pref.relief?.label ?: "", style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = {
                            val token = authState.accessToken ?: return@IconButton
                            runCatching { vm.removeFromFrequent(token, pref.id) }
                                .onFailure { e ->
                                    e.printStackTrace()
                                    scope.launch { snackbar.showSnackbar("Failed to remove: ${e.message ?: "error"}") }
                                }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove from Frequent")
                        }
                    }
                }
            }

            item { Text("All Reliefs", style = MaterialTheme.typography.titleMedium) }

            val frequentIds = frequent.map { it.reliefId }.toSet()
            val remaining = pool.filter { it.id !in frequentIds }

            items(remaining, key = { it.id }) { rel ->
                val dismissState = rememberSwipeToDismissBoxState(
                    initialValue = Settled,
                    confirmValueChange = { value ->
                        if (value == EndToStart) {
                            val token = authState.accessToken ?: return@rememberSwipeToDismissBoxState false
                            runCatching {
                                vm.removeFromPool(token, rel.id)
                                true
                            }.onFailure { e ->
                                e.printStackTrace()
                                scope.launch { snackbar.showSnackbar("Delete failed: ${e.message ?: "error"}") }
                            }.getOrDefault(false)
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp, horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(rel.label, style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = {
                                val token = authState.accessToken ?: return@IconButton
                                runCatching { vm.addToFrequent(token, rel.id) }
                                    .onFailure { e ->
                                        e.printStackTrace()
                                        scope.launch { snackbar.showSnackbar("Add failed: ${e.message ?: "error"}") }
                                    }
                            }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add to Frequent")
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
