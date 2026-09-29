package cloud.ohiyou.utils;

import cloud.ohiyou.constant.HifiniConstants;
import cloud.ohiyou.vo.SignResultVO;
import com.alibaba.fastjson2.JSON;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

/**
 * 签到 HTTP 执行器
 * <p>
 * 统一处理签到请求的公共逻辑：
 * 1. 补齐完整浏览器指纹头（Accept / Accept-Language / Sec-Fetch-*），降低被 WAF 识别的概率
 * 2. 网络类失败自动重试（指数退避 + 随机抖动，每次重试更换 UA）
 * 3. 业务响应（如"请登录后再签到"、"已签到"）不重试，直接返回
 * 4. 对失败原因做分类，方便排查是 Cookie 失效还是网络不通
 *
 * @author ohiyou
 */
public final class SignHttpHelper {

    private static final Logger logger = LoggerFactory.getLogger(SignHttpHelper.class);

    private static final OkHttpClient client = OkHttpUtils.getClient();
    private static final Random random = new Random();

    private static final MediaType FORM_URLENCODED =
            MediaType.get("application/x-www-form-urlencoded; charset=UTF-8");

    private SignHttpHelper() {
    }

    /**
     * 执行签到 POST 请求（带重试）
     *
     * @param signUrl   签到接口地址
     * @param cookie    用户 cookie
     * @param platformName 平台名称，用于日志（如 HiFiTi / HiFiHi）
     * @return 签到结果
     */
    public static SignResultVO postSign(String signUrl, String cookie, String platformName) {
        int maxRetries = HifiniConstants.SIGN_MAX_RETRIES;
        String lastError = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // 首次请求也加随机延迟，模拟人工点击
            sleepRandomDelay();
            try {
                SignResultVO result = doSignOnce(signUrl, cookie, platformName);
                if (result != null) {
                    return result;
                }
                // doSignOnce 返回 null 表示网络类失败，可重试
                lastError = "网络请求失败";
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                logger.warn("[{}] 第 {}/{} 次签到请求异常: {}", platformName, attempt + 1, maxRetries + 1, e.getMessage());
            }

            if (attempt < maxRetries) {
                long backoff = retryBackoffMs(attempt);
                logger.warn("[{}] 签到失败，{}ms 后重试（第 {} 次）", platformName, backoff, attempt + 1);
                sleep(backoff);
            }
        }

        return new SignResultVO(2, "签到失败（已重试 " + maxRetries + " 次）：" + lastError
                + "。若持续失败，可能是本机/服务器 IP 被站点屏蔽，可尝试配置代理或本地运行。");
    }

    /**
     * 执行一次签到请求
     *
     * @return 业务结果对象；网络类失败（HTTP 错误状态码）返回 null 表示可重试
     */
    private static SignResultVO doSignOnce(String signUrl, String cookie, String platformName) {
        String userAgent = randomUserAgent();

        RequestBody emptyBody = RequestBody.create("", FORM_URLENCODED);

        Request request = new Request.Builder()
                .url(signUrl)
                .post(emptyBody)
                .addHeader("Cookie", cookie)
                .addHeader("User-Agent", userAgent)
                .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Referer", signUrl)
                .addHeader("Sec-Fetch-Dest", "empty")
                .addHeader("Sec-Fetch-Mode", "cors")
                .addHeader("Sec-Fetch-Site", "same-origin")
                .build();

        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            if (!response.isSuccessful()) {
                logger.warn("[{}] HTTP 状态码 {}（UA: {}）", platformName, code, userAgent);
                // 5xx / 403 / 429 / 404 属于可重试的服务端或风控问题
                if (code >= 500 || code == 403 || code == 429 || code == 404) {
                    return null;
                }
                return new SignResultVO(2, "请求失败，HTTP状态码：" + code);
            }

            String responseBody = ResponseUtils.readResponse(response);
            logger.info("[{}] 签到响应内容: {}", platformName, responseBody);
            SignResultVO result = JSON.parseObject(responseBody, SignResultVO.class);
            if (result == null) {
                // 响应不是预期 JSON（可能被 WAF 拦截页劫持），按可重试处理
                logger.warn("[{}] 响应非预期 JSON，可能被 WAF 拦截", platformName);
                return null;
            }
            // 站点对未登录场景也返回 code=0，需按 message 识别为 Cookie 失效，避免误报"签到成功"
            if (result.getMessage() != null && result.getMessage().contains("请登录")) {
                logger.warn("[{}] Cookie 已失效（站点返回: {}）", platformName, result.getMessage());
                return new SignResultVO(2, "Cookie已失效或无效，请重新获取Cookie");
            }
            return result;
        } catch (java.io.IOException e) {
            logger.warn("[{}] 网络异常: {}", platformName, e.getMessage());
            return null;
        }
    }

    /**
     * 计算重试退避时间：BASE * 2^attempt + 随机抖动(0~BASE/2)
     */
    private static long retryBackoffMs(int attempt) {
        long backoff = HifiniConstants.SIGN_RETRY_BACKOFF_BASE_MS * (1L << attempt);
        return backoff + random.nextInt((int) (HifiniConstants.SIGN_RETRY_BACKOFF_BASE_MS / 2));
    }

    /**
     * 请求前随机延迟，避免固定节奏请求
     */
    private static void sleepRandomDelay() {
        sleep((long) random.nextInt((int) HifiniConstants.SIGN_RANDOM_DELAY_MAX_MS));
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String randomUserAgent() {
        return HifiniConstants.USER_AGENTS.get(random.nextInt(HifiniConstants.USER_AGENTS.size()));
    }
}
