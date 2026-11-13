package io.github.ynkcc.moredisplay.shizuku;

import android.os.Bundle;

interface IDaemonRpc {
    /**
     * Executes one RPC: payload is an RpcRequest serialized by RpcCodec; returns a serialized RpcResponse.
     */
    byte[] invoke(in byte[] payload);

    /**
     * Destroys the service: releases all managed virtual displays and exits the process.
     */
    void destroy();

    /**
     * Eye on: like invoke() with an AttachViewer request, plus the viewer's Surface and binder token
     * (packed in a Bundle, see ProviderRpc.EXTRA_VIEWER_*). Returns a serialized RpcResponse.
     */
    byte[] attachViewer(in byte[] payload, in Bundle viewer);
}
