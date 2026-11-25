package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.ChangeRequest;
import com.huoling.wms.masterdata.archive.domain.SkuArchive;
import com.huoling.wms.masterdata.archive.support.ArchiveDbTestSupport;
import com.huoling.wms.masterdata.domain.SkuArchiveAction;
import com.huoling.wms.masterdata.domain.SkuChangeRequestRule;
import com.huoling.wms.masterdata.domain.SkuChangeRequestStatus;
import com.huoling.wms.masterdata.domain.SkuChangeableField;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.masterdata.domain.SkuRuleViolation;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单仓变更申请的完整链路（specs product-archive-review「单仓变更申请」「生效同步与一致性」；
 * 契约 API-011 至 API-013）：单仓只能发起，运营审核通过后写回权威档案、递增版本号并产生下行事件，
 * 驳回必须保留原因；同一档案同时只允许一条待审核申请。
 */
@SpringBootTest(classes = HubApplication.class)
class ChangeRequestFlowIT extends ArchiveDbTestSupport {

    private static final long WAREHOUSE_ID = 3001L;

    @Test
    @DisplayName("审核通过写回权威档案：字段生效、版本递增、申请转已通过并产生两条下行事件")
    void 审核通过写回档案并下行() {
        long ownerId = registerOwner();
        String archiveNo = activeArchive(ownerId, "T-C-1", "6900000003001", "矿泉水");
        ArchiveAccessGuard.SiteScope scope = scopedTo(ownerId);
        long versionBefore = archive(archiveNo).version();

        ChangeRequestService.RequestCreatedView created = changeRequestService.create(archiveNo, scope,
            SkuChangeableField.NAME, "矿泉水（新包装）", "换包装", 0L);
        assertThat(created.status()).isEqualTo(SkuChangeRequestStatus.PENDING);
        assertThat(archive(archiveNo).fields().name()).as("审核前档案不得被改写").isEqualTo("矿泉水");

        ChangeRequestService.RequestReviewedView reviewed = changeRequestService.review(
            created.requestNo(), true, null, 0L);

        assertThat(reviewed.status()).isEqualTo(SkuChangeRequestStatus.APPROVED);
        SkuArchive after = archive(archiveNo);
        assertThat(after.fields().name()).isEqualTo("矿泉水（新包装）");
        assertThat(after.version()).isEqualTo(versionBefore + 1);
        assertThat(reviewed.archiveVersion()).isEqualTo(after.version());
        assertThat(jdbc.queryForObject(
            "select archive_version from hub_product_change_request where request_no = ?",
            Long.class, created.requestNo())).isEqualTo(after.version());

        // 下行：档案变更事件（事件标识含新版本）与申请结果事件
        assertThat(jdbc.queryForObject("""
            select event_id from hub_outbox
             where aggregate_no = ? and event_type = 'SKU_ARCHIVE_CHANGED'
            """, String.class, archiveNo)).isEqualTo(archiveNo + "#" + after.version());
        assertThat(jdbc.queryForObject("""
            select count(*) from hub_outbox
             where aggregate_no = ? and event_type = 'SKU_CHANGE_REQUEST_REVIEWED'
            """, Integer.class, archiveNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("驳回保留原因并下行，档案内容与版本不变")
    void 驳回保留原因() {
        long ownerId = registerOwner();
        String archiveNo = activeArchive(ownerId, "T-C-2", "6900000003002", "苏打水");
        long versionBefore = archive(archiveNo).version();
        ChangeRequestService.RequestCreatedView created = changeRequestService.create(archiveNo,
            scopedTo(ownerId), SkuChangeableField.SPECIFICATION, "500ml", "规格调整", 0L);

        ChangeRequestService.RequestReviewedView reviewed = changeRequestService.review(
            created.requestNo(), false, "规格描述与实物不符", 0L);

        assertThat(reviewed.status()).isEqualTo(SkuChangeRequestStatus.REJECTED);
        assertThat(jdbc.queryForObject(
            "select review_reason from hub_product_change_request where request_no = ?",
            String.class, created.requestNo())).isEqualTo("规格描述与实物不符");
        assertThat(archive(archiveNo).version()).isEqualTo(versionBefore);
        assertThat(archive(archiveNo).fields().specification()).isEqualTo("550ml");

        String payload = jdbc.queryForObject("""
            select payload_json from hub_outbox
             where aggregate_no = ? and event_type = 'SKU_CHANGE_REQUEST_REVIEWED'
            """, String.class, archiveNo);
        assertThat(payload).as("驳回原因必须随事件下行").contains("规格描述与实物不符");
    }

    @Test
    @DisplayName("驳回不给原因按参数错误拒绝，申请保持待审核")
    void 驳回必须给原因() {
        long ownerId = registerOwner();
        String archiveNo = activeArchive(ownerId, "T-C-3", "6900000003003", "气泡水");
        ChangeRequestService.RequestCreatedView created = changeRequestService.create(archiveNo,
            scopedTo(ownerId), SkuChangeableField.NAME, "气泡水（新）", "改名", 0L);

        assertThatThrownBy(() -> changeRequestService.review(created.requestNo(), false, " ", 0L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
        assertThat(jdbc.queryForObject(
            "select status from hub_product_change_request where request_no = ?",
            String.class, created.requestNo())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("同一档案同时只允许一条待审核申请，重复发起按状态冲突拒绝")
    void 同一档案只允许一条待审申请() {
        long ownerId = registerOwner();
        String archiveNo = activeArchive(ownerId, "T-C-4", "6900000003004", "纯净水");
        ArchiveAccessGuard.SiteScope scope = scopedTo(ownerId);
        changeRequestService.create(archiveNo, scope, SkuChangeableField.NAME, "纯净水（新）", "改名", 0L);

        assertThatThrownBy(() -> changeRequestService.create(archiveNo, scope,
            SkuChangeableField.CATEGORY, "饮用水", "改分类", 0L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.STATE_INVALID);

        assertThat(jdbc.queryForObject("""
            select count(*) from hub_product_change_request
             where archive_no = ? and status = 'PENDING'
            """, Integer.class, archiveNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("非启用态档案不接受变更申请，草稿与停用都被状态规则挡住")
    void 非启用态不接受申请() {
        long ownerId = registerOwner();
        String draft = commandService.createDraft(ownerId, fields("T-C-5", "6900000003005", "咖啡"), 0L)
            .archiveId();
        ArchiveAccessGuard.SiteScope scope = scopedTo(ownerId);

        assertThatThrownBy(() -> changeRequestService.create(draft, scope,
            SkuChangeableField.NAME, "咖啡（新）", "改名", 0L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.STATE_INVALID);

        String disabled = activeArchive(ownerId, "T-C-6", "6900000003006", "绿茶");
        commandService.disable(disabled, ownerId, archive(disabled).version(), "停售", 0L);
        assertThatThrownBy(() -> changeRequestService.create(disabled, scope,
            SkuChangeableField.NAME, "绿茶（新）", "改名", 0L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.STATE_INVALID);

        // 状态守卫的判定在 Kernel：闭集外与非法档案态都不放行
        assertThat(SkuChangeRequestRule.target(true)).isEqualTo(SkuChangeRequestStatus.APPROVED);
        assertThat(SkuChangeRequestRule.target(false)).isEqualTo(SkuChangeRequestStatus.REJECTED);
    }

    @Test
    @DisplayName("已审核的申请不能再审一次，按状态冲突拒绝")
    void 已审核申请不可重复审核() {
        long ownerId = registerOwner();
        String archiveNo = activeArchive(ownerId, "T-C-7", "6900000003007", "红茶");
        ChangeRequestService.RequestCreatedView created = changeRequestService.create(archiveNo,
            scopedTo(ownerId), SkuChangeableField.NAME, "红茶（新）", "改名", 0L);
        changeRequestService.review(created.requestNo(), true, null, 0L);

        assertThatThrownBy(() -> changeRequestService.review(created.requestNo(), true, null, 0L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.STATE_INVALID);
        assertThatThrownBy(() -> changeRequestService.review("CRQ-NOT-EXIST", true, null, 0L))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.NOT_FOUND_ARCHIVE);
    }

    @Test
    @DisplayName("运营查询变更申请可按货主与本仓筛选，排序字段 name 映射到申请字段")
    void 运营查询变更申请() {
        long ownerId = registerOwner();
        String first = activeArchive(ownerId, "T-C-8", "6900000003008", "矿泉水");
        String second = activeArchive(ownerId, "T-C-9", "6900000003009", "苏打水");
        ArchiveAccessGuard.SiteScope scope = scopedTo(ownerId);
        changeRequestService.create(first, scope, SkuChangeableField.STORAGE_CONDITION, "冷藏", "改条件", 0L);
        changeRequestService.create(second, scope, SkuChangeableField.CATEGORY, "饮用水", "改分类", 0L);

        ChangeRequestService.ChangeRequestPage page = changeRequestService.pageForHub(ownerId,
            WAREHOUSE_ID, SkuChangeRequestStatus.PENDING, null, null,
            SkuListSort.parse("name", "asc"), null, 20);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items()).allSatisfy(item -> {
            assertThat(item.ownerId()).isEqualTo(ownerId);
            assertThat(item.warehouseId()).isEqualTo(WAREHOUSE_ID);
        });
        // name 排序落在申请字段上：CATEGORY 在 STORAGE_CONDITION 之前
        assertThat(page.items()).extracting(item -> item.field().name())
            .containsExactly("CATEGORY", "STORAGE_CONDITION");

        // 键集翻页：每页一条，两次取完，不重不漏且顺序与一次性取全量一致
        SkuListSort sort = SkuListSort.parse("name", "asc");
        ChangeRequestService.ChangeRequestPage firstPage =
            changeRequestService.pageForHub(ownerId, WAREHOUSE_ID,
                SkuChangeRequestStatus.PENDING, null, null, sort, null, 1);
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();

        ChangeRequestService.ChangeRequestPage secondPage =
            changeRequestService.pageForHub(ownerId, WAREHOUSE_ID,
                SkuChangeRequestStatus.PENDING, null, null, sort, firstPage.nextCursor(), 1);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.hasMore()).isFalse();
        assertThat(secondPage.nextCursor()).isNull();

        List<String> paged = List.of(firstPage.items().get(0).requestNo(),
            secondPage.items().get(0).requestNo());
        assertThat(paged).containsExactlyElementsOf(
            page.items().stream().map(ChangeRequest::requestNo).toList());
        assertThat(paged).doesNotHaveDuplicates();
    }

    private SkuArchive archive(String archiveNo) {
        return archiveRepository.findByArchiveNo(archiveNo).orElseThrow();
    }

    private ArchiveAccessGuard.SiteScope scopedTo(long ownerId) {
        long siteAccount = createSiteAccountScopedToOwners(List.of(WAREHOUSE_ID), List.of(ownerId));
        return accessGuard.requireSite(siteAccount, ClientType.SITE);
    }

    private String activeArchive(long ownerId, String ownerCode, String barcode, String name) {
        String archiveNo = commandService.createDraft(ownerId, fields(ownerCode, barcode, name), 0L)
            .archiveId();
        commandService.submit(archiveNo, ownerId, 0L, 0L);
        reviewService.review(archiveNo, SkuArchiveAction.APPROVE, null, 0L);
        return archiveNo;
    }
}
