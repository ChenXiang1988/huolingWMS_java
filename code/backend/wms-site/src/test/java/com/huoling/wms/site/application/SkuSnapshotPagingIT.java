package com.huoling.wms.site.application;

import com.huoling.wms.common.error.ArchiveContractCodes;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.masterdata.domain.SkuSortDirection;
import com.huoling.wms.site.domain.SiteException;
import com.huoling.wms.site.support.SiteDbTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本仓档案查询（契约 API-010「请求」与「响应」）：{@code sortBy} 闭集为 {@code updateTime}、
 * {@code ownerCode}、{@code name}，{@code sortDir} 缺省 {@code desc}；键集分页不重不漏；
 * 响应必带数据时间戳，缓存落后时标 {@code stale}，不把缓存伪装成最新结果。
 *
 * <p>用例用自己的运行标记做名称前缀，查询时按名称过滤，因此同库上别的进程留下的同仓数据
 * 不会混进断言，用例之间也不依赖执行顺序。
 */
@SpringBootTest(classes = com.huoling.wms.site.SiteApplication.class,
    properties = {"wms.site.id=1001"})
class SkuSnapshotPagingIT extends SiteDbTestSupport {

    private static final int TOTAL = 5;
    private static final Instant BASE_TIME = Instant.parse("2026-01-05T08:00:00Z");

    private String runTag;

    @BeforeEach
    void createSnapshots() {
        runTag = "paging-" + nextId();
        // 名称与货主编码故意与插入顺序错开，才能判断排序键落在哪一列
        apply("3", "丙-苏打水", 0);
        apply("1", "甲-矿泉水", 1);
        apply("5", "戊-气泡水", 2);
        apply("2", "乙-纯净水", 3);
        apply("4", "丁-咖啡", 4);
    }

    @Test
    @DisplayName("按名称升序排列")
    void 按名称排序() {
        List<String> names = view(page(SkuListSort.parse("name", "asc"), null, 20)).stream()
            .map(SkuSnapshotQueryService.SnapshotView::name).toList();

        assertThat(names).hasSize(TOTAL).isSorted();
    }

