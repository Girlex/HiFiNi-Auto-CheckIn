package cloud.ohiyou.utils;

import cloud.ohiyou.constant.HifiniConstants;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * OkHttp 客户端工具类
 * 提供全局唯一的 OkHttpClient 实例
 *
 * @author ohiyou
 */
public final class OkHttpUtils {

    private static final Logger logger = LoggerFactory.getLogger(OkHttpUtils.class);

    private static volatile OkHttpClient client;

    private static final Object LOCK = new Object();

    /**
     * 禁止实例化
     */
    private OkHttpUtils() {
    }

    /**
     * 获取 OkHttpClient 单例实例
     * 使用双重检查锁定确保线程安全
     *
     * @return OkHttpClient 实例
     */
    public static OkHttpClient getClient() {
        if (client == null) {
            synchronized (LOCK) {
                if (client == null) {
                    client = createClient();
                }
            }
        }
        return client;
    }

    /**
     * 创建 OkHttpClient 实例
     * 支持通过环境变量 HTTPS_PROXY / HTTP_PROXY 配置代理（用于部署环境 IP 被站点屏蔽的场景）
     */
    private static OkHttpClient createClient() {
        // 自定义守护线程池，解决 exec 命令中线程无法释放问题
        ExecutorService executor = new ThreadPoolExecutor(
                0,
                Integer.MAX_VALUE,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread thread = new Thread(r, "OkHttp-Daemon");
                    thread.setDaemon(true);
                    return thread;
                }
        );

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .dispatcher(new Dispatcher(executor))
                .connectTimeout(HifiniConstants.DEFAULT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(HifiniConstants.DEFAULT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(HifiniConstants.DEFAULT_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        java.net.Proxy proxy = resolveProxyFromEnv();
        if (proxy != null) {
            builder.proxy(proxy);
            logger.info("已启用代理: {}", proxy.address());
        }

        return builder.build();
    }

    /**
     * 从环境变量解析代理配置
     * 支持 HTTPS_PROXY / HTTP_PROXY，格式: http://host:port
     *
     * @return 代理对象；未配置返回 null
     */
    private static java.net.Proxy resolveProxyFromEnv() {
        String proxyUrl = System.getenv(HifiniConstants.ENV_HTTPS_PROXY);
        if (proxyUrl == null || proxyUrl.trim().isEmpty()) {
            proxyUrl = System.getenv(HifiniConstants.ENV_HTTP_PROXY);
        }
        if (proxyUrl == null || proxyUrl.trim().isEmpty()) {
            return null;
        }
        try {
            proxyUrl = proxyUrl.trim();
            // 兼容不带协议前缀的写法
            if (!proxyUrl.startsWith("http://") && !proxyUrl.startsWith("https://")) {
                proxyUrl = "http://" + proxyUrl;
            }
            java.net.URI uri = java.net.URI.create(proxyUrl);
            return new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(uri.getHost(), uri.getPort()));
        } catch (Exception e) {
            logger.warn("解析代理配置失败（{}={}），忽略代理: {}",
                    HifiniConstants.ENV_HTTPS_PROXY, proxyUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 关闭 OkHttpClient 资源
     * 应在应用退出时调用
     */
    public static void shutdown() {
        if (client != null) {
            try {
                client.dispatcher().executorService().shutdownNow();
                client.connectionPool().evictAll();
                if (client.cache() != null) {
                    client.cache().close();
                }
                logger.info("OkHttpClient 资源已释放");
            } catch (Exception e) {
                logger.error("关闭 OkHttpClient 时发生异常: {}", e.getMessage(), e);
            }
        }
    }
}
