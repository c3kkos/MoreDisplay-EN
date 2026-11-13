package io.github.ynkcc.moredisplay.core

import android.os.Parcel

object RpcCodec {

    fun marshallRequest(request: RpcRequest): ByteArray {
        val parcel = Parcel.obtain()
        try {
            writeRequest(parcel, request)
            return parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    fun unmarshallRequest(payload: ByteArray): RpcRequest {
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(payload, 0, payload.size)
            parcel.setDataPosition(0)
            return readRequest(parcel)
        } finally {
            parcel.recycle()
        }
    }

    fun marshallResponse(response: RpcResponse): ByteArray {
        val parcel = Parcel.obtain()
        try {
            writeResponse(parcel, response)
            return parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    fun unmarshallResponse(payload: ByteArray): RpcResponse {
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(payload, 0, payload.size)
            parcel.setDataPosition(0)
            return readResponse(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun writeRequest(parcel: Parcel, request: RpcRequest) {
        when (request) {
            is RpcRequest.Ping -> {
                parcel.writeInt(TAG_PING)
                parcel.writeInt(request.id)
            }
            is RpcRequest.GetCapabilities -> {
                parcel.writeInt(TAG_GET_CAPABILITIES)
                parcel.writeInt(request.id)
            }
            is RpcRequest.CreateDisplay -> {
                parcel.writeInt(TAG_CREATE_DISPLAY)
                parcel.writeInt(request.id)
                writeSpec(parcel, request.spec)
            }
            is RpcRequest.HoldDisplay -> {
                parcel.writeInt(TAG_HOLD_DISPLAY)
                parcel.writeInt(request.id)
                parcel.writeInt(request.displayId)
            }
            is RpcRequest.RemoveDisplay -> {
                parcel.writeInt(TAG_REMOVE_DISPLAY)
                parcel.writeInt(request.id)
                parcel.writeInt(request.displayId)
            }
            is RpcRequest.ListDisplays -> {
                parcel.writeInt(TAG_LIST_DISPLAYS)
                parcel.writeInt(request.id)
            }
            is RpcRequest.SetDisplayPolicy -> {
                parcel.writeInt(TAG_SET_POLICY)
                parcel.writeInt(request.id)
                writePolicy(parcel, request.policy)
            }
            is RpcRequest.GetDisplayPolicy -> {
                parcel.writeInt(TAG_GET_POLICY)
                parcel.writeInt(request.id)
                parcel.writeInt(request.uid)
            }
            is RpcRequest.RemoveDisplayPolicy -> {
                parcel.writeInt(TAG_REMOVE_POLICY)
                parcel.writeInt(request.id)
                parcel.writeInt(request.uid)
            }
            is RpcRequest.ListDisplayPolicies -> {
                parcel.writeInt(TAG_LIST_POLICIES)
                parcel.writeInt(request.id)
            }
            is RpcRequest.DisplaysForUid -> {
                parcel.writeInt(TAG_DISPLAYS_FOR_UID)
                parcel.writeInt(request.id)
                parcel.writeInt(request.uid)
            }
            is RpcRequest.AttachViewer -> {
                parcel.writeInt(TAG_ATTACH_VIEWER)
                parcel.writeInt(request.id)
                parcel.writeInt(request.displayId)
            }
            is RpcRequest.DetachViewer -> {
                parcel.writeInt(TAG_DETACH_VIEWER)
                parcel.writeInt(request.id)
                parcel.writeInt(request.displayId)
            }
            is RpcRequest.StartAudioCapture -> {
                parcel.writeInt(TAG_START_AUDIO)
                parcel.writeInt(request.id)
                parcel.writeInt(request.displayId)
            }
            is RpcRequest.StopAudioCapture -> {
                parcel.writeInt(TAG_STOP_AUDIO)
                parcel.writeInt(request.id)
                parcel.writeInt(request.displayId)
            }
            is RpcRequest.OpenTouchChannel -> {
                parcel.writeInt(TAG_OPEN_TOUCH)
                parcel.writeInt(request.id)
                parcel.writeInt(request.displayId)
                parcel.writeInt(request.viewerTaskId)
            }
        }
    }

    private fun readRequest(parcel: Parcel): RpcRequest {
        return when (val tag = parcel.readInt()) {
            TAG_PING -> RpcRequest.Ping(parcel.readInt())
            TAG_GET_CAPABILITIES -> RpcRequest.GetCapabilities(parcel.readInt())
            TAG_CREATE_DISPLAY -> RpcRequest.CreateDisplay(parcel.readInt(), readSpec(parcel))
            TAG_HOLD_DISPLAY -> RpcRequest.HoldDisplay(parcel.readInt(), parcel.readInt())
            TAG_REMOVE_DISPLAY -> RpcRequest.RemoveDisplay(parcel.readInt(), parcel.readInt())
            TAG_LIST_DISPLAYS -> RpcRequest.ListDisplays(parcel.readInt())
            TAG_SET_POLICY -> RpcRequest.SetDisplayPolicy(parcel.readInt(), readPolicy(parcel))
            TAG_GET_POLICY -> RpcRequest.GetDisplayPolicy(parcel.readInt(), parcel.readInt())
            TAG_REMOVE_POLICY -> RpcRequest.RemoveDisplayPolicy(parcel.readInt(), parcel.readInt())
            TAG_LIST_POLICIES -> RpcRequest.ListDisplayPolicies(parcel.readInt())
            TAG_DISPLAYS_FOR_UID -> RpcRequest.DisplaysForUid(parcel.readInt(), parcel.readInt())
            TAG_ATTACH_VIEWER -> RpcRequest.AttachViewer(parcel.readInt(), parcel.readInt())
            TAG_DETACH_VIEWER -> RpcRequest.DetachViewer(parcel.readInt(), parcel.readInt())
            TAG_START_AUDIO -> RpcRequest.StartAudioCapture(parcel.readInt(), parcel.readInt())
            TAG_STOP_AUDIO -> RpcRequest.StopAudioCapture(parcel.readInt(), parcel.readInt())
            TAG_OPEN_TOUCH -> RpcRequest.OpenTouchChannel(parcel.readInt(), parcel.readInt(), parcel.readInt())
            else -> error("unknown request tag $tag")
        }
    }

    private fun writeResponse(parcel: Parcel, response: RpcResponse) {
        parcel.writeInt(response.id)
        when (response) {
            is RpcResponse.Pong -> {
                parcel.writeInt(TAG_PONG)
                parcel.writeInt(response.daemonPid)
                parcel.writeInt(response.daemonVersion)
            }
            is RpcResponse.Capabilities -> {
                parcel.writeInt(TAG_CAPABILITIES)
                writeCapabilities(parcel, response.capabilities)
            }
            is RpcResponse.DisplayResult -> {
                parcel.writeInt(TAG_DISPLAY_RESULT)
                writeInfo(parcel, response.info)
            }
            is RpcResponse.DisplayList -> {
                parcel.writeInt(TAG_DISPLAY_LIST)
                parcel.writeInt(response.displays.size)
                response.displays.forEach { writeInfo(parcel, it) }
            }
            is RpcResponse.PolicyResult -> {
                parcel.writeInt(TAG_POLICY_RESULT)
                writeNullablePolicy(parcel, response.policy)
            }
            is RpcResponse.PolicyList -> {
                parcel.writeInt(TAG_POLICY_LIST)
                parcel.writeInt(response.policies.size)
                response.policies.forEach { writePolicy(parcel, it) }
            }
            is RpcResponse.IdList -> {
                parcel.writeInt(TAG_ID_LIST)
                parcel.writeInt(response.ids.size)
                response.ids.forEach { parcel.writeInt(it) }
            }
            is RpcResponse.Ok -> parcel.writeInt(TAG_OK)
            is RpcResponse.Error -> {
                parcel.writeInt(TAG_ERROR)
                parcel.writeInt(response.code)
                parcel.writeString(response.message)
            }
        }
    }

    private fun readResponse(parcel: Parcel): RpcResponse {
        val id = parcel.readInt()
        return when (val tag = parcel.readInt()) {
            TAG_PONG -> RpcResponse.Pong(id, parcel.readInt(), parcel.readInt())
            TAG_CAPABILITIES -> RpcResponse.Capabilities(id, readCapabilities(parcel))
            TAG_DISPLAY_RESULT -> RpcResponse.DisplayResult(id, readInfo(parcel))
            TAG_DISPLAY_LIST -> {
                val count = parcel.readInt()
                RpcResponse.DisplayList(id, buildList { repeat(count) { add(readInfo(parcel)) } })
            }
            TAG_POLICY_RESULT -> RpcResponse.PolicyResult(id, readNullablePolicy(parcel))
            TAG_POLICY_LIST -> {
                val count = parcel.readInt()
                RpcResponse.PolicyList(id, buildList { repeat(count) { add(readPolicy(parcel)) } })
            }
            TAG_ID_LIST -> {
                val count = parcel.readInt()
                RpcResponse.IdList(id, buildList { repeat(count) { add(parcel.readInt()) } })
            }
            TAG_OK -> RpcResponse.Ok(id)
            TAG_ERROR -> RpcResponse.Error(id, parcel.readInt(), parcel.readString() ?: "")
            else -> error("unknown response tag $tag")
        }
    }

    private fun writeSpec(parcel: Parcel, spec: DisplaySpec) {
        parcel.writeInt(spec.width)
        parcel.writeInt(spec.height)
        parcel.writeInt(spec.densityDpi)
        parcel.writeString(spec.name)
        parcel.writeInt(spec.flags)
    }

    private fun readSpec(parcel: Parcel) = DisplaySpec(
        width = parcel.readInt(),
        height = parcel.readInt(),
        densityDpi = parcel.readInt(),
        name = parcel.readString() ?: "MoreDisplay",
        flags = parcel.readInt()
    )

    private fun writeInfo(parcel: Parcel, info: DisplayInfo) {
        writeSpec(parcel, info.spec)
        parcel.writeInt(info.displayId)
        val owner = info.owner
        if (owner == null) {
            parcel.writeInt(0)
        } else {
            parcel.writeInt(1)
            parcel.writeString(owner.packageName)
            parcel.writeInt(owner.uid)
        }
    }

    private fun readInfo(parcel: Parcel): DisplayInfo {
        val spec = readSpec(parcel)
        val displayId = parcel.readInt()
        val owner = if (parcel.readInt() == 1) {
            DisplayOwner(parcel.readString(), parcel.readInt())
        } else null
        return DisplayInfo(displayId, spec, owner)
    }

    private fun writePolicy(parcel: Parcel, policy: DisplayPolicy) {
        parcel.writeInt(policy.uid)
        parcel.writeString(policy.packageName)
        parcel.writeString(policy.visibility.name)
        parcel.writeInt(policy.displayIds.size)
        policy.displayIds.forEach { parcel.writeInt(it) }
        writeNullableInt(parcel, policy.operatedDisplayId)
        writeNullableInt(parcel, policy.recordDisplayId)
        writeNullableInt(parcel, policy.launchDisplayId)
        parcel.writeInt(policy.orientation.ordinal)
    }

    private fun readPolicy(parcel: Parcel) = DisplayPolicy(
        uid = parcel.readInt(),
        packageName = parcel.readString(),
        visibility = Visibility.fromName(parcel.readString()),
        displayIds = buildSet { repeat(parcel.readInt()) { add(parcel.readInt()) } },
        operatedDisplayId = readNullableInt(parcel),
        recordDisplayId = readNullableInt(parcel),
        launchDisplayId = readNullableInt(parcel),
        orientation = OrientationOverride.entries.getOrElse(parcel.readInt()) { OrientationOverride.APP }
    )

    private fun writeNullablePolicy(parcel: Parcel, policy: DisplayPolicy?) {
        if (policy == null) {
            parcel.writeInt(0)
        } else {
            parcel.writeInt(1)
            writePolicy(parcel, policy)
        }
    }

    private fun readNullablePolicy(parcel: Parcel): DisplayPolicy? =
        if (parcel.readInt() == 1) readPolicy(parcel) else null

    private fun writeNullableInt(parcel: Parcel, value: Int?) {
        parcel.writeInt(if (value == null) 0 else 1)
        if (value != null) parcel.writeInt(value)
    }

    private fun readNullableInt(parcel: Parcel): Int? =
        if (parcel.readInt() == 1) parcel.readInt() else null

    private fun writeCapabilities(parcel: Parcel, capabilities: DaemonCapabilities) {
        parcel.writeInt(capabilities.maxDisplayCount)
        parcel.writeInt(capabilities.supportedFlags)
        parcel.writeInt(if (capabilities.launchOnDisplayBypass) 1 else 0)
        parcel.writeInt(if (capabilities.unlockedDisplay) 1 else 0)
        parcel.writeInt(if (capabilities.privilegedSurface) 1 else 0)
        parcel.writeInt(if (capabilities.perUidDisplayVisibility) 1 else 0)
        parcel.writeInt(if (capabilities.recordRedirection) 1 else 0)
        parcel.writeInt(if (capabilities.accessibilityRedirection) 1 else 0)
        parcel.writeInt(if (capabilities.recentsGate) 1 else 0)
    }

    private fun readCapabilities(parcel: Parcel) = DaemonCapabilities(
        maxDisplayCount = parcel.readInt(),
        supportedFlags = parcel.readInt(),
        launchOnDisplayBypass = parcel.readInt() == 1,
        unlockedDisplay = parcel.readInt() == 1,
        privilegedSurface = parcel.readInt() == 1,
        perUidDisplayVisibility = parcel.readInt() == 1,
        recordRedirection = parcel.readInt() == 1,
        accessibilityRedirection = parcel.readInt() == 1,
        recentsGate = parcel.readInt() == 1
    )

    private const val TAG_PING = 1
    private const val TAG_GET_CAPABILITIES = 2
    private const val TAG_CREATE_DISPLAY = 3
    private const val TAG_HOLD_DISPLAY = 4
    private const val TAG_REMOVE_DISPLAY = 5
    private const val TAG_LIST_DISPLAYS = 6
    private const val TAG_SET_POLICY = 7
    private const val TAG_GET_POLICY = 8
    private const val TAG_REMOVE_POLICY = 9
    private const val TAG_LIST_POLICIES = 10
    private const val TAG_DISPLAYS_FOR_UID = 11
    private const val TAG_ATTACH_VIEWER = 12
    private const val TAG_DETACH_VIEWER = 13
    private const val TAG_START_AUDIO = 14
    private const val TAG_STOP_AUDIO = 15
    private const val TAG_OPEN_TOUCH = 16

    private const val TAG_PONG = 101
    private const val TAG_CAPABILITIES = 102
    private const val TAG_DISPLAY_RESULT = 103
    private const val TAG_DISPLAY_LIST = 104
    private const val TAG_OK = 105
    private const val TAG_ERROR = 106
    private const val TAG_POLICY_RESULT = 107
    private const val TAG_POLICY_LIST = 108
    private const val TAG_ID_LIST = 109
}
