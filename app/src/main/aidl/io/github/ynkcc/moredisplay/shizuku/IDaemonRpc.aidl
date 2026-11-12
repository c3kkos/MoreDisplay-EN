package io.github.ynkcc.moredisplay.shizuku;

interface IDaemonRpc {
    /**
     * Executes one RPC: payload is an RpcRequest serialized by RpcCodec; returns a serialized RpcResponse.
     */
    byte[] invoke(in byte[] payload);

    /**
     * Destroys the service: releases all managed virtual displays and exits the process.
     */
    void destroy();
}
