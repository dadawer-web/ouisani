package com.ouisani.aios.vfs;

/**
 * Thrown when an Agent attempts to read from or write to a remote device
 * that has been disconnected from the AIOS kernel.
 * <p>
 * This is the VFS equivalent of a hardware interrupt — the device that
 * was mounted at {@code /dev/remote/{deviceId}} is no longer reachable,
 * and any pending or future I/O operations on that node must fail
 * immediately rather than block indefinitely.
 * <p>
 * The {@link SyscallDispatcher} catches this exception in the
 * {@code routeStorage} path and converts it into a
 * {@link com.ouisani.aios.core.syscall.SyscallResponse#fail(String)},
 * so the Agent's LLM can see the error and attempt self-repair
 * (e.g., retry, switch to a different device, or degrade gracefully).
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
