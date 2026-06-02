#ifndef AIOS_H
#define AIOS_H

/*
 * AIOS Standard C Library SDK
 *
 * Usage (WASM target):
 *   #include "aios.h"
 *
 *   char* data = aios_vfs_read("/dev/camera0");
 *   char* reply = aios_think("Describe this image");
 *   aios_log(reply);
 *   free(data);
 *   free(reply);
 *
 * Compile:
 *   clang --target=wasm32-wasi --sysroot=<wasi-sysroot> \
 *         -I/usr_include -o app.wasm app.c
 */

#include <stdlib.h>
#include <string.h>

#define AIOS_VFS_READ_BUF_SIZE  16384
#define AIOS_THINK_BUF_SIZE     32768

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Low-level WASM import declarations
 *
 * These symbols are resolved by the AIOS kernel's WasmEdge host
 * module registry at instantiation time.
 */

__attribute__((import_module("aios"), import_name("__aios_vfs_read")))
int __aios_vfs_read_raw(const char* path, int path_len, char* resp, int resp_len);

__attribute__((import_module("aios"), import_name("__aios_think")))
int __aios_think_raw(const char* prompt, int prompt_len, char* resp, int resp_len);

__attribute__((import_module("aios"), import_name("__aios_vfs_speak")))
int __aios_vfs_speak_raw(const char* text, int text_len);

__attribute__((import_module("aios"), import_name("kprint")))
void __aios_kprint_raw(const char* msg, int msg_len);

/*
 * High-level convenience functions
 */

static inline char* aios_vfs_read(const char* path) {
    if (!path) return NULL;
    int path_len = (int)strlen(path);
    if (path_len == 0) return NULL;

    char* buf = (char*)malloc(AIOS_VFS_READ_BUF_SIZE);
    if (!buf) return NULL;

    int n = __aios_vfs_read_raw(path, path_len, buf, AIOS_VFS_READ_BUF_SIZE - 1);

    if (n < 0) {
        free(buf);
        return NULL;
    }

    buf[n] = '\0';

    if (n + 1 < AIOS_VFS_READ_BUF_SIZE) {
        char* trimmed = (char*)realloc(buf, (size_t)n + 1);
        return trimmed ? trimmed : buf;
    }

    return buf;
}

static inline char* aios_think(const char* prompt) {
    if (!prompt) return NULL;
    int prompt_len = (int)strlen(prompt);
    if (prompt_len == 0) return NULL;

    char* buf = (char*)malloc(AIOS_THINK_BUF_SIZE);
    if (!buf) return NULL;

    int n = __aios_think_raw(prompt, prompt_len, buf, AIOS_THINK_BUF_SIZE - 1);

    if (n < 0) {
        free(buf);
        return NULL;
    }

    buf[n] = '\0';

    if (n + 1 < AIOS_THINK_BUF_SIZE) {
        char* trimmed = (char*)realloc(buf, (size_t)n + 1);
        return trimmed ? trimmed : buf;
    }

    return buf;
}

static inline void aios_log(const char* msg) {
    if (!msg) return;
    __aios_kprint_raw(msg, (int)strlen(msg));
}

static inline void aios_speak(const char* text) {
    if (!text) return;
    int text_len = (int)strlen(text);
    if (text_len == 0) return;
    __aios_vfs_speak_raw(text, text_len);
}

#ifdef __cplusplus
}
#endif

#endif
