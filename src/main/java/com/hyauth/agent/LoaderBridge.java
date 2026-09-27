package com.hyauth.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 服务端类加载器桥接。
 *
 * <p>Minecraft 1.18+ 的 {@code server.jar} 其实是 Mojang Bundler：它把服务端依赖
 * （authlib、gson、guava 等）解包后，用一个 <b>父加载器为 platform 的 URLClassLoader</b> 加载服务端
 * （26.3 的 Bundler 甚至不再 fork 子进程，直接在本 JVM 里跑服务端主类）。
 *
 * <p>这带来两个后果：
 * <ol>
 *     <li>{@code -javaagent} 的类位于 system 加载器，<b>服务端类加载器看不到它</b>，
 *         切面内联进去的 {@code com.hyauth.agent.*} 调用会在玩家登录时报 NoClassDefFoundError；</li>
 *     <li>反向地，Agent 类也看不到服务端的 gson / authlib。</li>
 * </ol>
 *
 * <p>解决方式：在转换目标类之前，把 Agent 自己的辅助类<b>注入到目标类所在的加载器</b>里。
 * 这样切面（内联在 authlib 类中，由该加载器解析）与本类辅助类处于同一加载器，
 * 既能互相调用，也都能看到服务端自带的 gson / authlib。
 *
 * <p>注入使用反射调用 {@code ClassLoader#defineClass}，因此启动脚本必须带有
 * {@code --add-opens java.base/java.lang=ALL-UNNAMED}。
 */
public final class LoaderBridge {

    /** 注入范围：com.hyauth.agent 下除入口/桥接类以外的全部类（含内部类）。 */
    private static final String AGENT_PACKAGE_PREFIX = "com/hyauth/agent/";

    /** 不注入的类前缀：它们引用 Byte Buddy，只在 Agent 自身的加载器里使用。 */
    private static final String[] EXCLUDED_PREFIXES = {
            "com/hyauth/agent/AgentMain",
            "com/hyauth/agent/LoaderBridge"
    };

    private static final String CONFIG_CLASS = "com.hyauth.agent.config.ListManager";

    private static final Map<ClassLoader, Boolean> HANDLED =
            Collections.synchronizedMap(new WeakHashMap<ClassLoader, Boolean>());

    private LoaderBridge() {
    }

    /**
     * 确保目标类加载器可以使用 Agent 的辅助类：若该加载器已能解析（父加载器可见）则什么都不做，
     * 否则把辅助类注入进去，并在注入副本上初始化配置。
     */
    public static void ensureInjected(ClassLoader target) {
        if (target == null) {
            return;
        }
        synchronized (HANDLED) {
            if (HANDLED.containsKey(target)) {
                return;
            }
            HANDLED.put(target, Boolean.TRUE);
        }

        // 已经能解析（例如目标类就在 system 加载器里）→ 不需要注入
        try {
            target.loadClass("com.hyauth.agent.util.VerifiedProperty");
            return;
        } catch (ClassNotFoundException ignored) {
            // 继续注入
        }

        try {
            Method defineClass = ClassLoader.class.getDeclaredMethod(
                    "defineClass", String.class, byte[].class, int.class, int.class);
            defineClass.setAccessible(true);

            List<String> entries = collectHelperEntries();
            if (entries.isEmpty()) {
                System.err.println("[HyAuth] 未在 Agent 包中枚举到可注入的类，跳过注入。");
                return;
            }
            for (String entry : entries) {
                byte[] bytes = readClassBytes("/" + entry);
                if (bytes == null) {
                    continue;
                }
                String className = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
                defineClass.invoke(target, className, bytes, 0, bytes.length);
            }
            System.out.println("[HyAuth] 已把 " + entries.size() + " 个 Agent 辅助类注入服务端类加载器: " + target);

            // 用注入后的副本初始化配置（注入副本才是切面实际使用的那一份）
            Class<?> listManager = target.loadClass(CONFIG_CLASS);
            listManager.getMethod("init").invoke(null);
        } catch (Throwable t) {
            System.err.println("[HyAuth] Agent 类注入失败，切面调用将在运行期报错: " + t);
            System.err.println("[HyAuth] 原版 Bundler 启动时请确认已加入 --add-opens java.base/java.lang=ALL-UNNAMED");
        }
    }

    /** 从 Agent jar 枚举需要注入的 class 条目（含内部类）。 */
    private static List<String> collectHelperEntries() {
        List<String> entries = new ArrayList<String>();
        try {
            URL location = LoaderBridge.class.getProtectionDomain().getCodeSource().getLocation();
            File jar = new File(location.toURI());
            if (!jar.isFile()) {
                return entries;
            }
            try (JarFile jarFile = new JarFile(jar)) {
                Enumeration<JarEntry> enumeration = jarFile.entries();
                while (enumeration.hasMoreElements()) {
                    String name = enumeration.nextElement().getName();
                    if (!name.endsWith(".class") || !name.startsWith(AGENT_PACKAGE_PREFIX)) {
                        continue;
                    }
                    boolean excluded = false;
                    for (String prefix : EXCLUDED_PREFIXES) {
                        if (name.startsWith(prefix)) {
                            excluded = true;
                            break;
                        }
                    }
                    if (!excluded) {
                        entries.add(name);
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[HyAuth] 枚举 Agent 类失败: " + t);
        }
        return entries;
    }

    private static byte[] readClassBytes(String resource) {
        try (InputStream in = LoaderBridge.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (Exception e) {
            System.err.println("[HyAuth] 读取 " + resource + " 失败: " + e);
            return null;
        }
    }

    /** 便于诊断：当前已处理过的加载器数量。 */
    public static int handledLoaderCount() {
        return HANDLED.size();
    }

    /** 仅用于自检脚本的可见性检查。 */
    public static Set<ClassLoader> handledLoaders() {
        return HANDLED.keySet();
    }
}
