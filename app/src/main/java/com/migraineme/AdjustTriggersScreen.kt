package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdjustTriggersScreen(
    navController: NavController,
    vm: TriggerViewModel
) {
    val authVm: AuthViewModel = viewModel()
    val authState by authVm.state.collectAsState()

    val pool by vm.pool.collectAsState()
    val frequent by vm.frequent.collectAsState()

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { vm.loadAll(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Manage Triggers", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        Text(
            "DEBUG → pool=${pool.size}, frequent=${frequent.size}",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(16.dp))

        Text("Frequent", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        LazyColumn {
            items(frequent) { pref ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(pref.trigger?.label ?: "")
                    IconButton(onClick = {
                        authState.accessToken?.let { token ->
                            vm.removeFromFrequent(token, pref.id)
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text("All Triggers", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        val frequentIds = frequent.map { it.triggerId }.toSet()
        val remaining = pool.filter { it.id !in frequentIds }

        LazyColumn {
            items(remaining) { trig ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(trig.label)
                    IconButton(onClick = {
                        authState.accessToken?.let { token ->
                            vm.addToFrequent(token, trig.id)
                        }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                }
            }
        }
    }
}
