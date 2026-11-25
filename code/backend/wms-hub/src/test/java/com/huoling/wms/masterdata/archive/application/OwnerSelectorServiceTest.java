package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.OwnerDirectoryMapper;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.OwnerDirectoryPO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 货主选择器（API-020）：条目字段为 {@code ownerId} 与 {@code ownerName}，筛选条件透传给投影查询，
 * 正常查询不产出数据时间戳（只有降级来源才带）。
 */
class OwnerSelectorServiceTest {

    private final List<OwnerDirectoryPO> rows = new ArrayList<>();

    private String capturedOwnerCode;
    private String capturedName;
    private Long capturedCursor;
    private int capturedLimit;

    private final OwnerDirectoryMapper mapper = (ownerCode, name, cursorId, limit) -> {
        capturedOwnerCode = ownerCode;
        capturedName = name;
        capturedCursor = cursorId;
        capturedLimit = limit;
        return rows;
    };

    private OwnerSelectorService service;

    @BeforeEach
    void setUp() {
        service = new OwnerSelectorService(mapper);
    }

    @Test
    @DisplayName("正常查询：条目字段为 ownerName，不带数据时间戳")
    void 正常查询字段与时间戳() {
        rows.add(owner(9L, "华东货主"));
        rows.add(owner(12L, "华南货主"));

        OwnerSelectorService.OwnerPage page = service.page(null, null, null, 20);

        assertThat(page.items()).extracting(OwnerSelectorService.OwnerOption::ownerId)
            .containsExactly("9", "12");
        assertThat(page.items()).extracting(OwnerSelectorService.OwnerOption::ownerName)
            .containsExactly("华东货主", "华南货主");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.dataTimestamp()).isNull();
    }

    @Test
    @DisplayName("多取一条判定翻页，游标锚在本页最后一个货主标识")
    void 翻页() {
        rows.add(owner(9L, "华东货主"));
        rows.add(owner(12L, "华南货主"));
        rows.add(owner(15L, "西南货主"));

        OwnerSelectorService.OwnerPage page = service.page(null, null, null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isEqualTo("12");
        assertThat(capturedLimit).isEqualTo(3);
    }

    @Test
    @DisplayName("货主标识与展示名同时给出时两者一起透传，由同一条查询取交集")
    void 条件取交集() {
        service.page("9", "华东", null, 20);

        assertThat(capturedOwnerCode).isEqualTo("9");
        assertThat(capturedName).isEqualTo("华东");
    }

    @Test
    @DisplayName("空与空白筛选值不下发条件，游标按货主标识解析")
    void 空白归一与游标() {
        service.page("", "  ", "12", 20);

        assertThat(capturedOwnerCode).isNull();
        assertThat(capturedName).isNull();
        assertThat(capturedCursor).isEqualTo(12L);
    }

    @Test
    @DisplayName("单页上限之外按参数错误拒绝")
    void 页大小越界() {
        assertThatThrownBy(() -> service.page(null, null, null, 0))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
        assertThatThrownBy(() -> service.page(null, null, null, OwnerSelectorService.MAX_LIMIT + 1))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("游标形态不合法按参数错误拒绝")
    void 游标非法() {
        assertThatThrownBy(() -> service.page(null, null, "abc", 20))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
    }

    private OwnerDirectoryPO owner(Long ownerId, String ownerName) {
        OwnerDirectoryPO po = new OwnerDirectoryPO();
        po.setOwnerId(ownerId);
        po.setOwnerName(ownerName);
        return po;
    }
}
