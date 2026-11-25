package com.huoling.wms.masterdata.identity.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import java.io.IOException;

/**
 * 标识字段的反序列化器：只接受 JSON 字符串，数字与布尔一律按 {@code COMMON_9001} 拒绝
 * （契约「标识、类型与枚举约定」）。
 *
 * <p>为什么不用全局的 {@code spring.jackson.mapper.allow-coercion-of-scalars=false}：
 * 那个开关只拦「字符串 → 数字」方向，Jackson 仍会把 {@code 1001} 隐式转成 {@code "1001"}，
 * 前端一旦漏改就静默通过、精度缺陷重新出现。这里显式拒绝，两端都不做隐式转换。
 * {@code null} 原样放行，可空字段（如 API-007 的 {@code employeeId}）语义不变。
 */
public class IdentifierDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.hasToken(JsonToken.VALUE_STRING)) {
            return parser.getText();
        }
        if (parser.hasToken(JsonToken.VALUE_NULL)) {
            return null;
        }
        throw MismatchedInputException.from(parser, String.class,
            "标识必须为字符串形态（" + Identifiers.PATTERN + "），不要把标识写成 JSON 数字");
    }
}
