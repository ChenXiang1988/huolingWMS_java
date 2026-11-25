package com.huoling.wms.masterdata.archive.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.masterdata.archive.domain.ArchiveErrorCodes;
import com.huoling.wms.masterdata.archive.domain.ArchiveEventPayload;
import com.huoling.wms.masterdata.archive.domain.ArchiveException;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxMessage;
import com.huoling.wms.masterdata.archive.domain.ArchiveOutboxRepository;
import com.huoling.wms.masterdata.archive.domain.ChangeRequest;
import com.huoling.wms.masterdata.archive.domain.ChangeRequestEventPayload;
import com.huoling.wms.masterdata.archive.domain.ChangeRequestQuery;
import com.huoling.wms.masterdata.archive.domain.ChangeRequestRepository;
import com.huoling.wms.masterdata.archive.domain.SkuArchive;
import com.huoling.wms.masterdata.archive.domain.SkuArchiveRepository;
import com.huoling.wms.masterdata.archive.domain.SkuPageCursor;
import com.huoling.wms.masterdata.domain.SkuChangeRequestRule;
import com.huoling.wms.masterdata.domain.SkuChangeRequestStatus;
import com.huoling.wms.masterdata.domain.SkuChangeableField;
import com.huoling.wms.masterdata.domain.SkuListSort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 单仓变更申请应用服务（API-011 发起、API-012 运营查询、API-013 运营审核、API-014 单仓查询）。
 *
 * <p>单仓端只能发起，不能改写档案；审核通过后由本服务写回权威档案、递增版本号并写下行消息，
 * 三件事在同一本地事务内完成（specs product-archive-review、22 T-06）。
 *
 * <p>审核结果不论通过还是驳回都写一条下行消息：单仓的申请跟踪要看到驳回原因与结论，
 * 只靠档案变更事件无法表达「申请被驳回」。
 */
@Service
public class ChangeRequestService {

    private final ChangeRequestRepository changeRequestRepository;
    private final SkuArchiveRepository archiveRepository;
    private final ArchiveOutboxRepository outboxRepository;
    private final ArchiveNumbers numbers;
    private final ObjectMapper objectMapper;

    public ChangeRequestService(ChangeRequestRepository changeRequestRepository,
                                SkuArchiveRepository archiveRepository,
                                ArchiveOutboxRepository outboxRepository,
                                ArchiveNumbers numbers,
                                ObjectMapper objectMapper) {
        this.changeRequestRepository = changeRequestRepository;
        this.archiveRepository = archiveRepository;
        this.outboxRepository = outboxRepository;
        this.numbers = numbers;
        this.objectMapper = objectMapper;
    }

    /**
     * API-011：单仓发起字段变更申请（Kernel 闭集解析入口）。
     *
     * <p>接口层已按契约的 {@code changeField} 闭集翻译成枚举，这个重载保留给直接传枚举名的调用方。
     */
    @Transactional
    public RequestCreatedView create(String archiveNo, ArchiveAccessGuard.SiteScope scope,
                                     String field, String targetValue, String reason, long operatorId) {
        return create(archiveNo, scope, SkuChangeableField.parse(field), targetValue, reason, operatorId);
    }

