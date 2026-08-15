package com.ouisani.aios.core.permission;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.tool.Tool;
import com.ouisani.aios.core.tool.ToolContext;
import com.ouisani.aios.core.tool.ToolInput;
import com.ouisani.aios.core.tool.ToolOutput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨租户所有权校验单元测试 — 验证 {@code PermissionChecker.checkTenantOwnership} 硬前置
 * 与 {@link VfsManager} 的 {@code ownerTenantId} 盖戳机制。
 * <p>
 * <b>背景</b>：P2 加固项 7 — 资源归属显式化。给 VFS node 加 {@code ownerTenantId} 字段，
 * PermissionChecker 校验 {@code caller.tenantId == target.ownerTenantId}，让"跨租户"语义
 * 不再依赖脆弱的路径子串匹配（子串匹配会被 {@code /tenants/tenantA_evil/} 之类前缀碰撞绕过）。
 * <p>
 * <b>覆盖矩阵</b>：
 * <ul>
 *   <li>跨租户拦截（DEFAULT / BYPASS / DONT_ASK 三模式均不可逃逸 = 硬前置）</li>
 *   <li>同租户放行（ownership 返回 null，落入正常模式分发）</li>
 *   <li>向后兼容：legacy 节点（ownerTenantId=null）/ legacy 调用者（tenantId=null）/ 无 path 工具 / 新建文件</li>
 *   <li>VfsManager 盖戳：writeText(path,content,tenantId) / stampOwnerTenantId（不漂移）/ registerTenantRoot（递归）</li>
 * </ul>
 * <p>
 * <b>测试隔离</b>：VfsManager 是单例，{@code init()} 幂等（{@code if (initialized) return;}）。
 * 每个用例用 {@code System.nanoTime()} 生成唯一路径，避免 pathTree 跨用例污染。
 */
class TenantOwnershipTest {

    private static final String TENANT_A = "tenantA";
    private static final String TENANT_B = "tenantB";

    /** 唯一路径前缀，避免与其它测试注册的物理工作空间（如 /factory）碰撞。 */
    private static final String ROOT = "/tenant-ownership-test";

    @BeforeAll
    static void initVfs() {
        // 幂等：若其它测试类已 init 则 no-op；否则初始化标准目录树
        VfsManager.instance().init();
    }

    // ════════════════════════════════════════════════════════════════
    //  测试桩
    //════════════════════════════════════════════════════════════════

    /** 非只读 file_write 工具桩（带 path 字段，触发 extractPath）。 */
    private static Tool<ToolInput> fileWriteTool() {
        return new Tool<>() {
            @Override public String name() { return "file_write"; }
            @Override public String description() { return "stub"; }
            @Override public String inputSchema() { return "{}"; }
            @Override public ToolOutput call(ToolInput i, ToolContext c) { return ToolOutput.ok("ok"); }
            @Override public boolean readOnly() { return false; }
        };
    }

    /** 构造含 path 字段的工具输入。 */
    private static ToolInput input(String path) {
        return () -> "{\"path\":\"" + path + "\",\"content\":\"x\"}";
    }

    /** 带租户的调用者上下文（5 参规范构造器）。 */
    private static ToolContext ctx(String tenantId) {
        return new ToolContext("agent-" + tenantId, null, "/tmp", null, tenantId);
    }

    /** 生成唯一 VFS 路径，避免单例 pathTree 跨用例污染。 */
    private static String uniquePath(String suffix) {
        return ROOT + "/" + suffix + "/" + System.nanoTime() + "/file.txt";
    }

    // ════════════════════════════════════════════════════════════════
    //  跨租户拦截 — 硬前置（三模式不可逃逸）
    //════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("跨租户写：tenantA 访问 tenantB 文件 → DENY(tenant_ownership, bypassImmune)")
    void crossTenant_write_denied() {
        String path = uniquePath("b-secret");
        assertTrue(VfsManager.instance().writeText(path, "tenantB secret", TENANT_B));

        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);

