package com.ouisani.aios.core.tool;

import com.ouisani.aios.core.VfsManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;

/**
 * Notebook 编辑工具 — 编辑 Jupyter Notebook 单元格。
 * <p>
 * 通过 VFS 读取 .ipynb 文件（JSON 格式），解析后对单元格执行
 * 替换、插入、删除操作，再写回 VFS。
 * <p>
 * 支持三种编辑模式：
 * - replace：替换指定单元格的内容和/或类型
 * - insert：在指定位置插入新单元格
 * - delete：删除指定单元格
 * <p>
 * OS 类比：相当于对 /proc/notebook 的 ioctl 操作 — 精确控制
 * Notebook 文件的内部结构，而非粗暴地整体覆写。
 */
public class NotebookEditTool implements Tool<NotebookEditTool.Input> {

    private static final Logger log = LoggerFactory.getLogger(NotebookEditTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 工具输入参数。
     *
     * @param notebook_path Notebook 文件在 VFS 中的路径
     * @param cell_id       单元格 ID（可选，用于 replace/delete 模式定位目标单元格）
     * @param new_source    新的单元格源代码内容
     * @param cell_type     单元格类型：code 或 markdown
     * @param edit_mode     编辑模式：replace、insert 或 delete
     */
    public record Input(
            String notebook_path,
            String cell_id,
            String new_source,
            String cell_type,
            String edit_mode
    ) implements ToolInput {

        public Input {
            if (notebook_path == null || notebook_path.isBlank()) {
                throw new IllegalArgumentException("notebook_path 不能为空");
            }
            if (edit_mode == null || edit_mode.isBlank()) {
                edit_mode = "replace";
            }
            if (cell_type == null || cell_type.isBlank()) {
                cell_type = "code";
            }
            if (new_source == null) {
                new_source = "";
            }
            // 校验 cell_type 合法性
            if (!"code".equals(cell_type) && !"markdown".equals(cell_type)) {
                throw new IllegalArgumentException("cell_type 必须为 code 或 markdown，当前值: " + cell_type);
            }
            // 校验 edit_mode 合法性
            if (!"replace".equals(edit_mode) && !"insert".equals(edit_mode) && !"delete".equals(edit_mode)) {
                throw new IllegalArgumentException("edit_mode 必须为 replace、insert 或 delete，当前值: " + edit_mode);
            }
        }

        @Override
        public String toJson() {
            return "{\"notebook_path\":\"" + notebook_path.replace("\"", "\\\"")
                    + "\",\"cell_id\":" + (cell_id == null ? "null" : "\"" + cell_id.replace("\"", "\\\"") + "\"")
                    + ",\"new_source\":\"" + new_source.replace("\"", "\\\"").replace("\n", "\\n")
                    + "\",\"cell_type\":\"" + cell_type
                    + "\",\"edit_mode\":\"" + edit_mode + "\"}";
        }
    }

    @Override
    public String name() {
        return "notebook_edit";
    }

    @Override
    public String description() {
        return "编辑 Jupyter Notebook 单元格";
    }

    @Override
    public String inputSchema() {
        return "{\"type\":\"object\","
                + "\"properties\":{"
                + "\"notebook_path\":{\"type\":\"string\",\"description\":\"Notebook 文件在 VFS 中的路径\"},"
                + "\"cell_id\":{\"type\":\"string\",\"description\":\"目标单元格 ID（replace/delete 模式必填，insert 模式可选）\"},"
                + "\"new_source\":{\"type\":\"string\",\"description\":\"新的单元格源代码内容\"},"
                + "\"cell_type\":{\"type\":\"string\",\"enum\":[\"code\",\"markdown\"],\"description\":\"单元格类型，默认 code\"},"
                + "\"edit_mode\":{\"type\":\"string\",\"enum\":[\"replace\",\"insert\",\"delete\"],\"description\":\"编辑模式，默认 replace\"}"
                + "},"
                + "\"required\":[\"notebook_path\",\"edit_mode\"]}";
    }

    @Override
    public ToolOutput call(Input input, ToolContext context) {
        try {
            VfsManager vfs = VfsManager.instance();

            // ── 读取 Notebook 文件 ──
            if (!vfs.exists(input.notebook_path())) {
                return ToolOutput.fail("Notebook 文件不存在: " + input.notebook_path());
            }

            String content = vfs.readText(input.notebook_path());
            if (content == null) {
                return ToolOutput.fail("无法读取 Notebook 文件（权限不足或读取失败）: " + input.notebook_path());
            }

            // ── 解析 JSON ──
            ObjectNode nbRoot = (ObjectNode) MAPPER.readTree(content);
            ArrayNode cells = (ArrayNode) nbRoot.get("cells");
            if (cells == null) {
                return ToolOutput.fail("无效的 Notebook 格式：缺少 cells 数组");
            }

            // ── 根据编辑模式执行操作 ──
            return switch (input.edit_mode()) {
                case "replace" -> handleReplace(cells, input, vfs, nbRoot);
                case "insert"  -> handleInsert(cells, input, vfs, nbRoot);
                case "delete"  -> handleDelete(cells, input, vfs, nbRoot);
                default        -> ToolOutput.fail("不支持的编辑模式: " + input.edit_mode());
            };

        } catch (Exception e) {
            log.error("[NotebookEditTool] 编辑 Notebook 失败", e);
            return ToolOutput.fail("编辑 Notebook 失败: " + e.getMessage());
        }
    }

    /**
     * 替换模式 — 替换指定单元格的内容和/或类型。
     */
    private ToolOutput handleReplace(ArrayNode cells, Input input, VfsManager vfs, ObjectNode nbRoot) {
        if (input.cell_id() == null || input.cell_id().isBlank()) {
            return ToolOutput.fail("replace 模式需要指定 cell_id");
        }

        int index = findCellIndex(cells, input.cell_id());
        if (index < 0) {
            return ToolOutput.fail("未找到单元格: cell_id=" + input.cell_id());
        }

        // 替换单元格内容
        ObjectNode cell = (ObjectNode) cells.get(index);
        cell.put("cell_type", input.cell_type());

        // 替换 source 字段（支持字符串或字符串数组格式）
        ArrayNode sourceArray = MAPPER.createArrayNode();
        String[] lines = input.new_source().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sourceArray.add(i < lines.length - 1 ? lines[i] + "\n" : lines[i]);
        }
        cell.set("source", sourceArray);

        // 写回 VFS
        return writeBack(vfs, input.notebook_path(), nbRoot,
                "已替换单元格 [" + input.cell_id() + "]，类型=" + input.cell_type());
    }

