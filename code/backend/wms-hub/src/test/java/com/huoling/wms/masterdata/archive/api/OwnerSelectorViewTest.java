package com.huoling.wms.masterdata.archive.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-020 响应字段逐字对齐契约：条目是 {@code ownerId} 与 {@code ownerName} 两项，
 * {@code dataTimestamp} 只在降级时出现。
 */
class OwnerSelectorViewTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("正常查询：条目只有 ownerId 与 ownerName，响应不带 dataTimestamp")
    void 正常查询不带数据时间戳() throws Exception {
        OwnerSelectorView.OwnerSelectorPage page = new OwnerSelectorView.OwnerSelectorPage(
            List.of(new OwnerSelectorView("9", "华东货主")), null, false, null);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(page));

        Set<String> itemFields = new HashSet<>();
        json.get("items").get(0).fieldNames().forEachRemaining(itemFields::add);
        assertThat(itemFields).containsExactlyInAnyOrder("ownerId", "ownerName");
        assertThat(json.get("items").get(0).get("ownerName").asText()).isEqualTo("华东货主");
        assertThat(json.has("displayName")).isFalse();
        assertThat(json.has("dataTimestamp")).isFalse();
        assertThat(json.get("hasMore").asBoolean()).isFalse();
        assertThat(json.has("nextCursor")).isTrue();
        assertThat(json.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("降级查询：响应带 dataTimestamp，取降级数据来源时间")
    void 降级查询带数据时间戳() throws Exception {
        Instant dataTimestamp = Instant.parse("2026-11-20T08:30:00Z");
        OwnerSelectorView.OwnerSelectorPage page = new OwnerSelectorView.OwnerSelectorPage(
            List.of(new OwnerSelectorView("9", "华东货主")), "9", true, dataTimestamp);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(page));

        assertThat(json.get("dataTimestamp").asText()).isEqualTo("2026-11-20T08:30:00Z");
        assertThat(json.get("nextCursor").asText()).isEqualTo("9");
        assertThat(json.get("hasMore").asBoolean()).isTrue();
    }
}
