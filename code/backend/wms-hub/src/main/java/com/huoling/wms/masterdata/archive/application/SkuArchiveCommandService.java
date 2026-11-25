package com.huoling.wms.masterdata.archive.application;

import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.SkuArchive;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveRepository;
import com.huoling.wms.masterdata.domain.SkuArchiveDedupRule;
import com.huoling.wms.masterdata.domain.SkuArchiveFields;
import com.huoling.wms.masterdata.domain.SkuArchiveStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * 商品档案写侧应用服务（API-001、API-002、API-003、API-004、API-006）。
 *
 * <p>每个写动作一个本地事务（design「事务边界与并发」）；判重与状态迁移都调 Kernel 规则，
 * 本类只负责取数与落库。数据域由调用方解析后以 {@code ownerId} 传入，仓储层强制注入（INV-06）。
 */
@Service
public class SkuArchiveCommandService {

    private final SkuArchiveRepository archiveRepository;
    private final ArchiveNumbers numbers;

    public SkuArchiveCommandService(SkuArchiveRepository archiveRepository, ArchiveNumbers numbers) {
        this.archiveRepository = archiveRepository;
        this.numbers = numbers;
    }

    /** API-001：货主端创建草稿，归属从登录上下文带入。 */
    @Transactional
    public ArchiveCreatedView createDraft(Long ownerId, SkuArchiveFields fields, long operatorId) {
        return doCreateDraft(ownerId, fields, operatorId);
    }

    /** API-006：运营代建草稿，归属由请求体指定，运营不获得货主身份、也不改变归属。 */
    @Transactional
    public ArchiveCreatedView createDraftByHub(Long ownerId, SkuArchiveFields fields, long operatorId) {
        if (ownerId == null) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_OWNER, "代建必须指定归属货主");
        }
        return doCreateDraft(ownerId, fields, operatorId);
    }

    /** API-003：编辑草稿或驳回态档案，启用态字段变更必须走变更申请或审核流程。 */
    @Transactional
    public ArchiveEditedView edit(String archiveNo, Long ownerId, Long expectedVersion,
                                  SkuArchiveFields fields, long operatorId) {
        SkuArchive archive = requireOwnedArchive(archiveNo, ownerId);
        long loadedVersion = archive.version();
        archive.edit(expectedVersion, fields, Instant.now());
        persist(archive, loadedVersion, operatorId);
        return new ArchiveEditedView(archive.version());
    }

    /** API-002：提交审核，提交前判重（草稿态允许重复，`26` 3.3）。 */
    @Transactional
    public ArchiveSubmittedView submit(String archiveNo, Long ownerId, Long expectedVersion,
                                       long operatorId) {
        SkuArchive archive = requireOwnedArchive(archiveNo, ownerId);
        long loadedVersion = archive.version();
        SkuArchiveDedupRule.requireUnique(archive.id(), archive.fields().ownerCode(),
            archive.fields().barcode(), archiveRepository.findIdentities(archive.ownerId()));
        archive.submit(expectedVersion, Instant.now());
        persist(archive, loadedVersion, operatorId);
        return new ArchiveSubmittedView(archive.status(), archive.version());
    }

    /** API-004：逻辑停用，数据保留、历史单据与库存流水仍可按系统编号追溯（INV-18）。 */
    @Transactional
    public ArchiveDisableView disable(String archiveNo, Long ownerId, Long expectedVersion,
                                      String reason, long operatorId) {
        SkuArchive archive = requireOwnedArchive(archiveNo, ownerId);
        long loadedVersion = archive.version();
        archive.disable(expectedVersion, reason, Instant.now());
        persist(archive, loadedVersion, operatorId);
        return new ArchiveDisableView(archive.status(), archive.version());
    }

    private ArchiveCreatedView doCreateDraft(Long ownerId, SkuArchiveFields fields, long operatorId) {
        Instant now = Instant.now();
        SkuArchive archive = SkuArchive.createDraft(null, ownerId, numbers.nextArchiveNo(), fields, now);
        try {
            archiveRepository.insert(archive, operatorId);
        } catch (DataIntegrityViolationException e) {
            throw duplicateOf(e);
        }
        return new ArchiveCreatedView(archive.archiveNo(), archive.status(), archive.version());
    }

    private SkuArchive requireOwnedArchive(String archiveNo, Long ownerId) {
        return archiveRepository.findByArchiveNoAndOwner(archiveNo, ownerId)
            .orElseThrow(() -> new ArchiveException(ArchiveErrorCodes.NOT_FOUND_ARCHIVE, "档案不存在"));
    }

    private void persist(SkuArchive archive, long loadedVersion, long operatorId) {
        try {
            if (!archiveRepository.update(archive, loadedVersion, operatorId)) {
                throw new ArchiveException(ArchiveErrorCodes.CONFLICT_VERSION,
                    "档案已被修改，当前版本与提交版本不一致，请刷新后重试");
            }
        } catch (DataIntegrityViolationException e) {
            // 判重与落库之间的竞争由部分唯一索引兜底，异常按契约码回给调用方。
            throw duplicateOf(e);
        }
    }

    private ArchiveException duplicateOf(DataIntegrityViolationException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("barcode")) {
            return new ArchiveException(ArchiveErrorCodes.RULE_DUPLICATE_BARCODE, "货主内条码重复");
        }
        if (message.contains("owner_code")) {
            return new ArchiveException(ArchiveErrorCodes.RULE_DUPLICATE_CODE, "货主内编码重复");
        }
        return new ArchiveException(ArchiveErrorCodes.CONFLICT_VERSION, "档案已被修改，请刷新后重试");
    }

    /**
     * API-001、API-006 的受理结果摘要：系统编号、状态与版本号。
     *
     * <p>结果摘要只出现在受理记录与受理查询响应里；写接口响应只回 {@code acceptNo} 与状态快照，
     * 因此这里不再自带受理号（受理号由受理记录统一生成）。
     */
    public record ArchiveCreatedView(String archiveId, SkuArchiveStatus status, long version) {
    }

    /** API-003 的受理结果摘要：更新后的版本号。 */
    public record ArchiveEditedView(long version) {
    }

    /** API-002 的受理结果摘要：状态与版本号。 */
    public record ArchiveSubmittedView(SkuArchiveStatus status, long version) {
    }

    /** API-004 的受理结果摘要：停用后的状态与版本号。 */
    public record ArchiveDisableView(SkuArchiveStatus status, long version) {
    }
}
