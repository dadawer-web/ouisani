package com.ouisani.aios.operator.secrets;

/**
 * 密钥解析异常 — 对标 OpenClaw 的 SecretProviderResolutionError + SecretRefResolutionError。
 */
public class SecretResolutionException extends Exception {

    private final SecretRef ref;

    public SecretResolutionException(String message, SecretRef ref) {
        super(message);
        this.ref = ref;
    }

    public SecretResolutionException(String message, SecretRef ref, Throwable cause) {
        super(message, cause);
        this.ref = ref;
    }

    public SecretRef ref() { return ref; }
}
