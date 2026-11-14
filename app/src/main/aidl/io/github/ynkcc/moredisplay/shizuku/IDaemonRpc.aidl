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
     * Like invoke(), plus objects that can't be serialized into the request bytes (a Surface, a binder token,
     * a pipe end), packed in a Bundle by RpcExtras. Used by the eye, cast sound and touch control.
     * Returns a serialized RpcResponse. (Same transaction code as the former attachViewer.)
     */
    byte[] sendWithExtras(in byte[] payload, in Bundle extras);
}
