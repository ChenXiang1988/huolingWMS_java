package com.huoling.wms.masterdata.archive.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-020 改造后的响应字段逐字对齐契约：条目是 {@code ownerId}、{@code ownerNo}、{@code ownerName} 三项，
 * 响应不带 {@code dataTimestamp}（读权威库，不伪造降级标识）。
 */
class OwnerSelectorViewTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("条目含 ownerNo，且不带 dataTimestamp")
    void 条目字段() throws Exception {
        OwnerSelectorView.OwnerSelectorPage page = new OwnerSelectorView.OwnerSelectorPage(
            List.of(new OwnerSelectorView("9", "OW000009", "华东货主")), null, false);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(page));

        Set<String> itemFields = new HashSet<>();
        json.get("items").get(0).fieldNames().forEachRemaining(itemFields::add);
        assertThat(itemFields).containsExactlyInAnyOrder("ownerId", "ownerNo", "ownerName");
        assertThat(json.get("items").get(0).get("ownerNo").asText()).isEqualTo("OW000009");
        assertThat(json.has("displayName")).isFalse();
        assertThat(json.has("dataTimestamp")).as("读权威库，不得出现降级标识").isFalse();
        assertThat(json.get("hasMore").asBoolean()).isFalse();
        assertThat(json.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("翻页游标原样回出不透明字符串")
    void 翻页游标() throws Exception {
        OwnerSelectorView.OwnerSelectorPage page = new OwnerSelectorView.OwnerSelectorPage(
            List.of(new OwnerSelectorView("9", "OW000009", "华东货主")), "cursor-value", true);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(page));

        assertThat(json.get("nextCursor").asText()).isEqualTo("cursor-value");
        assertThat(json.get("hasMore").asBoolean()).isTrue();
    }
}