    /**
     * 插入模式 — 在指定位置插入新单元格。
     * 如果提供了 cell_id，则在该单元格之后插入；否则追加到末尾。
     */
    private ToolOutput handleInsert(ArrayNode cells, Input input, VfsManager vfs, ObjectNode nbRoot) {
        // 构建新单元格
        ObjectNode newCell = MAPPER.createObjectNode();
        String newCellId = "cell_" + System.currentTimeMillis();
        newCell.put("id", newCellId);
        newCell.put("cell_type", input.cell_type());

        // 设置 metadata
        ObjectNode metadata = MAPPER.createObjectNode();
        newCell.set("metadata", metadata);

        // 设置 source
        ArrayNode sourceArray = MAPPER.createArrayNode();
        String[] lines = input.new_source().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            sourceArray.add(i < lines.length - 1 ? lines[i] + "\n" : lines[i]);
        }
        newCell.set("source", sourceArray);

        // 设置 outputs（仅 code 类型需要）
        if ("code".equals(input.cell_type())) {
            newCell.putArray("outputs");
            newCell.putNull("execution_count");
        }

        // 确定插入位置
        int insertIndex;
        if (input.cell_id() != null && !input.cell_id().isBlank()) {
            int targetIndex = findCellIndex(cells, input.cell_id());
            if (targetIndex < 0) {
                return ToolOutput.fail("未找到参考单元格: cell_id=" + input.cell_id());
            }
            insertIndex = targetIndex + 1;
        } else {
            insertIndex = cells.size(); // 追加到末尾
        }

        // 执行插入（ArrayNode 没有直接 insert，需要重建）
        ArrayNode newCells = MAPPER.createArrayNode();
        for (int i = 0; i < cells.size(); i++) {
            newCells.add(cells.get(i));
            if (i + 1 == insertIndex) {
                newCells.add(newCell);
            }
        }
        // 如果插入位置在末尾
        if (insertIndex >= cells.size()) {
            newCells.add(newCell);
        }

        nbRoot.set("cells", newCells);

        return writeBack(vfs, input.notebook_path(), nbRoot,
                "已插入新单元格 [id=" + newCellId + "]，类型=" + input.cell_type()
                        + "，位置=" + insertIndex);
    }

    /**
     * 删除模式 — 删除指定单元格。
     */
    private ToolOutput handleDelete(ArrayNode cells, Input input, VfsManager vfs, ObjectNode nbRoot) {
        if (input.cell_id() == null || input.cell_id().isBlank()) {
            return ToolOutput.fail("delete 模式需要指定 cell_id");
        }

        int index = findCellIndex(cells, input.cell_id());
        if (index < 0) {
            return ToolOutput.fail("未找到单元格: cell_id=" + input.cell_id());
        }

        // 重建单元格数组（跳过被删除的单元格）
        ArrayNode newCells = MAPPER.createArrayNode();
        for (int i = 0; i < cells.size(); i++) {
            if (i != index) {
                newCells.add(cells.get(i));
            }
        }

        nbRoot.set("cells", newCells);

        return writeBack(vfs, input.notebook_path(), nbRoot,
                "已删除单元格 [" + input.cell_id() + "]");
    }

    /**
     * 根据 cell_id 查找单元格在数组中的索引。
     *
     * @param cells  单元格数组
     * @param cellId 目标单元格 ID
     * @return 索引位置，未找到返回 -1
     */
    private int findCellIndex(ArrayNode cells, String cellId) {
        for (int i = 0; i < cells.size(); i++) {
            JsonNode cell = cells.get(i);
            JsonNode idNode = cell.get("id");
            if (idNode != null && cellId.equals(idNode.asText())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 将修改后的 Notebook 写回 VFS。
     *
     * @param vfs          VFS 管理器
     * @param path         Notebook 路径
     * @param nbRoot       修改后的 Notebook JSON 根节点
     * @param successMsg   成功消息
     * @return 工具输出结果
     */
    private ToolOutput writeBack(VfsManager vfs, String path, ObjectNode nbRoot, String successMsg) {
        try {
            String newContent = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nbRoot);
            boolean success = vfs.writeText(path, newContent);
            if (!success) {
                return ToolOutput.fail("写入 Notebook 失败（权限不足或路径无效）: " + path);
            }
            return ToolOutput.ok(successMsg);
        } catch (Exception e) {
            return ToolOutput.fail("序列化 Notebook 失败: " + e.getMessage());
        }
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public String prompt() {
        return "使用 notebook_edit 编辑 Jupyter Notebook 单元格。"
                + "支持三种模式：replace（替换单元格内容/类型）、insert（插入新单元格）、delete（删除单元格）。"
                + "replace 和 delete 模式需要 cell_id 定位目标单元格。";
    }
}
