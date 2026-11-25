package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.SkuArchive;
import com.huoling.wms.masterdata.archive.support.ArchiveDbTestSupport;
import com.huoling.wms.masterdata.domain.SkuArchiveAction;
import com.huoling.wms.masterdata.domain.SkuArchiveStatus;
import com.huoling.wms.masterdata.domain.SkuRuleViolation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审核状态机与批量处理（specs product-archive-review「审核状态机」「运营审核与批量处理」、契约 API-008/009）：
 * 只允许草稿→待审核→启用/驳回、驳回→待审核、启用→停用、停用→待审核；
 * 批量必须返回与输入条数一致的明细，单条失败不影响其他条目。
 */
@SpringBootTest(classes = HubApplication.class)
class SkuReviewBatchIT extends ArchiveDbTestSupport {

    @Test
    @DisplayName("批量审核逐条返回：状态已变的一条失败并带错误码，其余照常生效")
    void 批量部分失败逐条结果() {
        long ownerId = registerOwner();
        String first = pendingArchive(ownerId, "T-B-1", "6900000001001", "矿泉水");
        String second = pendingArchive(ownerId, "T-B-2", "6900000001002", "苏打水");
        String stillDraft = draftArchive(ownerId, "T-B-3", "6900000001003", "气泡水");

        List<String> ids = List.of(first, second, stillDraft);
        SkuReviewService.BatchView view =
            reviewService.batch(ids, SkuArchiveAction.APPROVE, null, 0L);

        assertThat(view.total()).as("结果条数必须与输入一致").isEqualTo(ids.size());
        assertThat(view.items()).hasSize(ids.size());
        assertThat(view.succeeded()).isEqualTo(2);
        assertThat(view.failed()).isEqualTo(1);

        SkuReviewService.BatchItem failure = view.items().stream()
            .filter(item -> !item.success()).findFirst().orElseThrow();
        assertThat(failure.archiveNo()).isEqualTo(stillDraft);
        assertThat(failure.code()).isEqualTo(ArchiveErrorCodes.STATE_INVALID);
        assertThat(failure.message()).isNotBlank();

        // 单条失败不影响其他条目：另外两条已生效
        assertThat(statusOf(first)).isEqualTo(SkuArchiveStatus.ACTIVE);
        assertThat(statusOf(second)).isEqualTo(SkuArchiveStatus.ACTIVE);
        // 失败的那条保持原状态，未被半改
        assertThat(statusOf(stillDraft)).isEqualTo(SkuArchiveStatus.DRAFT);
    }

