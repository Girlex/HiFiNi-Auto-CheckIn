package cloud.ohiyou.service.impl;

import cloud.ohiyou.constant.HifiniConstants;
import cloud.ohiyou.service.ISignService;
import cloud.ohiyou.utils.OkHttpUtils;
import cloud.ohiyou.utils.ResponseUtils;
import cloud.ohiyou.utils.SignHttpHelper;
import cloud.ohiyou.vo.SignResultVO;
import cloud.ohiyou.vo.UserInfoVO;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HiFiHi 签到服务实现
 *
 * @author ohiyou
 */
public class HifihiSignService implements ISignService {

    private static final Logger logger = LoggerFactory.getLogger(HifihiSignService.class);

    private static final OkHttpClient client = OkHttpUtils.getClient();
    private final Random random = new Random();

    /**
     * 签到
     *
     * @param cookie cookie
     * @return 签到结果
     */
    @Override
    public SignResultVO signIn(String cookie) {
        return SignHttpHelper.postSign(HifiniConstants.HIFIHI_SIGN_URL, cookie, "HiFiHi");
    }

    /**
     * 获取随机的 User-Agent
     */
    private String getRandomUserAgent() {
        return HifiniConstants.USER_AGENTS.get(random.nextInt(HifiniConstants.USER_AGENTS.size()));
    }

    /**
     * 获取用户信息
     *
     * @param cookie cookie
     * @return 用户信息
     */
    @Override
    public UserInfoVO getUserInfo(String cookie) {
        try {
            String userAgent = getRandomUserAgent();

            Request request = new Request.Builder()
                    .url(HifiniConstants.HIFIHI_USER_INFO_URL)
                    .get()
                    .addHeader("Cookie", cookie)
                    .addHeader("User-Agent", userAgent)
                    .addHeader("Referer", HifiniConstants.HIFIHI_BASE_URL + "/")
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("获取用户信息失败，HTTP状态码: " + response.code());
                }

                String pageContent = ResponseUtils.readResponse(response);
                return parseUserInfo(pageContent);
            }

        } catch (Exception e) {
            logger.error("HiFiHi 获取用户信息异常: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户信息失败: " + e.getMessage());
        }
    }

    /**
     * 获取连续签到天数
     *
     * @param cookie 用户cookie
     * @return 连续签到天数
     */
    @Override
    public Integer getSignStreak(String cookie) {
        try {
            String userAgent = getRandomUserAgent();

            Request request = new Request.Builder()
                    .url(HifiniConstants.HIFIHI_SIGN_URL)
                    .get()
                    .addHeader("Cookie", cookie)
                    .addHeader("User-Agent", userAgent)
                    .addHeader("Referer", HifiniConstants.HIFIHI_BASE_URL + "/")
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }

                String pageContent = ResponseUtils.readResponse(response);
                return parseSignStreak(pageContent);
            }

        } catch (Exception e) {
            logger.error("HiFiHi 获取连续签到天数异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从my.htm页面内容解析用户信息
     */
    private UserInfoVO parseUserInfo(String pageContent) {
        UserInfoVO userInfo = new UserInfoVO();

        try {
            // 解析用户名：先精确匹配，失败则用宽松备选
            String userName = matchFirst(pageContent, HifiniConstants.REGEX_USERNAME);
            if (userName == null) {
                userName = matchFirst(pageContent, HifiniConstants.REGEX_USERNAME_FALLBACK);
                if (userName != null) {
                    logger.debug("HiFiHi 用户名通过备用正则解析成功");
                }
            }
            if (userName != null) {
                userInfo.setUserName(userName.trim());
            }

            // 解析金币数量：先精确匹配，失败则用宽松备选
            String coins = matchFirst(pageContent, HifiniConstants.REGEX_COINS);
            if (coins == null) {
                coins = matchFirst(pageContent, HifiniConstants.REGEX_COINS_FALLBACK);
            }
            if (coins != null) {
                userInfo.setCoins(Integer.parseInt(coins));
            }

        } catch (Exception e) {
            logger.error("HiFiHi 解析用户信息时出错: {}", e.getMessage(), e);
        }

        return userInfo;
    }

    /**
     * 在页面内容中匹配正则的第一个捕获组
     *
     * @return 匹配内容；未命中返回 null
     */
    private String matchFirst(String content, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 从sg_sign.htm页面解析连续签到天数
     */
    private Integer parseSignStreak(String signPageContent) {
        try {
            // 匹配 JavaScript 变量中的连续签到天数
            Pattern streakPattern = Pattern.compile(HifiniConstants.REGEX_SIGN_STREAK);
            Matcher streakMatcher = streakPattern.matcher(signPageContent);
            if (streakMatcher.find()) {
                int streak = Integer.parseInt(streakMatcher.group(1));
                logger.debug("HiFiHi 解析连续签到天数成功: {} 天", streak);
                return streak;
            } else {
                logger.debug("HiFiHi 页面中未找到连续签到天数信息");
                // 尝试更宽松的匹配
                Pattern fallbackPattern = Pattern.compile(HifiniConstants.REGEX_SIGN_STREAK_FALLBACK);
                Matcher fallbackMatcher = fallbackPattern.matcher(signPageContent);
                if (fallbackMatcher.find()) {
                    int streak = Integer.parseInt(fallbackMatcher.group(1));
                    logger.debug("HiFiHi 通过备用方式解析连续签到天数成功: {} 天", streak);
                    return streak;
                }
            }
        } catch (Exception e) {
            logger.error("HiFiHi 解析连续签到天数时出错: {}", e.getMessage(), e);
        }
        return null;
    }
}
