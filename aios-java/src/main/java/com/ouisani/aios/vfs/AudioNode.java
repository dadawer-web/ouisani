package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public non-sealed class AudioNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(AudioNode.class);

    private final String path;
    private int ownerUid;
    private int permissions;

    public AudioNode(String path) {
        this(path, 0, 0222);
    }

    public AudioNode(String path, int ownerUid, int permissions) {
        this.path = path;
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.AUDIO;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public int ownerUid() {
        return ownerUid;
    }

    @Override
    public void setOwnerUid(int uid) {
        this.ownerUid = uid;
    }

    @Override
    public int permissions() {
        return permissions;
    }

    @Override
    public void setPermissions(int perm) {
        this.permissions = perm;
    }

    @Override
    public String read() {
        throw new UnsupportedOperationException("AudioNode is write-only: " + path);
    }

    @Override
    public boolean write(String payload) {
        System.out.printf("  🔊 [AudioNode] Playing sound: \"%s\"%n",
                payload.length() > 80 ? payload.substring(0, 80) + "..." : payload);
        log.info("[AudioNode] TTS synthesis: path={}, textLen={}", path, payload.length());
        return true;
    }
}