    @Test
    @DisplayName("审核通过：状态生效、版本递增、审核留痕与下行消息同事务落库")
    void 审核通过四件事同事务() {
        long ownerId = registerOwner();
        String archiveNo = pendingArchive(ownerId, "T-R-1", "6900000001101", "矿泉水");
        long versionBefore = archiveRepository.findByArchiveNo(archiveNo).orElseThrow().version();

        SkuReviewService.ReviewView view =
            reviewService.review(archiveNo, SkuArchiveAction.APPROVE, null, 0L);

        assertThat(view.status()).isEqualTo(SkuArchiveStatus.ACTIVE);
        assertThat(view.version()).isEqualTo(versionBefore + 1);

        SkuArchive archive = archiveRepository.findByArchiveNo(archiveNo).orElseThrow();
        assertThat(archive.activatedAt()).as("必须记录生效时间").isNotNull();
        assertThat(jdbc.queryForObject(
            "select count(*) from hub_sku_review where archive_no = ? and action = 'APPROVE'",
            Integer.class, archiveNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select event_id from hub_outbox where aggregate_no = ? and event_type = 'SKU_ARCHIVE_ACTIVATED'
            """, String.class, archiveNo))
            .as("事件标识为档案编号加版本号")
            .isEqualTo(archiveNo + "#" + view.version());
    }

    @Test
    @DisplayName("驳回必须给出原因：缺原因按参数错误拒绝，档案保持待审核")
    void 驳回必须给原因() {
        long ownerId = registerOwner();
        String archiveNo = pendingArchive(ownerId, "T-R-2", "6900000001102", "矿泉水");

        assertThatThrownBy(() -> reviewService.review(archiveNo, SkuArchiveAction.REJECT, "  ", 0L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);

        assertThat(statusOf(archiveNo)).isEqualTo(SkuArchiveStatus.PENDING);
        assertThat(jdbc.queryForObject("select count(*) from hub_sku_review where archive_no = ?",
            Integer.class, archiveNo)).isZero();
    }

    @Test
    @DisplayName("驳回后重提回到待审核，原驳回原因保留供审核参考")
    void 驳回后重提() {
        long ownerId = registerOwner();
        String archiveNo = pendingArchive(ownerId, "T-R-3", "6900000001103", "矿泉水");

        reviewService.review(archiveNo, SkuArchiveAction.REJECT, "规格描述不符", 0L);
        assertThat(statusOf(archiveNo)).isEqualTo(SkuArchiveStatus.REJECTED);
        assertThat(jdbc.queryForObject(
            "select reason from hub_sku_review where archive_no = ? and action = 'REJECT'",
            String.class, archiveNo)).isEqualTo("规格描述不符");

        long version = archiveRepository.findByArchiveNo(archiveNo).orElseThrow().version();
        commandService.submit(archiveNo, ownerId, version, 0L);
        assertThat(statusOf(archiveNo)).isEqualTo(SkuArchiveStatus.PENDING);
        assertThat(jdbc.queryForObject(
            "select reason from hub_sku_review where archive_no = ? and action = 'REJECT'",
            String.class, archiveNo)).as("驳回原因不因重提而丢失").isEqualTo("规格描述不符");
    }

    @Test
    @DisplayName("未列举的迁移一律被状态机拒绝，档案状态与版本都不变")
    void 非法迁移被拒() {
        long ownerId = registerOwner();
        String draft = draftArchive(ownerId, "T-S-1", "6900000001201", "矿泉水");
        long draftVersion = archiveRepository.findByArchiveNo(draft).orElseThrow().version();

        // 草稿不能越过提交直接审核通过
        assertThatThrownBy(() -> reviewService.review(draft, SkuArchiveAction.APPROVE, null, 0L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.STATE_INVALID);

        // 草稿不能被驳回
        assertThatThrownBy(() -> reviewService.review(draft, SkuArchiveAction.REJECT, "原因", 0L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.STATE_INVALID);

        String active = pendingArchive(ownerId, "T-S-2", "6900000001202", "苏打水");
        reviewService.review(active, SkuArchiveAction.APPROVE, null, 0L);
        long activeVersion = archiveRepository.findByArchiveNo(active).orElseThrow().version();

        // 已生效的档案不能再审一次，也不能被直接编辑覆盖
        assertThatThrownBy(() -> reviewService.review(active, SkuArchiveAction.APPROVE, null, 0L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.STATE_INVALID);
        assertThatThrownBy(() -> commandService.edit(active, ownerId, activeVersion,
            fields("T-S-2", "6900000001202", "直接改启用档案"), 0L))
            .isInstanceOf(SkuRuleViolation.class)
            .extracting(e -> ((SkuRuleViolation) e).code())
            .isEqualTo(ArchiveErrorCodes.STATE_INVALID);

        assertThat(statusOf(draft)).isEqualTo(SkuArchiveStatus.DRAFT);
        assertThat(archiveRepository.findByArchiveNo(draft).orElseThrow().version())
            .isEqualTo(draftVersion);
        assertThat(statusOf(active)).isEqualTo(SkuArchiveStatus.ACTIVE);
        assertThat(archiveRepository.findByArchiveNo(active).orElseThrow().version())
            .isEqualTo(activeVersion);
    }

    @Test
    @DisplayName("批量条目超过一百条在入口按参数错误拒绝")
    void 批量超上限拒绝() {
        List<String> tooMany = new ArrayList<>();
        for (int index = 0; index <= SkuReviewService.BATCH_MAX; index++) {
            tooMany.add("SKU" + index);
        }

        assertThatThrownBy(() -> reviewService.batch(tooMany, SkuArchiveAction.APPROVE, null, 0L))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
        assertThatThrownBy(() -> reviewService.batch(List.of(), SkuArchiveAction.APPROVE, null, 0L))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.PARAM_INVALID);
    }

    private String draftArchive(long ownerId, String ownerCode, String barcode, String name) {
        return commandService.createDraft(ownerId, fields(ownerCode, barcode, name), 0L).archiveId();
    }

    private String pendingArchive(long ownerId, String ownerCode, String barcode, String name) {
        String archiveNo = draftArchive(ownerId, ownerCode, barcode, name);
        long version = archiveRepository.findByArchiveNo(archiveNo).orElseThrow().version();
        commandService.submit(archiveNo, ownerId, version, 0L);
        return archiveNo;
    }

    private SkuArchiveStatus statusOf(String archiveNo) {
        return archiveRepository.findByArchiveNo(archiveNo).orElseThrow().status();
    }
}
