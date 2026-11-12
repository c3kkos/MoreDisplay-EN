package io.github.ynkcc.moredisplay.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.github.ynkcc.moredisplay.core.ConnectionStatus
import io.github.ynkcc.moredisplay.core.IDisplayRepository
import io.github.ynkcc.moredisplay.core.Privilege
import io.github.ynkcc.moredisplay.core.WorkModeInfo
import io.github.ynkcc.moredisplay.dispatch.DispatchCenter

class MainViewModel(
    private val repo: IDisplayRepository
) : ViewModel() {

    val status: StateFlow<ConnectionStatus> = repo.connectionStatus
    val error: StateFlow<String?> = repo.connectionError

    /** Current primary channel (highest-priority connected channel). */
    val workMode: StateFlow<WorkModeInfo> = repo.workMode

    /** All connected channels (multi-channel: Shizuku and Xposed can work at the same time). */
    val workModes: StateFlow<Map<Privilege, WorkModeInfo>> = repo.workModes

    /** Access methods currently available (not necessarily connected yet). */
    private val _availablePrivileges = MutableStateFlow<List<Privilege>>(emptyList())
    val availablePrivileges: StateFlow<List<Privilege>> = _availablePrivileges

    init {
        refreshProviders()
    }

    /** Connects all available channels (already connected ones are skipped; incremental). */
    fun connect() = viewModelScope.launch {
        repo.connect()
        repo.refreshDisplays()
    }

    fun disconnect() = viewModelScope.launch { repo.disconnect() }

    fun refreshProviders() {
        _availablePrivileges.value = DispatchCenter.activeProviders().map { it.privilege }
    }
}