        PermissionDecision d = checker.checkPermission(fileWriteTool(), input(path), ctx(TENANT_A));
        assertTrue(d.isDenied(), "tenantA 写 tenantB 文件应被所有权硬前置拒绝");
        assertEquals("tenant_ownership", d.reason());
        assertTrue(d.bypassImmune(), "跨租户拒绝应标记 bypassImmune=true（不可被规则覆盖）");
    }

    @Test
    @DisplayName("BYPASS 模式不可逃逸跨租户所有权硬前置")
    void crossTenant_bypassMode_stillDenied() {
        String path = uniquePath("b-secret");
        VfsManager.instance().writeText(path, "tenantB secret", TENANT_B);

        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.BYPASS);

        PermissionDecision d = checker.checkPermission(fileWriteTool(), input(path), ctx(TENANT_A));
        assertTrue(d.isDenied(), "BYPASS 模式不可逃逸跨租户所有权硬前置");
        assertEquals("tenant_ownership", d.reason());
    }

    @Test
    @DisplayName("DONT_ASK 模式不可逃逸跨租户所有权硬前置")
    void crossTenant_dontAskMode_stillDenied() {
        String path = uniquePath("b-secret");
        VfsManager.instance().writeText(path, "tenantB secret", TENANT_B);

        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DONT_ASK);

        PermissionDecision d = checker.checkPermission(fileWriteTool(), input(path), ctx(TENANT_A));
        assertTrue(d.isDenied(), "DONT_ASK 模式不可逃逸跨租户所有权硬前置");
        assertEquals("tenant_ownership", d.reason());
    }

    @Test
    @DisplayName("跨租户拒绝消息含双方 tenantId，便于审计追溯")
    void crossTenant_denialMessageContainsBothTenants() {
        String path = uniquePath("b-secret");
        VfsManager.instance().writeText(path, "tenantB secret", TENANT_B);

        PermissionChecker checker = new PermissionChecker();
        PermissionDecision d = checker.checkPermission(fileWriteTool(), input(path), ctx(TENANT_A));
        String msg = d.message();
        assertTrue(msg.contains(TENANT_A), "拒绝消息应含调用者租户 tenantA");
        assertTrue(msg.contains(TENANT_B), "拒绝消息应含资源归属租户 tenantB");
        assertTrue(msg.contains(path), "拒绝消息应含被拒路径");
    }

    // ════════════════════════════════════════════════════════════════
    //  同租户放行（ownership 返回 null，落入正常模式分发）
    //════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("同租户写：ownership 返回 null → DEFAULT 模式 ASK（证明未误拒也未误放行）")
    void sameTenant_write_notBlockedByOwnership() {
        String path = uniquePath("a-own");
        VfsManager.instance().writeText(path, "tenantA own", TENANT_A);

        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);

        PermissionDecision d = checker.checkPermission(fileWriteTool(), input(path), ctx(TENANT_A));
        assertFalse(d.isDenied(), "同租户不应被所有权拒绝");
        assertNotEquals("tenant_ownership", d.reason());
        assertTrue(d.needsPrompt(), "DEFAULT 模式下非只读 file_write 应 ASK（证明 ownership 未误放行）");
    }

    // ════════════════════════════════════════════════════════════════
    //  向后兼容 — 任一侧 null 一律 skip
    //════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("legacy 节点（ownerTenantId=null）→ skip 所有权校验")
    void legacyNode_nullOwner_notBlocked() {
        String path = uniquePath("legacy-node");
        VfsManager.instance().writeText(path, "legacy"); // 无 tenantId → ownerTenantId=null

        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);

        PermissionDecision d = checker.checkPermission(fileWriteTool(), input(path), ctx(TENANT_A));
        assertFalse(d.isDenied(), "legacy 节点（ownerTenantId=null）应 skip 所有权校验");
        assertNotEquals("tenant_ownership", d.reason());
    }

    @Test
    @DisplayName("legacy 调用者（tenantId=null）→ skip 所有权校验")
    void legacyCaller_nullTenant_notBlocked() {
        String path = uniquePath("b-legacy-caller");
        VfsManager.instance().writeText(path, "tenantB", TENANT_B);

        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);

        // 3 参构造器 → tenantId=null（legacy 调用者）
        ToolContext legacyCtx = new ToolContext("legacy-agent", null, "/tmp");
        PermissionDecision d = checker.checkPermission(fileWriteTool(), input(path), legacyCtx);
        assertFalse(d.isDenied(), "legacy 调用者（tenantId=null）应 skip 所有权校验");
        assertNotEquals("tenant_ownership", d.reason());
    }

    @Test
    @DisplayName("无 path 字段的工具 → extractPath 返回 null → skip 所有权校验")
    void nonFileTool_noPath_notBlocked() {
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);
        ToolInput noPathInput = () -> "{\"message\":\"hello\"}";
        PermissionDecision d = checker.checkPermission(fileWriteTool(), noPathInput, ctx(TENANT_A));
        assertNotEquals("tenant_ownership", d.reason(), "无 path 的工具不应触发所有权校验");
    }

    @Test
    @DisplayName("新建文件（节点不存在）→ resolve 返回 empty → skip 所有权校验")
    void newNode_doesNotExist_skipOwnership() {
        String newPath = uniquePath("new-file");
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);
        PermissionDecision d = checker.checkPermission(fileWriteTool(), input(newPath), ctx(TENANT_A));
        assertNotEquals("tenant_ownership", d.reason(), "新建文件（节点不存在）应 skip 所有权校验");
    }

    // ════════════════════════════════════════════════════════════════
    //  VfsManager 盖戳机制
    //════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("writeText(path, content, tenantId) 给新节点盖租户戳")
    void writeText_withTenantId_stampsNewNode() {
        String path = uniquePath("stamp-on-write");
        assertTrue(VfsManager.instance().writeText(path, "content", TENANT_A));
        var node = VfsManager.instance().resolve(path);
        assertTrue(node.isPresent());
        assertEquals(TENANT_A, node.get().ownerTenantId(),
                "writeText(path, content, tenantId) 应给新节点盖租户戳");
    }

    @Test
    @DisplayName("stampOwnerTenantId 不漂移：已有归属的节点不被重新盖戳")
    void stampOwnerTenantId_noDrift() {
        String path = uniquePath("no-drift");
        VfsManager.instance().writeText(path, "content", TENANT_A);
        // 尝试用 tenantB 覆盖盖戳 → 应失败（不漂移）
        boolean stamped = VfsManager.instance().stampOwnerTenantId(path, TENANT_B);
        assertFalse(stamped, "已有归属的节点不应被重新盖戳（不漂移原则）");
        var node = VfsManager.instance().resolve(path);
        assertEquals(TENANT_A, node.get().ownerTenantId(), "归属应保持 tenantA");
    }

    @Test
    @DisplayName("stampOwnerTenantId 对 legacy 节点（ownerTenantId=null）可盖戳")
    void stampOwnerTenantId_legacyNode_getsStamped() {
        String path = uniquePath("legacy-stamp");
        VfsManager.instance().writeText(path, "legacy"); // ownerTenantId=null
        boolean stamped = VfsManager.instance().stampOwnerTenantId(path, TENANT_A);
        assertTrue(stamped, "legacy 节点（ownerTenantId=null）应可被盖戳");
        var node = VfsManager.instance().resolve(path);
        assertEquals(TENANT_A, node.get().ownerTenantId());
    }

    @Test
    @DisplayName("stampOwnerTenantId 对不存在的路径返回 false")
    void stampOwnerTenantId_missingPath_false() {
        String missing = uniquePath("missing");
        assertFalse(VfsManager.instance().stampOwnerTenantId(missing, TENANT_A),
                "不存在的路径应返回 false");
    }

    @Test
    @DisplayName("stampOwnerTenantId 对 null/blank tenantId 为 no-op")
    void stampOwnerTenantId_nullTenant_noop() {
        String path = uniquePath("null-stamp");
        VfsManager.instance().writeText(path, "legacy");
        assertFalse(VfsManager.instance().stampOwnerTenantId(path, null));
        assertFalse(VfsManager.instance().stampOwnerTenantId(path, "  "));
    }

    @Test
    @DisplayName("registerTenantRoot 递归盖戳子树所有 legacy 节点")
    void registerTenantRoot_stampsSubtree() {
        String root = "/tenant-ownership-root-" + System.nanoTime();
        // 先在 root 下创建几个 legacy 节点（ownerTenantId=null）
        VfsManager.instance().writeText(root + "/a.txt", "a");
        VfsManager.instance().writeText(root + "/sub/b.txt", "b");
        // 注册租户根 → 递归盖戳
        VfsManager.instance().registerTenantRoot(TENANT_A, root);
        assertEquals(TENANT_A, VfsManager.instance().resolve(root).orElseThrow().ownerTenantId(),
                "根目录应被盖戳");
        assertEquals(TENANT_A, VfsManager.instance().resolve(root + "/a.txt").orElseThrow().ownerTenantId(),
                "子文件 a.txt 应被盖戳");
        assertEquals(TENANT_A, VfsManager.instance().resolve(root + "/sub/b.txt").orElseThrow().ownerTenantId(),
                "深层子文件 b.txt 应被盖戳");
        // 注册后 tenantB 跨租户访问应被拒
        PermissionChecker checker = new PermissionChecker();
        checker.setMode(PermissionMode.DEFAULT);
        PermissionDecision d = checker.checkPermission(fileWriteTool(), input(root + "/a.txt"), ctx(TENANT_B));
        assertTrue(d.isDenied(), "registerTenantRoot 后跨租户访问子树应被拒");
        assertEquals("tenant_ownership", d.reason());
    }

    @Test
    @DisplayName("registerTenantRoot 幂等：重复注册不重新盖戳已归属节点")
    void registerTenantRoot_idempotent() {
        String root = "/tenant-ownership-idem-" + System.nanoTime();
        VfsManager.instance().writeText(root + "/a.txt", "a", TENANT_A); // 已盖戳 tenantA
        // 用 tenantB 注册同 root → 已归属的 a.txt 不应漂移
        VfsManager.instance().registerTenantRoot(TENANT_B, root);
        assertEquals(TENANT_A, VfsManager.instance().resolve(root + "/a.txt").orElseThrow().ownerTenantId(),
                "已归属 tenantA 的节点不应被 tenantB 注册漂移");
        // 但未归属的根目录会被 tenantB 盖戳
        assertEquals(TENANT_B, VfsManager.instance().resolve(root).orElseThrow().ownerTenantId());
    }
}