    @Test
    @DisplayName("按货主编码升序与降序排列，方向分别生效")
    void 按货主编码排序() {
        List<String> ascending = view(page(SkuListSort.parse("ownerCode", "asc"), null, 20)).stream()
            .map(SkuSnapshotQueryService.SnapshotView::ownerCode).toList();
        List<String> descending = view(page(SkuListSort.parse("ownerCode", "desc"), null, 20)).stream()
            .map(SkuSnapshotQueryService.SnapshotView::ownerCode).toList();

        assertThat(ascending).hasSize(TOTAL).isSorted();
        assertThat(descending).hasSize(TOTAL).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    @DisplayName("按更新时间排序走数据时间戳，缺省方向为降序")
    void 按更新时间排序() {
        SkuListSort defaultSort = SkuListSort.parse(null, null);
        assertThat(defaultSort.key().name()).isEqualTo("UPDATE_TIME");
        assertThat(defaultSort.direction()).isEqualTo(SkuSortDirection.DESC);

        List<Instant> timestamps = view(page(defaultSort, null, 20)).stream()
            .map(SkuSnapshotQueryService.SnapshotView::dataTimestamp).toList();
        assertThat(timestamps).hasSize(TOTAL).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    @DisplayName("键集分页逐页取完不重不漏，与一次性取全量一致")
    void 游标翻页不重不漏() {
        for (String sortBy : List.of("updateTime", "ownerCode", "name")) {
            for (String sortDir : List.of("asc", "desc")) {
                SkuListSort sort = SkuListSort.parse(sortBy, sortDir);
                List<String> allAtOnce = view(page(sort, null, 20)).stream()
                    .map(SkuSnapshotQueryService.SnapshotView::archiveId).toList();
                List<String> paged = pageThrough(sort, 2);

                assertThat(paged).as("%s %s 逐页取完", sortBy, sortDir)
                    .containsExactlyElementsOf(allAtOnce);
                assertThat(paged).hasSize(TOTAL);
                assertThat(new LinkedHashSet<>(paged)).as("%s %s 不得重复", sortBy, sortDir)
                    .hasSize(paged.size());
            }
        }
    }

    @Test
    @DisplayName("游标与本次排序参数不一致时按参数错误拒绝")
    void 游标与排序不一致拒绝() {
        String cursor = page(SkuListSort.parse("name", "asc"), null, 2).nextCursor();
        assertThat(cursor).isNotBlank();

        assertThatThrownBy(() ->
            page(SkuListSort.parse("ownerCode", "asc"), cursor, 2))
            .isInstanceOf(SiteException.class)
            .extracting(e -> ((SiteException) e).code())
            .isEqualTo(ArchiveContractCodes.PARAM_INVALID);
    }

    @Test
    @DisplayName("响应带数据时间戳与待刷新标记：陈旧缓存标 stale，刚同步的不标")
    void 响应带数据时间戳与待刷新标记() {
        SkuSnapshotQueryService.SnapshotPage page = page(SkuListSort.parse("name", "asc"), null, 20);

        assertThat(page.dataTimestamp()).isNotNull();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.items()).allSatisfy(item -> {
            assertThat(item.dataTimestamp()).as("条目必须带数据时间戳").isNotNull();
            // 造数用的是固定过去时刻，超出五分钟新鲜度阈值
            assertThat(item.stale()).as("陈旧缓存必须标记待刷新").isTrue();
        });

        String freshName = "fresh-" + runTag;
        applySnapshot("T-K-FRESH", freshName, "ACTIVE", 9L, Instant.now());
        SkuSnapshotQueryService.SnapshotPage fresh = snapshotQueryService.page(SITE_ID, null, freshName,
            null, null, null, SkuListSort.parse(null, null), null, 20);

        assertThat(fresh.items()).hasSize(1);
        assertThat(fresh.items().get(0).stale()).as("阈值内的缓存不标待刷新").isFalse();
        assertThat(fresh.items().get(0).dataTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("筛选条件生效：货主编码精确匹配、名称模糊匹配只返回命中条目")
    void 筛选生效() {
        String ownerCode = "T-K-" + runTag + "-1";
        SkuSnapshotQueryService.SnapshotPage byOwner = snapshotQueryService.page(SITE_ID, ownerCode,
            null, null, null, null, SkuListSort.parse(null, null), null, 20);
        assertThat(byOwner.items()).hasSize(1);
        assertThat(byOwner.items().get(0).name()).isEqualTo(runTag + "-甲-矿泉水");

        SkuSnapshotQueryService.SnapshotPage byName = snapshotQueryService.page(SITE_ID, null,
            runTag + "-甲", null, null, null, SkuListSort.parse(null, null), null, 20);
        assertThat(byName.items()).hasSize(1);
    }

    @Test
    @DisplayName("limit 超出 1 到 100 的闭集按参数错误拒绝")
    void limit闭集() {
        assertThatThrownBy(() -> page(SkuListSort.parse(null, null), null, 0))
            .isInstanceOf(SiteException.class)
            .extracting(e -> ((SiteException) e).code())
            .isEqualTo(ArchiveContractCodes.PARAM_INVALID);
        assertThatThrownBy(() -> page(SkuListSort.parse(null, null), null, 101))
            .isInstanceOf(SiteException.class)
            .extracting(e -> ((SiteException) e).code())
            .isEqualTo(ArchiveContractCodes.PARAM_INVALID);
    }

    private SkuSnapshotQueryService.SnapshotPage page(SkuListSort sort, String cursor, int limit) {
        return snapshotQueryService.page(SITE_ID, null, runTag, null, null, null, sort, cursor, limit);
    }

    private List<String> pageThrough(SkuListSort sort, int pageSize) {
        List<String> collected = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 20; guard++) {
            SkuSnapshotQueryService.SnapshotPage page = page(sort, cursor, pageSize);
            view(page).forEach(item -> collected.add(item.archiveId()));
            if (!page.hasMore() || page.nextCursor() == null) {
                return collected;
            }
            cursor = page.nextCursor();
        }
        throw new AssertionError("翻页超过 20 页仍未结束，游标可能无法前进");
    }

    private List<SkuSnapshotQueryService.SnapshotView> view(SkuSnapshotQueryService.SnapshotPage page) {
        return page.items();
    }

    private void apply(String ownerCodeSuffix, String name, int index) {
        applySnapshot("T-K-" + runTag + "-" + ownerCodeSuffix, runTag + "-" + name, "ACTIVE",
            1L + index, BASE_TIME.plus(Duration.ofHours(index)));
    }
}
