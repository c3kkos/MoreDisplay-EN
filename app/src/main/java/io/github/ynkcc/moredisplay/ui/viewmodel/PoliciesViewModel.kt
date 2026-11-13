package io.github.ynkcc.moredisplay.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.github.ynkcc.moredisplay.core.DisplayPolicy
import io.github.ynkcc.moredisplay.core.IDisplayRepository
import io.github.ynkcc.moredisplay.core.Visibility

/** App picker entry: label / package name / uid fetched together; the icon is loaded lazily in the UI layer. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val uid: Int
)

class PoliciesViewModel(
    private val repo: IDisplayRepository,
    private val app: Application
) : ViewModel() {

    val displays = repo.managedDisplays

    private val _policies = MutableStateFlow<List<DisplayPolicy>>(emptyList())
    val policies: StateFlow<List<DisplayPolicy>> = _policies

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Installed app list; null = not loaded yet / loading. */
    private val _installedApps = MutableStateFlow<List<AppEntry>?>(null)
    val installedApps: StateFlow<List<AppEntry>?> = _installedApps

    init {
        reload()
    }

    /** On first call, reads installed apps on the IO thread and caches the result; repeated calls are no-ops. */
    fun loadInstalledApps() {
        if (_installedApps.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val pm = app.packageManager
            val list = runCatching {
                pm.getInstalledApplications(0)
                    .map { AppEntry(it.packageName, it.loadLabel(pm).toString(), it.uid) }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            }.getOrDefault(emptyList())
            _installedApps.value = list
        }
    }

    fun reload() {
        viewModelScope.launch {
            _busy.value = true
            repo.listDisplayPolicies().fold(
                onSuccess = { _policies.value = it },
                onFailure = { _message.value = "Failed to load policies: ${it.message}" }
            )
            _busy.value = false
        }
    }

    fun resolveUid(packageName: String): Int? = runCatching {
        app.packageManager.getApplicationInfo(packageName, 0).uid
    }.getOrNull()

    fun addPolicy(
        uid: Int,
        packageName: String?,
        visibility: Visibility,
        displayIds: Set<Int>,
        operatedDisplayId: Int?,
        recordDisplayId: Int?,
        launchDisplayId: Int? = null
    ) {
        viewModelScope.launch {
            _busy.value = true
            repo.setDisplayPolicy(
                DisplayPolicy(
                    uid = uid,
                    packageName = packageName?.takeIf { it.isNotBlank() },
                    visibility = visibility,
                    displayIds = displayIds,
                    operatedDisplayId = operatedDisplayId,
                    recordDisplayId = recordDisplayId,
                    launchDisplayId = launchDisplayId
                )
            ).fold(
                onSuccess = {
                    _message.value = "Policy saved: ${it.describe()}"
                    reload()
                },
                onFailure = { _message.value = "Save failed: ${it.message}" }
            )
            _busy.value = false
        }
    }

    fun removePolicy(uid: Int) {
        viewModelScope.launch {
            _busy.value = true
            repo.removeDisplayPolicy(uid).fold(
                onSuccess = {
                    _message.value = "Policy removed for uid=$uid"
                    reload()
                },
                onFailure = { _message.value = "Remove failed: ${it.message}" }
            )
            _busy.value = false
        }
    }

    fun consumeMessage() { _message.value = null }
}
