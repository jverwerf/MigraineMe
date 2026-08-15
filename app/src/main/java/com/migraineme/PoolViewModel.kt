package com.migraineme

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Shared base for the pool-management view models (reliefs, medicines,
 * triggers, symptoms, prodromes, migraines, locations, activities, missed
 * activities, treatment side effects).
 *
 * Pool edits run inside `viewModelScope.launch`, so a failure in them can never
 * propagate back to the composable that triggered it — a `runCatching` around
 * the `addNewToPool(...)` call returns before the work has even started. That is
 * why a failing pool write used to be printed to logcat only while the add
 * dialog closed as though the row had saved.
 *
 * View models emit user-facing failures here; [ManagePoolScreen] collects them
 * and shows the app's standard error snackbar.
 */
abstract class PoolViewModel : ViewModel() {

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** One-shot, user-facing failures from pool edits. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /** Log a pool-edit failure and surface it to the UI. */
    protected fun reportError(t: Throwable) {
        t.printStackTrace()
        _errors.tryEmit(t.message?.takeIf { it.isNotBlank() } ?: tSync("error"))
    }
}
