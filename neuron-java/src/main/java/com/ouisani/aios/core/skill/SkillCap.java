package com.ouisani.aios.core.skill;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;

/**
 * 技能能力元数据 — Skill 升级为结构化 Cap 模型的载体。
 * <p>
 * 借鉴 nuwa Cap 模型（res.locals.cap 中间件链元数据）+ Claude Code SKILL.md frontmatter，
 * 把原本散落在自然语言 prompt 里的<b>能力契约字段</b>提取为强类型结构，使 Skill 可被
 * 调度器/沙箱/权限层在<b>加载时</b>决策（而非运行时让 LLM 自由解释）。
 *
 * <h3>四个新增字段（用户需求）</h3>
 * <table>
 *   <tr><th>字段</th><th>类型</th><th>用途</th></tr>
 *   <tr><td>{@link #author}</td>
 *       <td>String（命名空间）</td>
 *       <td>发布者命名空间，如 {@code "oushani.core"} / {@code "vendor.acme.tools"} / {@code "user.alice"}。
 *           与 {@link #providerId} 配合做来源校验：providerId=VENDOR 时 author 应为 vendor 命名空间。
 *           缺失时降级为 {@code "unknown"}（best-effort）。</td></tr>
 *   <tr><td>{@link #artifactSrcUrl}</td>
 *       <td>URI（可空）</td>
 *       <td>远程代码载荷 URL（http/https/file），允许 Skill 加载远程 helper 代码而非仅本目录
 *           的 .py 文件。{@code null} 表示该 Skill 仅含 prompt 无远程载荷。
 *           实际抓取由 {@link SkillArtifactLoader} 完成（带缓存 + 大小上限 + SHA256 校验）。</td></tr>
 *   <tr><td>{@link #supportedInputs}</td>
 *       <td>List&lt;String&gt;</td>
 *       <td>支持的输入模态，v1 强制为 {@code ["text"]}（与现有 LLM prompt-only 路径对齐）。
 *           v2 计划扩展 image/audio，当前枚举只允许 "text"，其他值会被规范化为 {@code ["text"]}。</td></tr>
 *   <tr><td>{@link #providerId}</td>
 *       <td>{@link ProviderId}</td>
 *       <td>发布者身份枚举。与 SkillSource（加载来源）正交：同文件可 USER 目录加载但
 *           providerId=COMMUNITY。缺失时降级为 {@link ProviderId#AIOS_CORE}（零回归）。</td></tr>
 * </table>
 *
 * <h3>双读模式（与 aios_roles/*.yaml 对齐）</h3>
 * 仿 {@link com.ouisani.aios.core.role.RoleBlueprintLoader} 范式：
 * <ul>
 *   <li><b>原文 prompt 侧</b> — {@link SkillLoader.SkillDef#content()}/{@code body()} 不变，
 *       SkillLoader 的 prompt 拼接路径完全保留</li>
 *   <li><b>结构化字段侧</b> — 本类提供 4 个强类型字段，供调度器/沙箱/权限层加载时决策</li>
 * </ul>
 * 两侧并存，零回归：缺新字段的存量 SKILL.md 仍能正常加载，{@link SkillCap#DEFAULT}
 * 给出全默认值（author="unknown"、artifactSrcUrl=null、supportedInputs=["text"]、
 * providerId=AIOS_CORE）。
 *
 * <h3>OS 类比</h3>
 * 相当于 Linux ELF 的 {@code .note.gnu.build-id} + {@code PT_INTERP} 段 ——
 * 加载器在 mmap 之前就能读到能力声明，决定是否注入 / 是否隔离。
 *
 * @see SkillLoader.SkillDef
 * @see ProviderId
 * @see SkillArtifactLoader
 * @see com.ouisani.aios.core.role.RoleBlueprint
 */
