/**
 * aios.h — AIOS C SDK for GraalWasm Sandbox
 *
 * This header declares the host functions injected by the GraalVM WASM runtime
 * and provides friendly inline wrappers for Agent developers.
 *
 * Usage:
 *   #include "aios.h"
 *
 *   int main() {
 *       char buf[1024];
 *       aios_read_file("/proc/agents", buf, sizeof(buf));
 *       aios_think("What is the meaning of life?", buf, sizeof(buf));
 *       aios_log(42);
 *       return 0;
 *   }
 *
 * Compile:
 *   clang --target=wasm32 -nostdlib -Wl,--no-entry -Wl,--export-all \
 *         -o agent.wasm agent.c
 */

#ifndef AIOS_H
#define AIOS_H

#ifdef __cplusplus
extern "C" {
#endif

/* ========================================================================
 *  GraalWasm Host Function Imports
 *  These are provided by the aios_env module inside the GraalVM WASM sandbox.
 *  Do NOT call them directly — use the inline wrappers below instead.
 * ======================================================================== */

/**
 * Read from a VFS path into a buffer.
 * @param path    Virtual file system path (e.g. "/proc/agents", "/dev/camera0")
 * @param buffer  Output buffer to receive the file content
 * @param max_len Maximum number of bytes to read
 * @return        Number of bytes actually read, or -1 on error
 */
__attribute__((import_module("aios_env"), import_name("__aios_vfs_read")))
extern int __aios_vfs_read(const char* path, char* buffer, int max_len);

/**
 * Send a prompt to the LLM and receive a text response.
 * @param prompt  Null-terminated prompt string
 * @param buffer  Output buffer to receive the LLM response
 * @param max_len Maximum number of bytes to write into buffer
 * @return        Number of bytes in the response, or -1 on error
 */
__attribute__((import_module("aios_env"), import_name("__aios_think")))
extern int __aios_think(const char* prompt, char* buffer, int max_len);

/**
 * Log an integer value to the AIOS kernel log.
 * @param val  The integer value to log
 */
__attribute__((import_module("aios_env"), import_name("__aios_log")))
extern void __aios_log(int val);


/* ========================================================================
 *  Developer-Friendly Inline Wrappers
 * ======================================================================== */

/**
 * Read a virtual file from the AIOS VFS.
 *
 * Example:
 *   char buf[1024];
 *   aios_read_file("/proc/agents", buf, sizeof(buf));
 */
static inline void aios_read_file(const char* path, char* out_buf, int max_len) {
    __aios_vfs_read(path, out_buf, max_len);
}

/**
 * Ask the LLM a question and get a text response.
 *
 * Example:
 *   char answer[2048];
 *   aios_think("Summarize the system status", answer, sizeof(answer));
 */
static inline void aios_think(const char* prompt, char* out_buf, int max_len) {
    __aios_think(prompt, out_buf, max_len);
}

/**
 * Log an integer value to the kernel log.
 *
 * Example:
 *   aios_log(42);
 */
static inline void aios_log(int val) {
    __aios_log(val);
}

#ifdef __cplusplus
}
#endif

#endif /* AIOS_H */
