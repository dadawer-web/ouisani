package com.ouisani.aios.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 插件自动发现工具 — 扫描指定目录下的 JAR 文件，自动发现指定接口/类的实现。
 * <p>
 * 参考 LMCache 的 {@code discover_subclasses}，适配 Java 的 ServiceLoader + 反射机制。
 * <p>
 * 支持两种发现模式：
 * <ol>
 *   <li><b>目录扫描</b>：扫描指定目录下的 {@code .jar} 文件，用 {@link URLClassLoader}
 *       加载后反射查找实现类</li>
 *   <li><b>ServiceLoader</b>：标准的 Java SPI 机制，通过
 *       {@code META-INF/services/} 配置文件发现实现</li>
 * </ol>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 目录扫描
 * List<Class<? extends MyInterface>> classes =
 *     PluginDiscovery.instance().discoverFromDirectory(
 *         "/opt/aios/plugins", MyInterface.class, null);
 *
 * // ServiceLoader 发现
 * List<MyInterface> instances =
 *     PluginDiscovery.instance().discoverViaServiceLoader(MyInterface.class);
 * }</pre>
 */
public final class PluginDiscovery {

    private static final Logger log = LoggerFactory.getLogger(PluginDiscovery.class);

    private static final PluginDiscovery INSTANCE = new PluginDiscovery();

    private PluginDiscovery() {
    }

    /**
     * 获取插件发现工具单例。
     *
     * @return 单例实例
     */
    public static PluginDiscovery instance() {
        return INSTANCE;
    }

    /**
     * 扫描指定目录下的 JAR 文件，发现指定接口/类的实现。
     * <p>
     * 执行流程：
     * <ol>
     *   <li>遍历目录下所有 {@code .jar} 文件</li>
     *   <li>用 {@link URLClassLoader} 加载每个 JAR</li>
     *   <li>读取 JAR 中的 {@code .class} 文件条目</li>
     *   <li>用 {@link Class#forName} 加载每个类</li>
     *   <li>检查是否是 {@code baseInterface} 的子类</li>
     *   <li>返回匹配的类列表</li>
     * </ol>
     * <p>
     * 单个类加载失败不会中断整个扫描，错误会被记录到日志。
     *
     * @param dirPath         要扫描的目录路径
     * @param baseInterface   目标接口/类的 Class 对象
     * @param classNameFilter 类名过滤器（可为 null 表示接受所有类）
     * @param <T>             目标类型
     * @return 匹配的类列表，如果目录不存在或无匹配则返回空列表
     * @throws IllegalArgumentException 如果 dirPath 或 baseInterface 为 null
     */
    public <T> List<Class<? extends T>> discoverFromDirectory(
            String dirPath, Class<T> baseInterface, Predicate<String> classNameFilter) {
        if (dirPath == null || dirPath.isBlank()) {
            throw new IllegalArgumentException("Directory path cannot be null or blank");
        }
        if (baseInterface == null) {
            throw new IllegalArgumentException("Base interface cannot be null");
        }

        List<Class<? extends T>> result = new ArrayList<>();
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            log.warn("[PluginDiscovery] 目录不存在或不是目录: {}", dirPath);
            return result;
        }

