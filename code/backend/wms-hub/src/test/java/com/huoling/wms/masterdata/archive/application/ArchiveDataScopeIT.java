package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.support.ArchiveDbTestSupport;
import com.huoling.wms.masterdata.domain.SkuArchiveAction;
import com.huoling.wms.masterdata.domain.SkuChangeableField;
import com.huoling.wms.masterdata.domain.SkuListSort;
import com.huoling.wms.masterdata.identity.domain.ClientType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 双重权限校验（00a INV-19、specs product-archive-access）：功能权限在入口切面校验，
 * 数据域在应用层解析后由仓储注入；货主越权与单仓跨仓都必须被拒，且不泄露对方数据。
 *
 * <p>两条不变量各有能红的反例：{@code FORBIDDEN_OWNER} 与 {@code FORBIDDEN_SITE}。
 * 把数据域注入去掉（例如 {@code requireOwner} 回落成全量），下面这些断言立即变红。
 */
@SpringBootTest(classes = HubApplication.class)
class ArchiveDataScopeIT extends ArchiveDbTestSupport {

    @Test
    @DisplayName("货主端数据域必须是货主令牌且账号绑定货主，否则 FORBIDDEN_OWNER")
    void 货主数据域取不到即拒绝() {
        long ownerId = registerOwner();
        long ownerAccount = createOwnerAccount(ownerId);
        long hubAccount = createHubAdminAccount();
        long noOwnerAccount = createAccountWithoutOwner();

        // 端标识不符：运营端令牌打货主端接口
        assertThatThrownBy(() -> accessGuard.requireOwner(hubAccount, ClientType.HUB))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.FORBIDDEN_OWNER);

