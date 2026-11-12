package io.github.ynkcc.moredisplay.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.github.ynkcc.moredisplay.core.DisplayInfo
import io.github.ynkcc.moredisplay.core.DisplaySpec
import io.github.ynkcc.moredisplay.core.IDisplayRepository
import io.github.ynkcc.moredisplay.core.Privilege

class DisplaysViewModel(
    private val repo: IDisplayRepository
) : ViewModel() {

    /** Managed displays grouped by channel, shown and managed per channel in the UI. */
    val displaysByChannel: StateFlow<Map<Privilege, List<DisplayInfo>>> = repo.displaysByChannel

    /** Currently connected channels. */
    val workModes: StateFlow<Map<Privilege, io.github.ynkcc.moredisplay.core.WorkModeInfo>> = repo.workModes

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Creates a virtual display on the given channel. */
    fun createDisplay(
        channel: Privilege,
        width: Int,
        height: Int,
        dpi: Int,
        name: String,
        flags: Int
    ) {
        viewModelScope.launch {
            _busy.value = true
            repo.createDisplay(
                DisplaySpec(
                    width = width, height = height, densityDpi = dpi,
                    name = name.ifBlank { "MoreDisplay" }, flags = flags
                ),
                channel
            ).fold(
                onSuccess = {
                    _message.value = "[${channel.label()}] Created display ${it.displayId}"
                    repo.refreshDisplays()
                },
                onFailure = { _message.value = "Create failed: ${it.message}" }
            )
            _busy.value = false
        }
    }

    fun removeDisplay(id: Int) {
        viewModelScope.launch {
            _busy.value = true
            repo.removeDisplay(id).fold(
                onSuccess = {
                    _message.value = "Display $id removed"
                    repo.refreshDisplays()
                },
                onFailure = { _message.value = "Remove failed: ${it.message}" }
            )
            _busy.value = false
        }
    }

    fun refresh() = repo.refreshDisplays()

    fun consumeMessage() { _message.value = null }
}

fun Privilege.label(): String = when (this) {
    Privilege.LSPOSED -> "LSPosed"
    Privilege.ROOT -> "Root"
    Privilege.SHELL_SHIZUKU -> "Shizuku"
    Privilege.NONE -> "Unassigned"
}
