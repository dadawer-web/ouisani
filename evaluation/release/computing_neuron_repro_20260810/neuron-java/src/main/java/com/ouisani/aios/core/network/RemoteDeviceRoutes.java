package com.ouisani.aios.core.network;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.vfs.RemoteDeviceMountNode;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 远程设备路由 — 从 SyscallServer 抽取的远程设备挂载 WebSocket 通道。
 * <p>
 * 远程设备通过 WebSocket 接入后，自动挂载到 VFS 的 /dev/remote/{deviceId} 路径，
 * 成为一个 RemoteDeviceMountNode 节点，断开时自动卸载。
 * <p>
 * OS 类比：Linux 的 udev — 设备热插拔时自动创建/销毁 /dev 设备节点。
 */
final class RemoteDeviceRoutes {

    private static final Logger log = LoggerFactory.getLogger(RemoteDeviceRoutes.class);

    private RemoteDeviceRoutes() {}

    /**
     * 挂载远程设备 WebSocket 路由到 Javalin 应用。
     *
     * <ul>
     *   <li>WS /ws/remote/{deviceId} — 远程设备自动挂载/卸载通道</li>
     * </ul>
     *
     * @param app           Javalin 应用实例
     * @param remoteDevices 远程设备挂载点表（deviceId → RemoteDeviceMountNode），由调用方持有并传入
     */
    static void attachTo(Javalin app, Map<String, RemoteDeviceMountNode> remoteDevices) {
        // ── Remote Device WebSocket: dynamic mount/unmount into /dev/remote/ ──
        app.ws("/ws/remote/{deviceId}", ws -> {
            ws.onConnect(ctx -> {
                // Auth check
                String token = ctx.queryParam("token");
                if (!AuthManager.instance().verifyToken(token)) {
                    log.warn("[API Gateway] WebSocket /ws/remote 被拒绝: 无效 Token");
                    ctx.session.close();
                    return;
                }

                String deviceId = ctx.pathParam("deviceId");
                String deviceType = ctx.queryParam("type") != null ? ctx.queryParam("type") : "generic";

                log.info("[RemoteDevice] 设备正在连接: deviceId={}, type={}", deviceId, deviceType);

                // Mount or retrieve the RemoteDeviceMountNode from VFS
                RemoteDeviceMountNode node = VfsManager.instance().mountRemoteDevice(deviceId, deviceType);
                node.attachWsContext(ctx);

                remoteDevices.put(deviceId, node);

                EventBus.instance().broadcast("device_mount",
                        "{\"deviceId\":\"" + deviceId + "\",\"type\":\"" + deviceType
                                + "\",\"path\":\"/dev/remote/" + deviceId + "\"}");

                log.info("[RemoteDevice] 设备已挂载: deviceId={} → /dev/remote/{}", deviceId, deviceId);
                System.out.println("  \u001B[32m[RemoteDevice] 设备 '" + deviceId + "' 已连接并挂载到 /dev/remote/" + deviceId + "\u001B[0m");
            });

            ws.onMessage(ctx -> {
                String deviceId = ctx.pathParam("deviceId");
                RemoteDeviceMountNode node = remoteDevices.get(deviceId);
                if (node != null) {
                    String message = ctx.message();
                    node.onWsMessage(message);
                    log.debug("[RemoteDevice] 收到消息: deviceId={}, len={}", deviceId, message.length());
                }
            });

            ws.onClose(ctx -> {
                String deviceId = ctx.pathParam("deviceId");
                log.info("[RemoteDevice] 设备正在断开: deviceId={}", deviceId);

                RemoteDeviceMountNode node = remoteDevices.remove(deviceId);
                if (node != null) {
                    node.detachWsContext();

                    // Unmount from VFS — the device is gone
                    VfsManager.instance().unmountRemoteDevice(deviceId);

                    log.info("[RemoteDevice] 设备已卸载: deviceId={}", deviceId);
                }

                EventBus.instance().broadcast("device_unmount",
                        "{\"deviceId\":\"" + deviceId + "\",\"reason\":\""
                                + escapeJson(ctx.reason() != null ? ctx.reason() : "closed") + "\"}");
            });

            ws.onError(ctx -> {
                String deviceId = ctx.pathParam("deviceId");
                Throwable err = ctx.error();
                if (err != null) {
                    log.error("[RemoteDevice] deviceId={} 上发生错误: {}", deviceId, err.getMessage());
                }

                RemoteDeviceMountNode node = remoteDevices.remove(deviceId);
                if (node != null) {
                    node.detachWsContext();
                    VfsManager.instance().unmountRemoteDevice(deviceId);
                }
            });
        });

        log.info("[Syscall Gateway] 远程设备 WebSocket 已挂载: /ws/remote/{deviceId}");
        System.out.println("  ✓ [Syscall Gateway] 远程设备 WebSocket: /ws/remote/{deviceId}");
    }

    /**
     * 简单的 JSON 字符串转义。
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