    /**
     * API-011：单仓发起字段变更申请。
     *
     * @param scope  单仓端数据域，本仓加合并后的数据域；档案归属不在可见范围内即越权
     * @param field  契约 {@code changeField} 翻译后的字段，闭集由 Kernel 定义
     */
    @Transactional
    public RequestCreatedView create(String archiveNo, ArchiveAccessGuard.SiteScope scope,
                                     SkuChangeableField field, String targetValue, String reason,
                                     long operatorId) {
        SkuArchive archive = archiveRepository.findByArchiveNo(archiveNo)
            .orElseThrow(() -> new ArchiveException(ArchiveErrorCodes.NOT_FOUND_ARCHIVE, "档案不存在"));
        if (scope == null || !scope.dataScope().coversOwner(archive.ownerId())) {
            throw new ArchiveException(ArchiveErrorCodes.FORBIDDEN_SITE,
                "该档案不在本仓可见范围内，拒绝发起变更申请");
        }
        SkuChangeRequestRule.requireArchiveActive(archive.status());
        SkuChangeRequestRule.requireNoPending(changeRequestRepository.hasPending(archive.id()));
        // 目标值先按落库口径校验一次，避免申请通过后在写回时才发现非法。
        archive.fields().withChange(field, targetValue).requireComplete();

        Instant now = Instant.now();
        ChangeRequest request = ChangeRequest.create(null, numbers.nextRequestNo(), archive.id(),
            archive.archiveNo(), archive.ownerId(), scope.warehouseId(), field,
            targetValue, reason, now);
        try {
            changeRequestRepository.insert(request, operatorId);
        } catch (DataIntegrityViolationException e) {
            // 部分唯一索引兜底：并发发起只成功一条，其余按状态冲突返回。
            throw new ArchiveException(ArchiveErrorCodes.STATE_INVALID,
                "该档案已有待审核的变更申请，需先完成审核");
        }
        return new RequestCreatedView(request.requestNo(), request.status());
    }

    /** API-012：运营查询变更申请（全量数据域，可按货主、本仓、档案过滤，按申请字段排序）。 */
    public ChangeRequestPage pageForHub(Long ownerIdFilter, Long siteIdFilter,
                                        SkuChangeRequestStatus status, String requestNo,
                                        String archiveId, SkuListSort sort, String cursor, int limit) {
        ChangeRequestQuery query = new ChangeRequestQuery(ownerIdFilter, siteIdFilter, status,
            requestNo, archiveId, sort, SkuPageCursor.decode(cursor, sort), limit + 1);
        return page(query, limit);
    }

    /** API-014：单仓查询本仓申请（本仓域强制注入，按申请字段排序）。 */
    public ChangeRequestPage pageForSite(Long siteId, SkuChangeRequestStatus status,
                                         String requestNo, String archiveId, SkuListSort sort,
                                         String cursor, int limit) {
        ChangeRequestQuery query = ChangeRequestQuery.forSite(siteId, status, requestNo, archiveId,
            sort, SkuPageCursor.decode(cursor, sort), limit + 1);
        return page(query, limit);
    }

    /** API-013：运营审核变更申请，通过即写回权威档案并产生下行事件。 */
    @Transactional
    public RequestReviewedView review(String requestNo, boolean approve, String reason, long operatorId) {
        ChangeRequest request = changeRequestRepository.findByRequestNo(requestNo)
            .orElseThrow(() -> new ArchiveException(ArchiveErrorCodes.NOT_FOUND_ARCHIVE, "变更申请不存在"));
        SkuChangeRequestRule.requireReviewable(request.status(), approve, reason);

        Instant now = Instant.now();
        Long archiveVersion = null;
        if (approve) {
            SkuArchive archive = archiveRepository.findByArchiveNo(request.archiveNo())
                .orElseThrow(() -> new ArchiveException(ArchiveErrorCodes.NOT_FOUND_ARCHIVE, "档案不存在"));
            long loadedVersion = archive.version();
            archive.applyChange(request.field(), request.targetValue(), now);
            if (!archiveRepository.update(archive, loadedVersion, operatorId)) {
                throw new ArchiveException(ArchiveErrorCodes.CONFLICT_VERSION,
                    "档案已被修改，请刷新后重试");
            }
            archiveVersion = archive.version();
            writeArchiveChanged(archive, request.field(), now);
        }
        long loadedRequestVersion = request.version();
        request.review(approve, reason, archiveVersion, now);
        if (!changeRequestRepository.update(request, loadedRequestVersion, operatorId)) {
            throw new ArchiveException(ArchiveErrorCodes.STATE_INVALID,
                "申请状态已变化，请刷新后重试");
        }
        writeReviewResult(request, now);
        return new RequestReviewedView(request.status(), request.archiveVersion());
    }