        // 账号没有绑定货主：不回落成全量，直接拒绝
        assertThatThrownBy(() -> accessGuard.requireOwner(noOwnerAccount, ClientType.OWNER))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.FORBIDDEN_OWNER);

        assertThat(accessGuard.requireOwner(ownerAccount, ClientType.OWNER)).isEqualTo(ownerId);
    }

    @Test
    @DisplayName("货主越权访问他人档案按目标不存在处理，不泄露对方档案内容")
    void 货主越权访问他人档案() {
        long ownerA = registerOwner();
        long ownerB = registerOwner();
        long accountB = createOwnerAccount(ownerB);
        SkuArchiveCommandService.ArchiveCreatedView created =
            commandService.createDraft(ownerA, fields("T-A-1", "6900000000101", "A 的档案"), accountB);

        // 数据域注入：查到 0 行，而不是查到别人的行
        assertThat(archiveRepository.findByArchiveNoAndOwner(created.archiveId(), ownerB)).isEmpty();
        assertThatThrownBy(() ->
            commandService.edit(created.archiveId(), ownerB, 0L,
                fields("T-A-1", "6900000000101", "改别人的档案"), accountB))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.NOT_FOUND_ARCHIVE);

        // 缺货主数据域一律越权，不回落成全量
        assertThatThrownBy(() -> archiveRepository.findByArchiveNoAndOwner(created.archiveId(), null))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.FORBIDDEN_OWNER);

        assertThat(archiveRepository.findByArchiveNoAndOwner(created.archiveId(), ownerA)).isPresent();
    }

    @Test
    @DisplayName("货主查询只返回本货主档案，另一个货主同编码的档案不可见")
    void 货主查询限定本货主() {
        long ownerA = registerOwner();
        long ownerB = registerOwner();
        commandService.createDraft(ownerA, fields("T-SAME", "6900000000201", "矿泉水"), 0L);
        commandService.createDraft(ownerB, fields("T-SAME", "6900000000201", "矿泉水"), 0L);

        SkuArchiveQueryService.ArchivePage pageA = queryService.pageForOwner(ownerA, null, null, null,
            null, null, null, SkuListSort.parse(null, null), null, 20);

        assertThat(pageA.items()).hasSize(1);
        assertThat(pageA.items().get(0).ownerId()).isEqualTo(ownerA);
    }

    @Test
    @DisplayName("单仓端数据域必须是唯一一仓，缺仓域或多仓声明都返回 FORBIDDEN_SITE")
    void 单仓数据域必须唯一一仓() {
        long ownerId = registerOwner();
        long ownerAccount = createOwnerAccount(ownerId);
        long noWarehouse = createSiteAccount(List.of());
        long twoWarehouses = createSiteAccount(List.of(2001L, 2002L));
        long singleWarehouse = createSiteAccount(List.of(2001L));

        // 端标识不符
        assertThatThrownBy(() -> accessGuard.requireSite(ownerAccount, ClientType.OWNER))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.FORBIDDEN_SITE);

        // 未配置仓维度 → 按全部仓处理 → 不是唯一一仓
        assertThatThrownBy(() -> accessGuard.requireSite(noWarehouse, ClientType.SITE))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.FORBIDDEN_SITE);

        // 多仓声明
        assertThatThrownBy(() -> accessGuard.requireSite(twoWarehouses, ClientType.SITE))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.FORBIDDEN_SITE);

        assertThat(accessGuard.requireSite(singleWarehouse, ClientType.SITE).warehouseId())
            .isEqualTo(2001L);
    }

    @Test
    @DisplayName("单仓越权发起变更申请返回 FORBIDDEN_SITE，原档案不变")
    void 单仓越权发起变更申请() {
        long ownerA = registerOwner();
        long ownerB = registerOwner();
        SkuArchiveCommandService.ArchiveCreatedView created =
            commandService.createDraft(ownerA, fields("T-SITE-1", "6900000000301", "矿泉水"), 0L);
        activate(created.archiveId(), ownerA);
        long versionBefore = archiveRepository.findByArchiveNo(created.archiveId())
            .orElseThrow().version();

        // 本仓账号的货主数据域被限定为 ownerB：ownerA 的档案即越权
        long siteAccount = createSiteAccountScopedToOwners(List.of(2001L), List.of(ownerB));
        ArchiveAccessGuard.SiteScope scope =
            accessGuard.requireSite(siteAccount, ClientType.SITE);

        assertThatThrownBy(() -> changeRequestService.create(created.archiveId(), scope,
            SkuChangeableField.NAME, "改了名字", "越权尝试", siteAccount))
            .isInstanceOf(ArchiveException.class)
            .extracting(e -> ((ArchiveException) e).code())
            .isEqualTo(ArchiveErrorCodes.FORBIDDEN_SITE);

        assertThat(archiveRepository.findByArchiveNo(created.archiveId()).orElseThrow().version())
            .isEqualTo(versionBefore);
        assertThat(jdbc.queryForObject(
            "select count(*) from hub_product_change_request where archive_no = ?",
            Integer.class, created.archiveId())).isZero();
    }

    @Test
    @DisplayName("本仓可见的生效档案允许发起变更申请，申请落待审核且档案内容不变")
    void 本仓可见即可发起申请() {
        long ownerId = registerOwner();
        SkuArchiveCommandService.ArchiveCreatedView created =
            commandService.createDraft(ownerId, fields("T-SITE-2", "6900000000302", "矿泉水"), 0L);
        activate(created.archiveId(), ownerId);

        long siteAccount = createSiteAccountScopedToOwners(List.of(2001L), List.of(ownerId));
        ArchiveAccessGuard.SiteScope scope =
            accessGuard.requireSite(siteAccount, ClientType.SITE);

        ChangeRequestService.RequestCreatedView view = changeRequestService.create(
            created.archiveId(), scope, SkuChangeableField.NAME, "矿泉水（新包装）", "换包装",
            siteAccount);

        assertThat(view.requestNo()).isNotBlank();
        assertThat(jdbc.queryForObject(
            "select status from hub_product_change_request where request_no = ?",
            String.class, view.requestNo())).isEqualTo("PENDING");
        assertThat(archiveRepository.findByArchiveNo(created.archiveId()).orElseThrow()
            .fields().name()).isEqualTo("矿泉水");
    }

    /** 按状态机把档案推到启用：先提交，再由运营审核通过。 */
    private void activate(String archiveNo, long ownerId) {
        long version = archiveRepository.findByArchiveNo(archiveNo).orElseThrow().version();
        commandService.submit(archiveNo, ownerId, version, 0L);
        reviewService.review(archiveNo, SkuArchiveAction.APPROVE, null, 0L);
    }
}
