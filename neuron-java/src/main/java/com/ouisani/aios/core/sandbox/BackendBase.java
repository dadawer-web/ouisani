package com.ouisani.aios.core.sandbox;

import java.util.List;

/**
 * 沙箱执行后端抽象 — AIOS 的「执行后端」可插拔接口。
 * <p>
 * 借鉴 AgentScope 的 {@code workspace/_base.py::BackendBase}：把所有
 * 文件 I/O 与 shell 执行抽象成七个原语，工具代码只依赖本接口，不感知
 * 后端是本地文件系统、Docker 容器、E2B 云沙箱、OpenSandbox 还是 Daytona。
 * <p>
 * <b>与 VfsManager 的分工</b>：
 * <ul>
 *   <li>{@code VfsManager} 是更高层的 VFS 命名空间抽象（语义/向量/图形设备节点、
 *       chroot 命名空间、OverlayFS、VSS 快照），负责路径树与虚拟设备；</li>
 *   <li>{@code BackendBase} 是更基础的「执行后端」抽象，负责把路径字符串翻译
 *       为后端可识别的形式并落地为真实的 I/O 与进程执行。</li>
 * </ul>
 * 同一 VFS 路径可路由到不同后端：{@code LocalBackend} 直接在宿主机执行，
 * {@code DockerBackend}（预留）将命令通过 {@code docker exec} 路由到容器内，
 * {@code E2BBackend}（预留）路由到云沙箱。工具不感知路由细节。
 * <p>
 * <b>七个原语</b>（对标 AgentScope BackendBase）：
 * <ol>
 *   <li>{@link #write_file} — 写文件</li>
 *   <li>{@link #read_file} — 读文件</li>
 *   <li>{@link #exec_shell} — 执行 shell 命令</li>
 *   <li>{@link #join_path} — 路径拼接</li>
 *   <li>{@link #file_exists} — 判断路径存在</li>
 *   <li>{@link #list_dir} — 列出目录</li>
 *   <li>{@link #delete_path} — 删除路径</li>
 * </ol>
 * <p>
 * OS 类比：相当于 Linux 的 VFS 操作集（{@code struct file_operations}）+
 * {@code execve()} 的组合 — 屏蔽底层"在哪执行"的差异。
 *
 * @see LocalBackend
 * @see ExecOptions
 * @see ExecResult
 */
public interface BackendBase {

    /**
     * 写文件 — 覆盖写入，父目录不存在时自动创建。
     *
     * @param path    后端可识别的路径（VFS 路径或后端原生路径）
     * @param content 文本内容
     * @return true 写入成功
     */
    boolean write_file(String path, String content);

    /**
     * 读文件 — 返回文本内容。
     *
     * @param path 后端可识别的路径
     * @return 文件文本内容；不存在或无权限返回 null
     */
    String read_file(String path);

    /**
     * 执行 shell 命令 — 在后端环境中执行。
     * <p>
     * 实现必须遵循 {@link ExecOptions} 的超时/工作目录/环境变量约束，
     * 并通过 {@link ExecResult} 返回退出码与 stdout（合并 stderr）。
     *
     * @param command 要执行的 shell 命令字符串
     * @param options 执行选项（超时、工作目录、环境变量、输出截断）
     * @return 执行结果（含退出码、输出、超时标志）
     */
    ExecResult exec_shell(String command, ExecOptions options);

    /** 便捷重载：使用默认选项（无超时、当前工作目录）。 */
    default ExecResult exec_shell(String command) {
        return exec_shell(command, ExecOptions.DEFAULT);
    }

    /**
     * 路径拼接 — 在后端命名空间下拼接路径片段。
     * <p>
     * LocalBackend 等价于 {@code Path.of(base, child).toString()}；
     * 容器后端可能需要做挂载点映射。
     *
     * @param base  基础路径
     * @param child 子路径片段（可多个）
     * @return 拼接后的路径字符串
     */
    String join_path(String base, String... child);

    /**
     * 判断路径是否存在。
     *
     * @param path 后端可识别的路径
     * @return true 存在
     */
    boolean file_exists(String path);

    /**
     * 列出目录下的子项。
     *
     * @param dirPath 目录路径
     * @return 子项名称列表；目录不存在时返回空列表
     */
    List<String> list_dir(String dirPath);

    /**
     * 删除路径（文件或空目录）。
     *
     * @param path 要删除的路径
     * @return true 删除成功；不存在也返回 false
     */
    boolean delete_path(String path);

    /**
     * 后端名称 — 用于日志、TracingSpan、UpstreamMeta 标识。
     * <p>
     * 例如：{@code "local"}、{@code "docker:python:3.10"}、{@code "e2b"}。
     */
    String backendName();
}
