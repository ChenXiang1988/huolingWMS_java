package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.OwnerDirectoryMapper;
import com.huoling.wms.masterdata.archive.infrastructure.persistence.OwnerDirectoryPO;
import com.huoling.wms.masterdata.domain.OwnerListSort;
import com.huoling.wms.masterdata.identity.domain.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 货主选择器（API-020 改造，tasks 1.12）：条目字段为 {@code ownerId}/{@code ownerNo}/{@code ownerName}，
 * 只查 {@code wms_owner}，数据域与键集分页由服务拼进查询参数。
 */
class OwnerSelectorServiceTest {

    private final List<OwnerDirectoryPO> rows = new ArrayList<>();

    private String capturedOwnerNo;
    private String capturedOwnerName;
    private boolean capturedAllOwners;
    private Collection<Long> capturedOwnerIds;
    private String capturedSortKey;
    private boolean capturedAscending;
    private String capturedCursorValue;
    private String capturedCursorOwnerNo;
    private int capturedLimit;

    private final OwnerDirectoryMapper mapper = new OwnerDirectoryMapper() {
        @Override
        public List<OwnerDirectoryPO> page(String ownerNo, String ownerName, boolean allOwners,
                                           Collection<Long> ownerIds, String sortKey,
                                           boolean ascending, String cursorValue,
                                           String cursorOwnerNo, int limit) {
            capturedOwnerNo = ownerNo;
            capturedOwnerName = ownerName;
            capturedAllOwners = allOwners;
            capturedOwnerIds = ownerIds;
            capturedSortKey = sortKey;
            capturedAscending = ascending;
            capturedCursorValue = cursorValue;
            capturedCursorOwnerNo = cursorOwnerNo;
            capturedLimit = limit;
            return rows;
        }
    };

    private OwnerSelectorService service;

    @BeforeEach
    void setUp() {
        service = new OwnerSelectorService(mapper);
    }

    @Test
    @DisplayName("正常查询：条目含 ownerId、ownerNo、ownerName，不带数据时间戳")
    void 正常查询字段() {
        rows.add(owner(9L, "OW000009", "华东货主"));
        rows.add(owner(12L, "OW000012", "华南货主"));

        OwnerSelectorService.OwnerPage page =
            service.page(DataScope.all(), null, null, OwnerListSort.parse(null, null), null, 20);

        assertThat(page.items()).extracting(OwnerSelectorService.OwnerOption::ownerId)
            .containsExactly("9", "12");
        assertThat(page.items()).extracting(OwnerSelectorService.OwnerOption::ownerNo)
            .containsExactly("OW000009", "OW000012");
        assertThat(page.items()).extracting(OwnerSelectorService.OwnerOption::ownerName)
            .containsExactly("华东货主", "华南货主");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("多取一条判定翻页，游标锚在本页最后一条的货主编号上")
    void 翻页() {
        rows.add(owner(9L, "OW000009", "华东货主"));
        rows.add(owner(12L, "OW000012", "华南货主"));
        rows.add(owner(15L, "OW000015", "西南货主"));

        OwnerSelectorService.OwnerPage page =
            service.page(DataScope.all(), null, null, OwnerListSort.parse(null, null), null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(capturedLimit).as("多取一条用于判定是否还有下一页").isEqualTo(3);
    }

    @Test
    @DisplayName("编号与名称同时给出时一起透传，由同一条查询取交集")
    void 条件取交集() {
        service.page(DataScope.all(), "OW000009", "华东", OwnerListSort.parse("ownerName", "desc"),
            null, 20);

        assertThat(capturedOwnerNo).isEqualTo("OW000009");
        assertThat(capturedOwnerName).isEqualTo("华东");
        assertThat(capturedSortKey).isEqualTo("OWNER_NAME");
        assertThat(capturedAscending).isFalse();
    }

    @Test
    @DisplayName("空白筛选值不下发条件；游标按排序键解码后作为锚点")
    void 空白归一与游标() {
        service.page(DataScope.all(), "", "  ", OwnerListSort.parse(null, null), null, 20);
        assertThat(capturedOwnerNo).isNull();
        assertThat(capturedOwnerName).isNull();

        // 两行才能让 limit=1 有下一页，从而拿到游标
        rows.add(owner(9L, "OW000009", "华东货主"));
        rows.add(owner(12L, "OW000012", "华南货主"));
        OwnerSelectorService.OwnerPage first =
            service.page(DataScope.all(), null, null, OwnerListSort.parse(null, null), null, 1);
        assertThat(first.nextCursor()).isNotNull();
        service.page(DataScope.all(), null, null, OwnerListSort.parse(null, null),
            first.nextCursor(), 1);

        assertThat(capturedCursorValue).isEqualTo("OW000009");
        assertThat(capturedCursorOwnerNo).isEqualTo("OW000009");
    }

    @Test
    @DisplayName("指定列表数据域把 id 集合下发到查询，全域不带条件")
    void 数据域注入() {
        service.page(DataScope.all(), null, null, OwnerListSort.parse(null, null), null, 20);
        assertThat(capturedAllOwners).isTrue();
        assertThat(capturedOwnerIds).isEmpty();

        service.page(new DataScope(false, Set.of(9L, 12L), true, Set.of()), null, null,
            OwnerListSort.parse(null, null), null, 20);
        assertThat(capturedAllOwners).isFalse();
        assertThat(capturedOwnerIds).containsExactlyInAnyOrder(9L, 12L);
    }

    @Test
    @DisplayName("单页上限之外按参数错误拒绝")
    void 页大小越界() {
        assertThatThrownBy(() -> service.page(DataScope.all(), null, null,
            OwnerListSort.parse(null, null), null, 0))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
        assertThatThrownBy(() -> service.page(DataScope.all(), null, null,
            OwnerListSort.parse(null, null), null, OwnerSelectorService.MAX_LIMIT + 1))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("游标形态不合法按参数错误拒绝；空域不回落成全量")
    void 游标非法与空域() {
        assertThatThrownBy(() -> service.page(DataScope.all(), null, null,
            OwnerListSort.parse(null, null), "abc", 20))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);

        assertThatThrownBy(() -> service.page(new DataScope(false, Set.of(), true, Set.of()),
            null, null, OwnerListSort.parse(null, null), null, 20))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.FORBIDDEN_OWNER);
    }

    private OwnerDirectoryPO owner(Long ownerId, String ownerNo, String ownerName) {
        OwnerDirectoryPO po = new OwnerDirectoryPO();
        po.setOwnerId(ownerId);
        po.setOwnerNo(ownerNo);
        po.setOwnerName(ownerName);
        return po;
    }
}
