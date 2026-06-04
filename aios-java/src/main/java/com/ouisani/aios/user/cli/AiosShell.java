package com.ouisani.aios.user.cli;

import com.ouisani.aios.core.syscall.SyscallDispatcher;
import com.ouisani.aios.core.syscall.SyscallRequest;
import com.ouisani.aios.core.syscall.SyscallResponse;
import com.ouisani.aios.core.telemetry.SemanticEtw;

import java.util.Map;
import java.util.Scanner;

/**
 * AIOS 超级终端 (The Ultimate General-Purpose AIOS Shell)
 * 结合了底层 Syscall 直调与大模型 Intent Router 的混合命令行
 */
public class AiosShell {

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RED = "\u001B[31m";

    public static void main(String[] args) {
        bootSequence();
        startRepl();
    }

    private static void bootSequence() {
        System.out.println(ANSI_CYAN);
        System.out.println("   ___  _________  _____ ");
        System.out.println("  / _ \\/  _/ __ \\/ ___/ ");
        System.out.println(" / __ _/ // /_/ /\\__ \\  ");
        System.out.println("/_/ |_/___/\\____/____/  ");
        System.out.println("                        ");
        System.out.println("Ouisani General-Purpose AIOS v1.0.0-FINAL" + ANSI_RESET);
        System.out.println("Loading Kernel Modules...");

        try {
            Thread.sleep(300);
            System.out.println(ANSI_GREEN + "[OK] VFS Manager mounted at / (Journaling enabled)" + ANSI_RESET);
            Thread.sleep(200);
            System.out.println(ANSI_GREEN + "[OK] GraalWasm & Docker Sandbox Provider injected" + ANSI_RESET);
            Thread.sleep(200);
            System.out.println(ANSI_GREEN + "[OK] Semantic ETW & Ring0 Impersonation Context active" + ANSI_RESET);
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("\nWelcome to AIOS. Type your intent naturally, or use '/' for raw syscalls.");
        System.out.println("Type 'exit' to halt the system.\n");
    }

    private static void startRepl() {
        Scanner scanner = new Scanner(System.in);
        IntentRouter router = IntentRouter.getInstance();
        SyscallDispatcher dispatcher = SyscallDispatcher.getInstance();

        while (true) {
            System.out.print(ANSI_YELLOW + "aios_root@local" + ANSI_RESET + ":~$ ");
            String input = scanner.nextLine().trim();

            if (input.equalsIgnoreCase("exit")) {
                System.out.println("Initiating system halt... Goodbye.");
                SemanticEtw.getInstance().logEvent("AiosShell", "SYSTEM_HALT", "User exited shell");
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            try {
                // 原生 Syscall 极客模式
                if (input.startsWith("/")) {
                    handleRawSyscall(input, dispatcher);
                }
                // 意图路由自然语言模式
                else {
                    System.out.println(ANSI_CYAN + ">> Routing intent through NUI Engine..." + ANSI_RESET);
                    router.executeNaturalLanguage(input);
                }
            } catch (Exception e) {
                System.out.println(ANSI_RED + "[Kernel Panic] Uncaught user-space exception: " + e.getMessage() + ANSI_RESET);
            }
        }
        scanner.close();
    }

    private static void handleRawSyscall(String input, SyscallDispatcher dispatcher) {
        // Example parsing: /vfs.read path=/dev/camera
        String[] parts = input.substring(1).split(" ", 2);
        String action = parts[0];

        SyscallRequest request;
        if (parts.length > 1) {
            // A simple mock parser for demo purposes. Real impl would use JSON.
            String[] paramPairs = parts[1].split("=", 2);
            if (paramPairs.length == 2) {
                request = new SyscallRequest(action, Map.of(paramPairs[0], paramPairs[1]));
            } else {
                request = new SyscallRequest(action, Map.of("payload", parts[1]));
            }
        } else {
            request = new SyscallRequest(action, Map.of());
        }

        System.out.println(ANSI_CYAN + ">> Executing Raw Syscall: " + action + ANSI_RESET);
        SyscallResponse response = dispatcher.execute("root_cli", request);

        if (response.success()) {
            System.out.println(ANSI_GREEN + "Response: " + response.data() + ANSI_RESET);
        } else {
            System.out.println(ANSI_RED + "Error: " + response.errorMessage() + ANSI_RESET);
        }
    }
}
