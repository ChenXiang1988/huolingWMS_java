package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.SkuArchive;
import com.huoling.wms.masterdata.archive.support.ArchiveDbTestSupport;
import com.huoling.wms.masterdata.domain.SkuArchiveStatus;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.masterdata.domain.SkuRuleViolation;
import com.huoling.wms.masterdata.domain.SkuSortDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 列表排序与键集分页（契约 API-005、API-007「排序参数」与「分页约定」）：
 * {@code sortBy} 闭集为 {@code updateTime}、{@code ownerCode}、{@code name}，{@code sortDir} 闭集为
 * {@code asc}、{@code desc} 且缺省 {@code desc}；翻页按游标锚点走，不重不漏。
 *
 * <p>游标里带着排序字段与方向：换排序键后旧游标必须被拒绝，不按另一种顺序续翻——
 * 否则「大于/小于」比较会落到另一列上，必然重复或漏记录。
 */
@SpringBootTest(classes = HubApplication.class)
class SkuArchivePagingIT extends ArchiveDbTestSupport {

    private static final int TOTAL = 5;

    private long ownerId;

    @BeforeEach
    void createArchives() {
        ownerId = registerOwner();
        // 名称与货主编码故意与插入顺序错开，才能判断排序键究竟落在哪一列
        create("T-K-3", "6900000002001", "丙-苏打水");
        create("T-K-1", "6900000002002", "甲-矿泉水");
        create("T-K-5", "6900000002003", "戊-气泡水");
        create("T-K-2", "6900000002004", "乙-纯净水");
        create("T-K-4", "6900000002005", "丁-咖啡");
    }

    @Test
    @DisplayName("按货主编码升序与降序排列")
    void 按货主编码排序() {
        List<String> ascending = ownerCodes(fullPage(SkuListSort.parse("ownerCode", "asc")));
        List<String> descending = ownerCodes(fullPage(SkuListSort.parse("ownerCode", "desc")));

        assertThat(ascending).hasSize(TOTAL).isSorted();
        assertThat(descending).hasSize(TOTAL).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(ascending.get(0)).isEqualTo("T-K-1");
    }

    @Test
    @DisplayName("按名称升序排列；排序参数全缺省时按更新时间降序")
    void 按名称排序与缺省方向() {
        assertThat(names(fullPage(SkuListSort.parse("name", "asc")))).hasSize(TOTAL).isSorted();

        SkuListSort defaultSort = SkuListSort.parse(null, null);
        assertThat(defaultSort.key().name()).isEqualTo("UPDATE_TIME");
        assertThat(defaultSort.direction()).isEqualTo(SkuSortDirection.DESC);
        assertThat(fullPage(defaultSort)).hasSize(TOTAL);
    }

    @Test
    @DisplayName("键集分页逐页取完不重不漏，与一次性取全量的结果完全一致")
    void 游标翻页不重不漏() {
        for (String sortBy : List.of("updateTime", "ownerCode", "name")) {
            for (String sortDir : List.of("asc", "desc")) {
                SkuListSort sort = SkuListSort.parse(sortBy, sortDir);
                List<String> allAtOnce = archiveNos(fullPage(sort));
                List<String> paged = pageThrough(sort, 2);

                assertThat(paged).as("%s %s 逐页取完应与全量一致", sortBy, sortDir)
                    .containsExactlyElementsOf(allAtOnce);
                assertThat(new LinkedHashSet<>(paged)).as("%s %s 不得重复", sortBy, sortDir)
                    .hasSize(paged.size());
                assertThat(paged).hasSize(TOTAL);
            }
        }
    }

    @Test
    @DisplayName("游标与本次排序参数不一致时按参数错误拒绝，不回落成第一页")
    void 游标与排序不一致拒绝() {
        String cursorFromNameSort = cursorPage(SkuListSort.parse("name", "asc"), null).nextCursor();
        assertThat(cursorFromNameSort).as("需要有下一页游标才能验证").isNotNull();

        assertThatThrownBy(() -> cursorPage(SkuListSort.parse("ownerCode", "asc"), cursorFromNameSort))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);

        // 方向也参与校验：同一字段换方向同样拒绝
        assertThatThrownBy(() -> cursorPage(SkuListSort.parse("name", "desc"), cursorFromNameSort))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("排序字段与方向不在闭集内按参数错误拒绝")
    void 排序闭集外拒绝() {
        assertThatThrownBy(() -> SkuListSort.parse("barcode", "asc"))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
        assertThatThrownBy(() -> SkuListSort.parse("name", "upward"))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("列表响应带数据时间戳与下一页游标，最后一页不再给游标")
    void 响应带游标与数据时间戳() {
        SkuListSort sort = SkuListSort.parse("name", "asc");
        SkuArchiveQueryService.ArchivePage first = cursorPage(sort, null);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(first.dataTimestamp()).isNotNull();

        SkuArchiveQueryService.ArchivePage current = first;
        String cursor = first.nextCursor();
        while (current.hasMore()) {
            current = cursorPage(sort, cursor);
            cursor = current.nextCursor();
        }
        // 五条按每页两条：最后一页只剩一条，且没有下一页游标
        assertThat(current.items()).hasSize(1);
        assertThat(current.nextCursor()).isNull();
    }

    @Test
    @DisplayName("运营全量视图可按归属货主筛选，筛选后只返回该货主档案")
    void 运营全量按货主筛选() {
        long otherOwner = registerOwner();
        commandService.createDraft(otherOwner, fields("T-Z-1", "6900000002101", "别人的档案"), 0L);

        SkuArchiveQueryService.ArchivePage page = queryService.pageForHub(ownerId, null, null,
            SkuArchiveStatus.DRAFT, null, null, null, SkuListSort.parse(null, null), null, 20);

        assertThat(page.items()).hasSize(TOTAL);
        assertThat(page.items()).allSatisfy(item -> assertThat(item.ownerId()).isEqualTo(ownerId));
    }

    private List<String> pageThrough(SkuListSort sort, int pageSize) {
        List<String> collected = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 20; guard++) {
            SkuArchiveQueryService.ArchivePage page = queryService.pageForOwner(ownerId, null, null,
                null, null, null, null, sort, cursor, pageSize);
            page.items().forEach(item -> collected.add(item.archiveNo()));
            if (!page.hasMore() || page.nextCursor() == null) {
                return collected;
            }
            cursor = page.nextCursor();
        }
        throw new AssertionError("翻页超过 20 页仍未结束，游标可能无法前进");
    }

    private List<SkuArchive> fullPage(SkuListSort sort) {
        return queryService.pageForOwner(ownerId, null, null, null, null, null, null, sort, null, 20)
            .items();
    }

    private SkuArchiveQueryService.ArchivePage cursorPage(SkuListSort sort, String cursor) {
        return queryService.pageForOwner(ownerId, null, null, null, null, null, null, sort, cursor, 2);
    }

    private List<String> ownerCodes(List<SkuArchive> items) {
        return items.stream().map(item -> item.fields().ownerCode()).toList();
    }

    private List<String> names(List<SkuArchive> items) {
        return items.stream().map(item -> item.fields().name()).toList();
    }

    private List<String> archiveNos(List<SkuArchive> items) {
        return items.stream().map(SkuArchive::archiveNo).toList();
    }

    private void create(String ownerCode, String barcode, String name) {
        commandService.createDraft(ownerId, fields(ownerCode, barcode, name), 0L);
    }
}
