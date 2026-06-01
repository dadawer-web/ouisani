#include <stdint.h>
#include <string.h>

#define BPF_INPUT_OFFSET  262144
#define BPF_OUTPUT_OFFSET 327680
#define BPF_MAX_OUTPUT    65536

static int is_malicious(const char *input) {
    const char *patterns[] = {
        "rm -rf", "rm -fr", "chmod 777", "chmod 666",
        "/bin/sh", "/bin/bash", "/bin/su",
        "Drop Table", "DROP TABLE", "drop table",
        "DELETE FROM", "delete from",
        "format C:", "mkfs.", "dd if=",
        ":(){ :|:& };:", "wget http", "curl http",
        "/dev/null >", "> /dev/sda",
        NULL
    };
    for (int i = 0; patterns[i] != NULL; i++) {
        if (strstr(input, patterns[i]) != NULL) return 1;
    }
    return 0;
}

static int has_academic_keyword(const char *input) {
    const char *keywords[] = {
        "FAT\xe8\xa1\xa8",
        "\xe6\xad\xbb\xe9\x94\x81",
        "LL(1)\xe6\x96\x87\xe6\xb3\x95",
        NULL
    };
    for (int i = 0; keywords[i] != NULL; i++) {
        if (strstr(input, keywords[i]) != NULL) return 1;
    }
    return 0;
}

static int has_marker(const char *input) {
    const char *marker = "\xe3\x80\x90\xe5\x90\x8d\xe5\xb8\x88";
    return strstr(input, marker) != NULL;
}

__attribute__((export_name("bpf_filter")))
int bpf_filter(int input_offset, int input_len) {
    char *mem = (char *)(uintptr_t)0;
    char *input = mem + input_offset;

    if (is_malicious(input)) {
        const char *drop_marker = "[KERNEL_DROP_MALICIOUS_INTENT]";
        int marker_len = (int)strlen(drop_marker);
        char *out = mem + BPF_OUTPUT_OFFSET;
        int copy_len = marker_len < BPF_MAX_OUTPUT ? marker_len : BPF_MAX_OUTPUT - 1;
        memcpy(out, drop_marker, copy_len);
        return copy_len;
    }

    if (has_marker(input)) {
        return -1;
    }

    if (!has_academic_keyword(input)) {
        return -1;
    }

    const char *prefix =
        "\xe3\x80\x90"
        "\xe5\x90\x8d\xe5\xb8\x88\xe4\xb8\xa5\xe8\xb0\xa8\xe6\xa8\xa1\xe5\xbc\x8f\xe8\xa7\xa6\xe5\x8f\x91"
        "\xe3\x80\x91"
        "\xe8\xaf\xb7\xe4\xbb\xa5\xe6\x93\x8d\xe4\xbd\x9c\xe7\xb3\xbb\xe7\xbb\x9f\xe4\xb8\x8e\xe7\xbc\x96\xe8\xaf\x91\xe5\x8e\x9f\xe7\x90\x86\xe7\x9a\x84"
        "\xe5\xad\xa6\xe6\x9c\xaf\xe8\xa7\x86\xe8\xa7\x92\xef\xbc\x8c\xe8\xaf\xa6\xe7\xbb\x86\xe8\xa7\xa3\xe6\x9e\x90\xe4\xbb\xa5\xe4\xb8\x8b\xe9\x97\xae\xe9\xa2\x98"
        "\xef\xbc\x8c\xe6\x8b\x92\xe7\xbb\x9d\xe5\xba\x9f\xe8\xaf\x9d\xef\xbc\x9a";

    int prefix_len = (int)strlen(prefix);
    char *out = mem + BPF_OUTPUT_OFFSET;
    int out_len = 0;

    if (prefix_len + input_len < BPF_MAX_OUTPUT) {
        memcpy(out, prefix, prefix_len);
        out_len += prefix_len;
        memcpy(out + out_len, input, input_len);
        out_len += input_len;
    } else {
        int copy_len = input_len;
        if (prefix_len + copy_len >= BPF_MAX_OUTPUT) {
            copy_len = BPF_MAX_OUTPUT - prefix_len - 1;
        }
        memcpy(out, prefix, prefix_len);
        out_len += prefix_len;
        memcpy(out + out_len, input, copy_len);
        out_len += copy_len;
    }

    return out_len;
}
