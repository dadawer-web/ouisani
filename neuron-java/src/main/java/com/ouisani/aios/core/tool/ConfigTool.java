package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.VfsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 配置工具 — 读取或修改 AIOS 配置项，对标 Claude Code 的 ConfigTool。
 * <p>
 * 支持的配置项：
 * <ul>
 *   <li>permissions.mode — 权限模式</li>
 *   <li>model — 默认模型</li>
 *   <li>output.style — 输出风格</li>
 *   <li>sandbox.enabled — 沙箱开关</li>
 *   <li>dream.enabled — 梦境模式开关</li>
 *   <li>compact.threshold — 压缩阈值</li>
 * </ul>
 * <p>
 * 读操作（value 为 null）：从 VFS /etc/config/ 读取当前值。
 * 写操作（value 非 null）：写入 VFS /etc/config/{setting}.json，返回成功。
 * <p>
 * OS 类比：相当于 Linux 的 sysctl — 读取或修改内核运行时参数。
 */
public class ConfigTool implements Tool<ConfigTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(ConfigTool.class);

    /** 配置文件在 VFS 中的根路径 */
    private static final String CONFIG_ROOT = "/etc/config/";

    /** 支持的配置项集合 */
    private static final Set<String> SUPPORTED_SETTINGS = Set.of(
            "permissions.mode",
            "model",
            "output.style",
            "sandbox.enabled",
            "dream.enabled",
            "compact.threshold"
    );

    /**
     * 配置工具输入 — 指定要操作的配置项名称和新值。
     *
     * @param setting 配置项名称（如 "model", "permissions.mode"）
     * @param value   新值，为 null 时表示读取当前值
     */
    public record Input(String setting, String value) implements ToolInput {
        public Input {
            if (setting == null || setting.isBlank()) {
                throw new IllegalArgumentException("setting 不能为空");
            }
        }

        @Override
        public String toJson() {
            String valuePart = value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
            return "{\"setting\":\"" + setting.replace("\"", "\\\"")
                    + "\",\"value\":" + valuePart + "}";
        }
    }

    @Override
    public String name() {
        return "config";
    }

    @Override
    public String description() {
        return "读取或修改 AIOS 配置项";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\",\"properties\":{"
                + "\"setting\":{\"type\":\"string\",\"description\":\"配置项名称\",\"enum\":["
                + "\"permissions.mode\",\"model\",\"output.style\",\"sandbox.enabled\",\"dream.enabled\",\"compact.threshold\"]},"
                + "\"value\":{\"type\":\"string\",\"description\":\"新值，不传或传 null 表示读取当前值\"}"
                + "},\"required\":[\"setting\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        String setting = input.setting();

        // 校验配置项是否受支持
        if (!SUPPORTED_SETTINGS.contains(setting)) {
            return ToolOutput.fail("不支持的配置项: " + setting
                    + "。支持的配置项: " + String.join(", ", SUPPORTED_SETTINGS));
        }

        VfsManager vfs = VfsManager.instance();
        String configPath = CONFIG_ROOT + setting + ".json";

        if (input.value() == null) {
            // 读操作：从 VFS 读取当前配置值
            return handleRead(vfs, setting, configPath);
        } else {
            // 写操作：将新值写入 VFS
            return handleWrite(vfs, setting, configPath, input.value());
        }
    }

    /**
     * 处理读操作 — 从 VFS 读取配置项的当前值。
     *
     * @param vfs        VFS 管理器实例
     * @param setting    配置项名称
     * @param configPath VFS 中的配置文件路径
     * @return 工具执行结果
     */
    private ToolOutput handleRead(VfsManager vfs, String setting, String configPath) {
        String content = vfs.readText(configPath);
        if (content == null) {
            log.info("[ConfigTool] 读取配置项 '{}'：未设置", setting);
            return ToolOutput.ok(setting + " = (未设置)");
        }
        log.info("[ConfigTool] 读取配置项 '{}': {}", setting, content);
        return ToolOutput.ok(setting + " = " + content);
    }

    /**
     * 处理写操作 — 将新值写入 VFS 配置文件。
     *
     * @param vfs        VFS 管理器实例
     * @param setting    配置项名称
     * @param configPath VFS 中的配置文件路径
     * @param value      要写入的新值
     * @return 工具执行结果
     */
    private ToolOutput handleWrite(VfsManager vfs, String setting, String configPath, String value) {
        boolean ok = vfs.writeText(configPath, value);
        if (!ok) {
            log.warn("[ConfigTool] 写入配置项 '{}' 失败", setting);
            return ToolOutput.fail("写入配置项 " + setting + " 失败");
        }
        log.info("[ConfigTool] 写入配置项 '{}': {}", setting, value);
        return ToolOutput.ok("已设置 " + setting + " = " + value);
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String prompt() {
        return "使用 config 工具读取或修改 AIOS 配置项。"
                + "不传 value 时读取当前值，传入 value 时写入新值。"
                + "支持的配置项: permissions.mode, model, output.style, sandbox.enabled, dream.enabled, compact.threshold";
    }
}
