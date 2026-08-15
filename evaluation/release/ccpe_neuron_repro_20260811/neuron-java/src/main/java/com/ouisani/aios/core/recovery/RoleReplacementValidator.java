package com.ouisani.aios.core.recovery;

import com.ouisani.aios.core.permission.PermissionProfileComparator;
import com.ouisani.aios.core.role.RoleBlueprint;
import com.ouisani.aios.core.role.RoleBlueprintLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 角色替换校验器 — 恢复动作的"角色级权限闸门"，对标工具级 {@link com.ouisani.aios.core.permission.PermissionChecker}。
 * <p>
 * <b>洞2 背景</b>：{@link TopologyMutationStrategy} 读 core dump 喂给 LLM 诊断，LLM 回复 JSON 里的
 * {@code suggested_role} 被直接拿去 {@code WorkflowEngine.resumeNode()} 完成节点角色替换，<b>全程零权限校验</b>。
 * 若攻击者让 core dump 混入诱导 LLM"误判"的内容，诊断 LLM 可吐出越权角色（如 {@code admin}、BYPASS 模式角色），
 * 系统直接把节点换成该角色，无人拦截。
 * <p>
 * <b>本类职责</b>（defense #2 恢复动作重过权限 + defense #3 角色替换白名单化）：
 * <ol>
 *   <li><b>存在性白名单</b>（defense #3）：{@code suggestedRole} 必须是 {@code aios_roles/*.yaml}
 *       中注册的已知角色 —— 不是 LLM 随口生成的任意字符串。未知角色一律拒绝。</li>
 *   <li><b>非越权校验</b>（defense #2）：若已知当前角色，目标角色的权限分数不得高于当前 ——
 *       即"恢复动作"与"正常动作"走同一套权限管道，恢复通道不开后门。由
 *       {@link PermissionProfileComparator} 计算"角色可达最小权限集合"的代理约束。</li>
 * </ol>
 * <p>
 * <b>契约</b>：当前角色名由 {@link RecoveryContext#metadata()} 的 {@code currentRole} 键携带
 * （生产侧由恢复编排器从 {@code failedNode.role()} 填充）。缺失时仅做存在性白名单（无法判定越权），
 * 不保守拒绝 —— 因为存在性检查已挡住未知角色，这是最关键的一层。
 *
 * @see PermissionProfileComparator
 * @see TopologyMutationStrategy
 */
public final class RoleReplacementValidator {

    private static final Logger log = LoggerFactory.getLogger(RoleReplacementValidator.class);

    /** 默认角色蓝图目录（相对模块根）。 */
    private static final Path DEFAULT_ROLES_DIR = Paths.get("aios_roles");

    /** 校验结果。 */
    public record Result(boolean valid, String suggestedRole, String reason, String category) {
        static final String OK = "OK";
        static final String EMPTY_ROLE = "EMPTY_ROLE";
        static final String UNKNOWN_ROLE = "UNKNOWN_ROLE";
        static final String PRIVILEGE_ESCALATION = "PRIVILEGE_ESCALATION";

        static Result accept(String role, String reason) {
            return new Result(true, role, reason, OK);
        }

        static Result reject(String role, String reason, String category) {
            return new Result(false, role, reason, category);
        }
    }

    private final Map<String, RoleBlueprint> roles;
    private final PermissionProfileComparator comparator;

    /**
     * 生产构造器 —— 从默认 {@code aios_roles/} 目录加载角色白名单。
     */
    public RoleReplacementValidator() {
        this(RoleBlueprintLoader.loadAll(DEFAULT_ROLES_DIR));
    }

    /**
     * 测试构造器 —— 注入角色白名单（避免文件系统依赖）。
     */
    public RoleReplacementValidator(Map<String, RoleBlueprint> roles) {
        this(roles, new PermissionProfileComparator());
    }

    /** 完整注入构造器（测试可注入自定义比较器）。 */
    RoleReplacementValidator(Map<String, RoleBlueprint> roles, PermissionProfileComparator comparator) {
        this.roles = roles == null ? Map.of() : roles;
        this.comparator = comparator;
    }

    /** 已注册角色白名单（不可变视图）。 */
    public Map<String, RoleBlueprint> registeredRoles() {
        return roles;
    }

    /**
     * 校验 suggested_role 是否可安全替换 currentRole。
     * <p>
     * 决策顺序（短路）：
     * <ol>
     *   <li>空角色 → 拒绝（EMPTY_ROLE）</li>
     *   <li>白名单不存在 → 拒绝（UNKNOWN_ROLE）—— 挡住 LLM 随口编的 {@code admin}</li>
     *   <li>当前角色已知且目标越权 → 拒绝（PRIVILEGE_ESCALATION）</li>
     *   <li>否则放行</li>
     * </ol>
     *
     * @param currentRole  当前角色名；null/未知 → 跳过越权判定，仅做存在性检查
     * @param suggestedRole LLM 建议的目标角色名
     * @return 校验结果（valid=true 可替换；valid=false 附拒绝类别）
     */
    public Result validate(String currentRole, String suggestedRole) {
        if (suggestedRole == null || suggestedRole.isBlank()) {
            return Result.reject(suggestedRole, "suggested_role 为空，无法替换", Result.EMPTY_ROLE);
        }
        String target = suggestedRole.trim();

        // ── defense #3：存在性白名单 ──
        RoleBlueprint targetBp = roles.get(target);
        if (targetBp == null) {
            log.warn("[RoleReplacementValidator] 拒绝未知角色（不在 aios_roles 白名单）: {}", target);
            return Result.reject(target,
                    "suggested_role '" + target + "' 不在已注册角色白名单，疑似 LLM 被污染或编造",
                    Result.UNKNOWN_ROLE);
        }

        // ── defense #2：非越权校验 ──
        if (currentRole != null && !currentRole.isBlank()) {
            RoleBlueprint currentBp = roles.get(currentRole.trim());
            if (currentBp != null && comparator.isPrivilegeEscalation(currentBp, targetBp)) {
                log.warn("[RoleReplacementValidator] 拒绝越权替换: {} → {}", currentRole, target);
                return Result.reject(target,
                        "privilege escalation: " + currentRole + " → " + target
                                + "（恢复通道不得借角色替换提权）",
                        Result.PRIVILEGE_ESCALATION);
            }
        }

        return Result.accept(target,
                "role within privilege bounds of current role: "
                        + (currentRole == null ? "(unknown current)" : currentRole));
    }
}