    private void writeArchiveChanged(SkuArchive archive, SkuChangeableField field, Instant now) {
        writeOutbox(ArchiveOutboxMessage.TYPE_ARCHIVE_CHANGED, archive.archiveNo(),
            ArchiveOutboxMessage.eventIdOf(archive.archiveNo(), archive.version()), archive.version(),
            archive.ownerId(), ArchiveEventPayload.of(archive, List.of(field.name())), now);
    }

    /**
     * 审核结果下行：带结论与驳回原因。
     *
     * <p>驳回原因必须下行，否则单仓的申请跟踪看不到「为什么被驳回」（契约 API-014 响应）；
     * 通过时不带原因。
     */
    private void writeReviewResult(ChangeRequest request, Instant now) {
        ChangeRequestEventPayload payload = new ChangeRequestEventPayload(request.requestNo(),
            request.archiveNo(), request.status().name(), request.reviewReason(),
            request.archiveVersion(), request.warehouseId(), now);
        writeOutbox(ArchiveOutboxMessage.TYPE_CHANGE_REQUEST_REVIEWED, request.archiveNo(),
            request.requestNo() + "#" + request.status().name() + "#" + request.version(),
            request.archiveVersion() == null ? 0L : request.archiveVersion(), request.ownerId(),
            payload, now);
    }

    private void writeOutbox(String eventType, String aggregateNo, String eventId, long version,
                             Long ownerId, Object payload, Instant now) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new ArchiveException(ArchiveErrorCodes.INTERNAL_ERROR, "下行摘要序列化失败");
        }
        outboxRepository.insert(new ArchiveOutboxMessage(null, eventId, eventType, aggregateNo,
            version, ownerId, json, now));
    }

    private ChangeRequestPage page(ChangeRequestQuery query, int limit) {
        List<ChangeRequest> rows = changeRequestRepository.page(query);
        boolean hasMore = rows.size() > limit;
        List<ChangeRequest> items = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            nextCursor = cursorOf(items.get(items.size() - 1), query.sort()).encode();
        }
        Instant dataTimestamp = items.isEmpty() ? Instant.now() : items.get(0).updatedAt();
        return new ChangeRequestPage(items, nextCursor, hasMore, dataTimestamp);
    }

    /**
     * 下一页锚点：取本页最后一条在当前排序字段上的值。
     *
     * <p>排序字段按申请字段取值（契约字段结构表）：时间取申请创建时间，货主编码取申请归属货主，
     * 名称取申请的变更字段，与仓储层的 ORDER BY 是同一列。
     */
    private SkuPageCursor cursorOf(ChangeRequest request, SkuListSort sort) {
        return switch (sort.key()) {
            case OWNER_CODE -> SkuPageCursor.ofNumber(sort, request.ownerId(), request.id());
            case NAME -> SkuPageCursor.ofText(sort, request.field().name(), request.id());
            case UPDATE_TIME -> SkuPageCursor.ofTime(sort, request.createdAt(), request.id());
        };
    }

    /**
     * API-011 的受理结果摘要：申请编号与申请状态（受理号由受理记录统一给出）。
     *
     * <p>线上字段名取契约「字段结构与枚举」的 {@code result.requestId}（不是内部命名的
     * {@code requestNo}）：受理面板按受理号查到的结果里要能直接拿到申请编号。
     * Java 访问器名保持 {@code requestNo()}，内部调用点与列表视图不受影响。
     */
    public record RequestCreatedView(
            @JsonProperty("requestId") String requestNo,
            SkuChangeRequestStatus status) {
    }

    /** API-013 的受理结果摘要：申请状态与写回后的档案版本号。 */
    public record RequestReviewedView(SkuChangeRequestStatus status, Long archiveVersion) {
    }

    /** 列表结果：条目、下一页游标、是否还有数据与数据时间戳。 */
    public record ChangeRequestPage(List<ChangeRequest> items, String nextCursor, boolean hasMore,
                                    Instant dataTimestamp) {
    }
}