        File[] jarFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jarFiles == null || jarFiles.length == 0) {
            log.debug("[PluginDiscovery] 目录中未找到 JAR 文件: {}", dirPath);
            return result;
        }

        log.info("[PluginDiscovery] 开始扫描目录: {} ({} 个 JAR)", dirPath, jarFiles.length);

        for (File jarFile : jarFiles) {
            result.addAll(scanJar(jarFile, baseInterface, classNameFilter));
        }

        log.info("[PluginDiscovery] 扫描完成: 从 {} 发现 {} 个 {} 的实现",
                dirPath, result.size(), baseInterface.getSimpleName());
        return result;
    }

    /**
     * 使用 Java SPI (ServiceLoader) 机制发现服务实现。
     * <p>
     * 通过 {@code META-INF/services/} 配置文件加载实现类并实例化。
     *
     * @param serviceInterface 服务接口的 Class 对象
     * @param <T>              服务类型
     * @return 服务实现实例列表
     * @throws IllegalArgumentException 如果 serviceInterface 为 null
     */
    public <T> List<T> discoverViaServiceLoader(Class<T> serviceInterface) {
        if (serviceInterface == null) {
            throw new IllegalArgumentException("Service interface cannot be null");
        }

        List<T> instances = new ArrayList<>();
        ServiceLoader<T> loader = ServiceLoader.load(serviceInterface);

        for (T instance : loader) {
            try {
                instances.add(instance);
                log.debug("[PluginDiscovery] ServiceLoader 发现: {}", instance.getClass().getName());
            } catch (ServiceConfigurationError e) {
                log.error("[PluginDiscovery] ServiceLoader 加载失败: {}", e.getMessage(), e);
            }
        }

        log.info("[PluginDiscovery] ServiceLoader 发现 {} 个 {} 的实现",
                instances.size(), serviceInterface.getSimpleName());
        return instances;
    }

    /**
     * 扫描指定包名下的类（基于类路径），发现指定接口/类的实现。
     * <p>
     * 将包名转换为路径，通过类加载器查找资源，扫描目录和 JAR 两种来源。
     *
     * @param packageName    要扫描的包名（如 {@code "com.ouisani.aios.core.plugin"}）
     * @param baseInterface   目标接口/类的 Class 对象
     * @param <T>             目标类型
     * @return 匹配的类列表
     * @throws IllegalArgumentException 如果 packageName 或 baseInterface 为 null
     */
    public <T> List<Class<? extends T>> discoverInPackage(
            String packageName, Class<T> baseInterface) {
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("Package name cannot be null or blank");
        }
        if (baseInterface == null) {
            throw new IllegalArgumentException("Base interface cannot be null");
        }

        List<Class<? extends T>> result = new ArrayList<>();
        String packagePath = packageName.replace('.', '/');
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = PluginDiscovery.class.getClassLoader();
        }

        try {
            Enumeration<URL> resources = classLoader.getResources(packagePath);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String protocol = resource.getProtocol();

                if ("file".equals(protocol)) {
                    File packageDir = new File(resource.toURI());
                    scanDirectory(packageDir, packageName, baseInterface, result);
                } else if ("jar".equals(protocol)) {
                    scanJarResource(resource, packagePath, packageName, baseInterface, result);
                }
            }
        } catch (Exception e) {
            log.error("[PluginDiscovery] 扫描包 {} 失败: {}", packageName, e.getMessage(), e);
        }

        log.info("[PluginDiscovery] 包扫描完成: 从 {} 发现 {} 个 {} 的实现",
                packageName, result.size(), baseInterface.getSimpleName());
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  Internal helpers
    // ════════════════════════════════════════════════════════════════

    /**
     * 扫描单个 JAR 文件，查找 baseInterface 的实现类。
     */
    @SuppressWarnings("unchecked")
    private <T> List<Class<? extends T>> scanJar(
            File jarFile, Class<T> baseInterface, Predicate<String> classNameFilter) {
        List<Class<? extends T>> matches = new ArrayList<>();

        try (JarFile jar = new JarFile(jarFile);
             URLClassLoader loader = new URLClassLoader(
                     new URL[]{jarFile.toURI().toURL()},
                     baseInterface.getClassLoader())) {

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (!entryName.endsWith(".class")) {
                    continue;
                }

                String className = entryName
                        .replace('/', '.')
                        .replace(".class", "");

                if (classNameFilter != null && !classNameFilter.test(className)) {
                    continue;
                }

                try {
                    Class<?> clazz = Class.forName(className, false, loader);
                    if (baseInterface.isAssignableFrom(clazz) && clazz != baseInterface) {
                        matches.add((Class<? extends T>) clazz);
                        log.debug("[PluginDiscovery] 发现实现: {} (in {})", className, jarFile.getName());
                    }
                } catch (Throwable e) {
                    log.debug("[PluginDiscovery] 加载类失败: {} (in {}) — {}",
                            className, jarFile.getName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[PluginDiscovery] 读取 JAR 失败: {} — {}", jarFile, e.getMessage());
        }

        return matches;
    }

    /**
     * 扫描文件系统目录中的 .class 文件。
     */
    @SuppressWarnings("unchecked")
    private <T> void scanDirectory(
            File packageDir, String packageName, Class<T> baseInterface,
            List<Class<? extends T>> result) {

        if (!packageDir.isDirectory()) {
            return;
        }

        File[] files = packageDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + file.getName(),
                        baseInterface, result);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "."
                        + file.getName().replace(".class", "");
                try {
                    Class<?> clazz = Class.forName(className);
                    if (baseInterface.isAssignableFrom(clazz) && clazz != baseInterface) {
                        result.add((Class<? extends T>) clazz);
                        log.debug("[PluginDiscovery] 发现实现: {}", className);
                    }
                } catch (Throwable e) {
                    log.debug("[PluginDiscovery] 加载类失败: {} — {}", className, e.getMessage());
                }
            }
        }
    }

    /**
     * 扫描 JAR 中的包资源。
     */
    @SuppressWarnings("unchecked")
    private <T> void scanJarResource(
            URL resource, String packagePath, String packageName,
            Class<T> baseInterface, List<Class<? extends T>> result) {

        String jarPath = resource.getPath();
        int bangIndex = jarPath.indexOf('!');
        if (bangIndex > 0) {
            jarPath = jarPath.substring(0, bangIndex);
        }
        if (jarPath.startsWith("file:")) {
            jarPath = jarPath.substring("file:".length());
        }

        File jarFile = new File(jarPath);
        if (!jarFile.exists()) {
            return;
        }

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                if (!entryName.startsWith(packagePath) || !entryName.endsWith(".class")) {
                    continue;
                }

                String className = entryName
                        .replace('/', '.')
                        .replace(".class", "");

                try {
                    Class<?> clazz = Class.forName(className);
                    if (baseInterface.isAssignableFrom(clazz) && clazz != baseInterface) {
                        result.add((Class<? extends T>) clazz);
                        log.debug("[PluginDiscovery] 发现实现: {}", className);
                    }
                } catch (Throwable e) {
                    log.debug("[PluginDiscovery] 加载类失败: {} — {}", className, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[PluginDiscovery] 读取 JAR 失败: {} — {}", jarFile, e.getMessage());
        }
    }
}
