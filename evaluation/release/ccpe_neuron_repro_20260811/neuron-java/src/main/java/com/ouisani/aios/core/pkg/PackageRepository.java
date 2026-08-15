package com.ouisani.aios.core.pkg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 包仓库 — AIOS 的软件源（Package Repository）。
 * <p>
 * 类比 Debian 的 sources.list 或 Docker Hub 的 Registry，
 * PackageRepository 维护了一个可搜索的软件包索引。
 * <p>
 * 当前实现使用内存存储 + 内置包列表，模拟远程软件源。
 * 未来可扩展为从远程 JSON API 或 Git 仓库拉取包索引。
 *
 * <h3>OS 类比</h3>
 * <table>
 *   <tr><th>Debian</th><th>AIOS</th><th>说明</th></tr>
 *   <tr><td>sources.list</td><td>PackageRepository</td><td>软件源配置</td></tr>
 *   <tr><td>apt-cache</td><td>search()</td><td>包搜索缓存</td></tr>
 *   <tr><td>Packages.gz</td><td>packageIndex</td><td>包索引文件</td></tr>
 * </table>
 *
 * @see AiosApt
 * @see PackageManifest
 */
public class PackageRepository {

    private static final Logger log = LoggerFactory.getLogger(PackageRepository.class);

    /** 包索引：packageName → PackageManifest */
    private final ConcurrentHashMap<String, PackageManifest> packageIndex = new ConcurrentHashMap<>();

    /** 关键词倒排索引：keyword → Set<packageName> */
    private final ConcurrentHashMap<String, Set<String>> invertedIndex = new ConcurrentHashMap<>();

    public PackageRepository() {
    }

    /**
     * 添加包到仓库索引。
     */
    public void addPackage(PackageManifest manifest) {
        packageIndex.put(manifest.name(), manifest);

        // 构建倒排索引 — 将包名、描述、标签拆分为关键词
        indexKeywords(manifest.name(), manifest);
        if (manifest.description() != null) {
            for (String token : tokenize(manifest.description())) {
                indexKeywords(token, manifest);
            }
        }
        if (manifest.metadata() != null) {
            String tags = manifest.metadata().get("tags");
            if (tags != null) {
                for (String tag : tags.split(",")) {
                    indexKeywords(tag.strip(), manifest);
                }
            }
        }
    }

    /**
     * 从仓库获取包清单。
     *
     * @param packageName 包名
     * @return PackageManifest，如果不存在返回 null
     */
    public PackageManifest fetch(String packageName) {
        return packageIndex.get(packageName);
    }

    /**
     * 搜索软件包 — 支持关键词匹配和语义搜索。
     * <p>
     * 搜索策略：
     * <ol>
     *   <li>精确匹配包名</li>
     *   <li>包名前缀匹配</li>
     *   <li>描述关键词匹配（倒排索引）</li>
     *   <li>标签匹配</li>
     * </ol>
     *
     * @param query 搜索查询
     * @return 匹配的包列表，按相关度排序
     */
    public List<PackageManifest> search(String query) {
        if (query == null || query.isBlank()) {
            return List.copyOf(packageIndex.values());
        }

        String normalizedQuery = query.toLowerCase().strip();
        Map<PackageManifest, Integer> scoredResults = new LinkedHashMap<>();

        for (PackageManifest manifest : packageIndex.values()) {
            int score = 0;

            // 精确匹配包名 — 最高分
            if (manifest.name().equalsIgnoreCase(normalizedQuery)) {
                score += 100;
            }

            // 包名包含查询
            if (manifest.name().toLowerCase().contains(normalizedQuery)) {
                score += 50;
            }

            // 描述包含查询
            if (manifest.description() != null
                    && manifest.description().toLowerCase().contains(normalizedQuery)) {
                score += 30;
            }

            // 倒排索引匹配
            for (String token : tokenize(query)) {
                Set<String> matches = invertedIndex.get(token.toLowerCase());
                if (matches != null && matches.contains(manifest.name())) {
                    score += 20;
                }
            }

            // 标签匹配
            if (manifest.metadata() != null) {
                String tags = manifest.metadata().get("tags");
                if (tags != null && tags.toLowerCase().contains(normalizedQuery)) {
                    score += 15;
                }
            }

            // 分类匹配
            if (manifest.metadata() != null) {
                String category = manifest.metadata().get("category");
                if (category != null && category.equalsIgnoreCase(normalizedQuery)) {
                    score += 10;
                }
            }

            if (score > 0) {
                scoredResults.put(manifest, score);
            }
        }

        // 按分数降序排序
        return scoredResults.entrySet().stream()
                .sorted(Map.Entry.<PackageManifest, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    /**
     * 刷新软件源索引 — 模拟从远程拉取。
     */
    public void refresh() {
        log.info("[PackageRepository] Refreshing package index (current: {} packages)", packageIndex.size());
        // 未来：从远程 JSON API 或 Git 仓库拉取最新包索引
    }

    /**
     * 仓库中可用的包数量。
     */
    public int packageCount() {
        return packageIndex.size();
    }

    /**
     * 列出所有可用包。
     */
    public Collection<PackageManifest> allPackages() {
        return Collections.unmodifiableCollection(packageIndex.values());
    }

    // ── 内部辅助 ──

    private void indexKeywords(String keyword, PackageManifest manifest) {
        if (keyword == null || keyword.isBlank()) return;
        invertedIndex.computeIfAbsent(keyword.toLowerCase(), k -> ConcurrentHashMap.newKeySet())
                .add(manifest.name());
    }

    private String[] tokenize(String text) {
        if (text == null) return new String[0];
        return text.toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff]", " ")
                .split("\\s+");
    }
}
