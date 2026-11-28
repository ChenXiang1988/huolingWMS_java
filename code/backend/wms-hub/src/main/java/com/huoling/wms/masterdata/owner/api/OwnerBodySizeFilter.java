package com.huoling.wms.masterdata.owner.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.masterdata.domain.OwnerFields;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 请求体上限的入口拦截（契约 1.1「请求边界」：单请求体 16 KB）。
 *
 * <p>为什么必须在过滤器里判、不能等控制器里判：控制器拿到 {@code @RequestBody} 时，
 * 请求体<b>已经被完整反序列化</b>——一个 10 MB 的载荷会先占满内存与 GC，再被告知「超限了」。
 * 这里只读 {@code Content-Length}，不读流、不解析，超限的直接回 400 并断开链路。
 *
 * <p>错误码与 {@code details} 结构不变：仍是 {@code PARAM_INVALID} + {@code details[].field=body}，
 * 与原控制器内校验逐字一致——调用方按码与字段名分派的逻辑不需要改。
 *
 * <p>顺序排在端白名单之后（{@code OwnerWebConfig}）：先判令牌与端，再判体积。
 * 反过来的话，一个未授权的大载荷会先拿到 400，让「你有没有权限」这件事被参数错误掩盖。
 *
 * <p>{@code Content-Length} 未知（分块传输）时不判定，与原口径一致：
 * 容器在没有长度时也会按配置的最大请求体自行拒绝，这里不额外造一个「分块必拒」的规则。
 */
public class OwnerBodySizeFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public OwnerBodySizeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > OwnerEntryChecks.BODY_MAX_BYTES) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        String reason = "请求体上限 " + OwnerEntryChecks.BODY_MAX_BYTES + " 字节";
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), OwnerResponse.invalid(reason,
            List.of(new OwnerFields.Issue("body", reason))));
    }
}
