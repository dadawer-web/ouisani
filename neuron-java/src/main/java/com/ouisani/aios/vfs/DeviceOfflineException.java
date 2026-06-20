package com.ouisani.aios.vfs;

/**
 * 设备离线异常 — 当 Agent 尝试读写已断开连接的远程设备时抛出。
 * <p>
 * 这是 VFS 层面的硬件中断等价物 — 挂载在 {@code /dev/remote/{deviceId}}
 * 的设备不再可达，所有待处理或未来的 I/O 操作必须立即失败，
 * 而非无限阻塞。
 * <p>
 * {@link com.ouisani.aios.core.syscall.SyscallDispatcher} 在
 * {@code routeStorage} 路径中捕获此异常，将其转换为
 * {@link com.ouisani.aios.core.syscall.SyscallResponse#fail(String)}，
 * 使 Agent 的 LLM 能看到错误并尝试自修复
 * （如重试、切换到其他设备、或优雅降级）。
 */
public class DeviceOfflineException extends RuntimeException {

    private final String devicePath;
    private final String deviceId;

    public DeviceOfflineException(String devicePath, String deviceId) {
        super("Device offline: path='" + devicePath + "', deviceId='" + deviceId
                + "'. The remote host has disconnected.");
        this.devicePath = devicePath;
        this.deviceId = deviceId;
    }

    public DeviceOfflineException(String devicePath, String deviceId, String reason) {
        super("Device offline: path='" + devicePath + "', deviceId='" + deviceId
                + "'. Reason: " + reason);
        this.devicePath = devicePath;
        this.deviceId = deviceId;
    }

    public String devicePath() {
        return devicePath;
    }

    public String deviceId() {
        return deviceId;
    }
}
