package com.huoling.wms.site.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 请求指纹：覆盖接口、路径参数与请求体，不含幂等键本身（契约「幂等约定」）。
 *
 * <p>算法与 Hub 侧一致（SHA-256 十六进制），两侧对同一请求得出同一指纹，
 * 便于联调时对照日志，也避免「同一键在不同端被当成不同体」。
 */
final class SiteFingerprints {

    private SiteFingerprints() {
    }

    static String of(String apiCode, String requestTarget, String requestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = apiCode + "\n" + (requestTarget == null ? "" : requestTarget)
                + "\n" + (requestBody == null ? "" : requestBody);
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
