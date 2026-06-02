#include <stdint.h>
#include <string.h>

#define BPF_INPUT_OFFSET  262144
#define BPF_OUTPUT_OFFSET 327680
#define BPF_MAX_OUTPUT    65536

static int is_malicious(const char *input) {
    const char *patterns[] = {
        "rm -rf",
        "rm -fr",
        "chmod 777",
        "chmod 666",
        "/bin/sh",
        "/bin/bash",
        "/bin/su",
        "Drop Table",
        "DROP TABLE",
        "drop table",
        "DELETE FROM",
        "delete from",
        "format C:",
        "mkfs.",
        "dd if=",
        ":(){ :|:& };:",
        "wget http",
        "curl http",
        "/dev/null >",
        "> /dev/sda",
        NULL
    };

    for (int i = 0; patterns[i] != NULL; i++) {
        if (strstr(input, patterns[i]) != NULL) {
            return 1;
        }
    }
    return 0;
}

__attribute__((export_name("bpf_filter")))
int bpf_filter(int input_offset, int input_len) {
    char *mem = (char *)(uintptr_t)0;
    char *input = mem + input_offset;

    if (!is_malicious(input)) {
        return -1;
    }

    const char *drop_marker = "[KERNEL_DROP_MALICIOUS_INTENT]";
    int marker_len = (int)strlen(drop_marker);

    char *out = mem + BPF_OUTPUT_OFFSET;
    int copy_len = marker_len;
    if (copy_len >= BPF_MAX_OUTPUT) {
        copy_len = BPF_MAX_OUTPUT - 1;
    }
    memcpy(out, drop_marker, copy_len);

    return copy_len;
}
