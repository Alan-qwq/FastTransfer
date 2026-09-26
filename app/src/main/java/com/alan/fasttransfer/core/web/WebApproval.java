package com.alan.fasttransfer.core.web;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网页上传的一次性凭证。
 *
 * <p>电脑传文件到手机必须<b>先经过手机用户点「接收」</b>。流程是：</p>
 * <ol>
 *   <li>浏览器把文件清单 POST 到 {@code /web/prepare}；</li>
 *   <li>手机弹窗询问用户；用户同意后 {@link #grant(int)} 按文件数发一张凭证；</li>
 *   <li>浏览器带着凭证逐个 POST {@code /web/upload}，{@link #consume} 每收一个减一次配额。</li>
 * </ol>
 *
 * <p>两条性质很重要，所以单独拎出来做可测的纯逻辑：</p>
 * <ul>
 *   <li><b>有配额</b>：用户批准了几个文件就只放行几次。配额用完凭证作废，
 *       别人捡到旧凭证也传不了；反过来也不能只给一次 —— 一个批次里每个文件
 *       都是一个独立的 multipart 请求，只给一次的话第二个文件就会被拒；</li>
 *   <li><b>会过期</b>：用户点了接收却一直不传，凭证不该无限期有效。</li>
 * </ul>
 */
public final class WebApproval {

    /** 凭证默认有效期：3 分钟足够开始传输，又不至于长期敞着口子。 */
    public static final long DEFAULT_TTL_MS = 3 * 60 * 1000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 一张凭证：签发时间 + 还能用几次。 */
    private static final class Grant {
        final long issuedAt;
        final AtomicInteger remaining;

        Grant(long issuedAt, int quota) {
            this.issuedAt = issuedAt;
            this.remaining = new AtomicInteger(Math.max(1, quota));
        }
    }

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final long ttlMs;

    public WebApproval() {
        this(DEFAULT_TTL_MS);
    }

    public WebApproval(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /** 用户点了「接收」，只批一个文件。 */
    public String grant() {
        return grant(1, System.currentTimeMillis());
    }

    /** 用户点了「接收」，批了 {@code fileCount} 个文件。 */
    public String grant(int fileCount) {
        return grant(fileCount, System.currentTimeMillis());
    }

    /** @see #grant(int) */
    public String grant(int fileCount, long now) {
        String token = newToken();
        grants.put(token, new Grant(now, fileCount));
        return token;
    }

    /**
     * 生成一个不可猜的随机令牌（十六进制）。
     *
     * <p>放在这里而不是放 Android 侧，是为了让凭证相关的逻辑整体保持纯 Java、
     * 可以离线测试。{@code WebOutbox} 也用这一个实现，避免两份随机数代码。</p>
     */
    public static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 校验并消耗一次配额。
     *
     * <p>这是唯一放行的入口：任何为 null、为空、没发过、配额用完、或已过期的凭证
     * 都必须返回 false。上传接口靠它把住「没在手机上确认过就一个字节都别想写」。</p>
     */
    public boolean consume(String token) {
        return consume(token, System.currentTimeMillis());
    }

    /** @see #consume(String) */
    public boolean consume(String token, long now) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        Grant grant = grants.get(token);
        if (grant == null) {
            return false;
        }
        if (now - grant.issuedAt > ttlMs) {
            grants.remove(token);
            return false;
        }
        int left = grant.remaining.decrementAndGet();
        if (left <= 0) {
            grants.remove(token);
        }
        return true;
    }

    /** 清掉过期凭证，避免长期积累。 */
    public void purgeExpired() {
        purgeExpired(System.currentTimeMillis());
    }

    /** @see #purgeExpired() */
    public void purgeExpired(long now) {
        for (Map.Entry<String, Grant> entry : grants.entrySet()) {
            Grant grant = entry.getValue();
            if (grant == null || now - grant.issuedAt > ttlMs) {
                grants.remove(entry.getKey());
            }
        }
    }

    /** 当前待使用的凭证数（测试用）。 */
    public int pendingCount() {
        return grants.size();
    }
}