public record SkillCap(
        String author,
        URI artifactSrcUrl,
        List<String> supportedInputs,
        ProviderId providerId
) {

    /** v1 强制唯一支持的输入模态。其他值会被规范化为 {@code ["text"]}。 */
    public static final List<String> SUPPORTED_INPUTS_V1 = List.of("text");

    /** 默认哨兵 —— frontmatter 缺所有 Cap 字段时降级到此（零回归）。 */
    public static final SkillCap DEFAULT = new SkillCap(
            "unknown", null, SUPPORTED_INPUTS_V1, ProviderId.AIOS_CORE);

    /**
     * 紧凑构造器 — 兜底 null + 输入模态规范化。
     * <ul>
     *   <li>author null → "unknown"</li>
     *   <li>supportedInputs null/空/含非 "text" 值 → {@link #SUPPORTED_INPUTS_V1}</li>
     *   <li>providerId null → {@link ProviderId#AIOS_CORE}</li>
     * </ul>
     */
    public SkillCap {
        if (author == null || author.isBlank()) author = "unknown";
        if (supportedInputs == null || supportedInputs.isEmpty()
                || !supportedInputs.equals(SUPPORTED_INPUTS_V1)) {
            // v1 仅接受 ["text"]，其他模态（image/audio/...）降级为 ["text"]
            supportedInputs = SUPPORTED_INPUTS_V1;
        } else {
            supportedInputs = List.copyOf(supportedInputs);
        }
        if (providerId == null) providerId = ProviderId.AIOS_CORE;
    }

    /**
     * 工厂 — 从 frontmatter 字符串构造（{@link SkillLoader#parseSkillMd} 调用）。
     * <p>
     * 所有参数 best-effort 解析，永不抛异常（保证加载链不中断）：
     * <ul>
     *   <li>{@code artifactSrcUrl} 非法 URL → 降级为 null（视为无远程载荷）</li>
     *   <li>{@code supportedInputs} 任意格式 → 规范化为 {@link #SUPPORTED_INPUTS_V1}</li>
     * </ul>
     *
     * @param author             命名空间字符串（可空）
     * @param artifactSrcUrlRaw  远程 URL 原文（可空）
     * @param supportedInputsRaw 输入模态列表原文（可空，v1 强制 ["text"]）
     * @param providerIdRaw      provider 字符串（可空，未知值降级 AIOS_CORE）
     * @return 规范化后的 SkillCap；全空 → {@link #DEFAULT}
     */
    public static SkillCap of(String author, String artifactSrcUrlRaw,
                              List<String> supportedInputsRaw, String providerIdRaw) {
        URI srcUrl = parseUriBestEffort(artifactSrcUrlRaw);
        List<String> inputs = supportedInputsRaw == null || supportedInputsRaw.isEmpty()
                ? SUPPORTED_INPUTS_V1
                : supportedInputsRaw; // 紧凑构造器会再次规范化为 ["text"]
        return new SkillCap(
                author,
                srcUrl,
                inputs,
                ProviderId.fromString(providerIdRaw));
    }

    /** 解析 URL，非法/空 → null（best-effort，不抛）。仅允许 http/https/file scheme。 */
    private static URI parseUriBestEffort(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI u = URI.create(raw.trim());
            String scheme = u.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase();
            if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("file")) {
                return null;
            }
            return u;
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /**
     * 是否含远程代码载荷。
     * <p>
     * 返回 true 时调度器应通过 {@link SkillArtifactLoader#fetch(SkillCap)} 拉取代码，
     * 并按 {@link #providerId} 决定沙箱策略（VENDOR/COMMUNITY 默认隔离执行）。
     */
    public boolean hasRemoteArtifact() {
        return artifactSrcUrl != null;
    }

    /**
     * 校验 author 命名空间与 providerId 一致性。
     * <p>
     * 约定：providerId=VENDOR 时 author 应以 {@code "vendor."} 前缀开头；
     * providerId=AIOS_CORE 时 author 应为 {@code "oushani.*"} 或 {@code "unknown"}。
     * <p>
     * <b>当前仅返回布尔，不抛异常</b>（best-effort 警告，加载链不中断）。
     * 后续可由 governance 层在加载时拒绝不一致的 Skill。
     *
     * @return true 表示一致或无约束；false 表示不一致（应记 WARN）
     */
    public boolean isAuthorConsistentWithProvider() {
        if (providerId == ProviderId.VENDOR) {
            return author.startsWith("vendor.") || author.startsWith("unknown");
        }
        if (providerId == ProviderId.AIOS_CORE) {
            return author.startsWith("oushani.") || author.equals("unknown");
        }
        return true; // COMMUNITY / USER 无强制前缀约束
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SkillCap that)) return false;
        return Objects.equals(author, that.author)
                && Objects.equals(artifactSrcUrl, that.artifactSrcUrl)
                && Objects.equals(supportedInputs, that.supportedInputs)
                && providerId == that.providerId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(author, artifactSrcUrl, supportedInputs, providerId);
    }
}
