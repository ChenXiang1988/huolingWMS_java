package com.huoling.wms.masterdata.archive.infrastructure;

import com.huoling.wms.masterdata.archive.application.SkuArchiveCommandService;
import com.huoling.wms.masterdata.archive.domain.SkuArchive;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveRepository;
import com.huoling.wms.masterdata.archive.support.ArchiveDbTestSupport;
import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.domain.SkuArchiveAction;
import com.huoling.wms.masterdata.domain.SkuArchiveStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据库层强制手段（design「强制手段分层」、00a INV-06 与 INV-18）：连真 PostgreSQL 验证，
 * 不用内存库——部分唯一索引、CHECK 与触发器是这三条不变量的真实落点。
 *
 * <p>每个用例都直接往库里插脏数据，断言数据库自己拒绝；把索引条件或约束改坏，用例即变红。
 */
@SpringBootTest(classes = HubApplication.class)
class SkuArchiveConstraintIT extends ArchiveDbTestSupport {

    private static final String OWNER_CODE = "T-CODE-001";
    private static final String BARCODE = "6900000000011";

    @Test
    @DisplayName("同货主同编码在生效竞争者存在时被部分唯一索引拒绝，写不进去两行")
    void 货主内编码唯一() {
        long ownerId = registerOwner();
        insertRow(ownerId, nextId(), OWNER_CODE, BARCODE, "ACTIVE", false);
        long secondId = nextId();

        assertThatThrownBy(() ->
            insertRow(ownerId, secondId, OWNER_CODE, "6900000000028", "PENDING", false))
            .isInstanceOf(DataAccessException.class);
        assertThat(countByOwner(ownerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("同货主同条码被部分唯一索引拒绝，与编码是两条独立索引")
    void 货主内条码唯一() {
        long ownerId = registerOwner();
        insertRow(ownerId, nextId(), OWNER_CODE, BARCODE, "ACTIVE", false);

        assertThatThrownBy(() ->
            insertRow(ownerId, nextId(), "T-CODE-002", BARCODE, "PENDING", false))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("草稿态允许重复编码与条码，判重只在提交环节执行")
    void 草稿态允许重复() {
        long ownerId = registerOwner();
        insertRow(ownerId, nextId(), OWNER_CODE, BARCODE, "DRAFT", false);
        insertRow(ownerId, nextId(), OWNER_CODE, BARCODE, "DRAFT", false);

        assertThat(countByOwner(ownerId)).isEqualTo(2);
    }

    @Test
    @DisplayName("停用记录不参与唯一索引：同编码可被新草稿与新启用档案使用")
    void 停用后同编码可重用() {
        long ownerId = registerOwner();
        insertRow(ownerId, nextId(), OWNER_CODE, BARCODE, "DISABLED", true);

        // 新草稿与新启用档案都能用同一个编码：停用记录已被排除在索引条件外
        insertRow(ownerId, nextId(), OWNER_CODE, "6900000000035", "DRAFT", false);
        insertRow(ownerId, nextId(), OWNER_CODE, "6900000000042", "ACTIVE", false);

        assertThat(countByOwner(ownerId)).isEqualTo(3);
    }

    @Test
    @DisplayName("不同货主用相同编码与条码互不影响，各自独立受理")
    void 跨货主不互相判重() {
        long ownerA = registerOwner();
        long ownerB = registerOwner();
        insertRow(ownerA, nextId(), OWNER_CODE, BARCODE, "ACTIVE", false);
        insertRow(ownerB, nextId(), OWNER_CODE, BARCODE, "ACTIVE", false);

        assertThat(countByOwner(ownerA)).isEqualTo(1);
        assertThat(countByOwner(ownerB)).isEqualTo(1);
    }

    @Test
    @DisplayName("停用标记与状态必须一致，二者矛盾的写入被 CHECK 约束拒绝")
    void 停用标记与状态同步() {
        long ownerId = registerOwner();

        // 状态为停用但停用标记为假：与唯一索引条件矛盾，数据库直接拒绝
        assertThatThrownBy(() -> insertRow(ownerId, nextId(), OWNER_CODE, BARCODE, "DISABLED", false))
            .isInstanceOf(DataAccessException.class);
        // 状态为启用却带停用标记：同上
        assertThatThrownBy(() ->
            insertRow(ownerId, nextId(), OWNER_CODE, BARCODE, "ACTIVE", true))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("归属货主创建后不可变更：绕过应用层直接改归属被触发器拦住")
    void 归属货主不可变更() {
        long ownerA = registerOwner();
        long ownerB = registerOwner();
        long id = nextId();
        insertRow(ownerA, id, OWNER_CODE, BARCODE, "DRAFT", false);

        assertThatThrownBy(() -> jdbc.update(
            "update hub_sku_archive set owner_id = ? where id = ?", ownerB, id))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("归属货主");

        assertThat(jdbc.queryForObject("select owner_id from hub_sku_archive where id = ?",
            Long.class, id)).isEqualTo(ownerA);
    }

    @Test
    @DisplayName("系统编号创建后不可变更")
    void 系统编号不可变更() {
        long ownerId = registerOwner();
        long id = nextId();
        insertRow(ownerId, id, OWNER_CODE, BARCODE, "DRAFT", false);

        assertThatThrownBy(() -> jdbc.update(
            "update hub_sku_archive set archive_no = ? where id = ?", "SKU-CHANGED", id))
            .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("停用后档案行保留、版本递增，历史仍可按系统编号追溯（不物理删除）")
    void 停用保留数据() {
        long ownerId = registerOwner();
        SkuArchiveCommandService.ArchiveCreatedView created =
            commandService.createDraft(ownerId, fields(OWNER_CODE, BARCODE, "矿泉水"), 0L);

        SkuArchive draft = archiveRepository.findByArchiveNo(created.archiveId()).orElseThrow();
        assertThat(draft.status()).isEqualTo(SkuArchiveStatus.DRAFT);
        assertThat(draft.version()).isZero();

        commandService.submit(created.archiveId(), ownerId, draft.version(), 0L);
        SkuArchive afterSubmit = archiveRepository.findByArchiveNo(created.archiveId()).orElseThrow();
        assertThat(afterSubmit.status()).isEqualTo(SkuArchiveStatus.PENDING);

        reviewService.review(created.archiveId(), SkuArchiveAction.APPROVE, null, 0L);
        SkuArchive active = archiveRepository.findByArchiveNo(created.archiveId()).orElseThrow();
        assertThat(active.status()).isEqualTo(SkuArchiveStatus.ACTIVE);

        commandService.disable(created.archiveId(), ownerId, active.version(), "货主停售", 0L);

        SkuArchive disabled = archiveRepository.findByArchiveNo(created.archiveId()).orElseThrow();
        assertThat(disabled.status()).isEqualTo(SkuArchiveStatus.DISABLED);
        assertThat(disabled.disabled()).isTrue();
        assertThat(disabled.version()).isGreaterThan(active.version());
        // 行还在：按系统编号可继续追溯历史单据与流水
        assertThat(jdbc.queryForObject("select count(*) from hub_sku_archive where owner_id = ?",
            Integer.class, ownerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("档案仓储不提供物理删除入口，业务数据只能靠状态停用")
    void 仓储没有删除入口() {
        List<String> forbidden = Arrays.stream(SkuArchiveRepository.class.getDeclaredMethods())
            .map(Method::getName)
            .filter(name -> name.toLowerCase().contains("delete")
                || name.toLowerCase().contains("remove"))
            .toList();

        assertThat(forbidden).as("档案仓储不得出现物理删除方法（INV-18）").isEmpty();
    }

    private long countByOwner(long ownerId) {
        return jdbc.queryForObject("select count(*) from hub_sku_archive where owner_id = ?",
            Long.class, ownerId);
    }

    /** 直接插行：绕过应用层，验证数据库自己的约束与索引条件。 */
    private void insertRow(long ownerId, long id, String ownerCode, String barcode, String status,
                           boolean disabledFlag) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
            insert into hub_sku_archive (id, owner_id, archive_no, owner_code, name, barcode,
                base_unit, storage_condition, status, disabled_flag, version, data_timestamp,
                created_at, created_by, updated_at, updated_by)
            values (?, ?, ?, ?, ?, ?, '瓶', '常温', ?, ?, 0, ?, ?, 0, ?, 0)
            """, id, ownerId, "SKU" + id, ownerCode, "测试档案" + id, barcode, status,
            disabledFlag, now, now, now);
    }
}
