package com.huoling.wms.masterdata.owner.application;

import com.huoling.wms.masterdata.domain.OwnerListSort;
import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.masterdata.identity.domain.Account;
import com.huoling.wms.masterdata.identity.domain.AccountRepository;
import com.huoling.wms.masterdata.owner.domain.OwnerArchive;
import com.huoling.wms.masterdata.owner.domain.OwnerArchiveQuery;
import com.huoling.wms.masterdata.owner.domain.OwnerArchiveRepository;
import com.huoling.wms.masterdata.owner.domain.OwnerContractException;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.domain.OwnerPageCursor;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 货主档案查询服务（API-021 列表、API-022 详情）。
 *
 * <p>列表只用键集分页：多取一条判定是否还有下一页，游标锚在（排序键值, 货主编号）上。
 * 排序字段与方向来自契约 {@code sortBy}、{@code sortDir}，游标编码里带着同一组字段与方向，
 * 因此换排序键后旧游标会被拒绝，不会按另一种顺序续翻。
 *
 * <p>只读单事务、不加锁（design「事务边界与并发控制」）；数据域由仓储注入，
 * 域外目标按不存在处理，不泄露存在性。
 */
@Service
public class OwnerQueryService {

    private final OwnerArchiveRepository repository;
    private final AccountRepository accountRepository;

    public OwnerQueryService(OwnerArchiveRepository repository, AccountRepository accountRepository) {
        this.repository = repository;
        this.accountRepository = accountRepository;
    }

    /**
     * API-021：四类筛选（编号精确、名称模糊、信用代码精确、状态）与最后修改时间闭区间，
     * 组合取交集。
     */
    public OwnerPage page(OwnerScope scope, String ownerNo, String ownerName, String creditCode,
                          OwnerStatus status, Instant updatedFrom, Instant updatedTo,
                          OwnerListSort sort, String cursor, int limit) {
        OwnerArchiveQuery query = new OwnerArchiveQuery(scope, blankToNull(ownerNo),
            blankToNull(ownerName), blankToNull(creditCode), status, updatedFrom, updatedTo, sort,
            OwnerPageCursor.decode(cursor, sort), limit + 1);
        List<OwnerArchive> rows = repository.page(query);
        boolean hasMore = rows.size() > limit;
        List<OwnerArchive> items = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore && !items.isEmpty()
            ? cursorOf(items.get(items.size() - 1), query.sort()).encode()
            : null;
        return new OwnerPage(items, nextCursor, hasMore);
    }

    /**
     * API-022：详情。域外与不存在同一响应（{@code NOT_FOUND_OWNER}）。
     *
     * @throws OwnerContractException 目标不存在或不在数据域内时 {@code NOT_FOUND_OWNER}
     */
    public OwnerArchive detail(String ownerNo, OwnerScope scope) {
        return repository.findByOwnerNo(ownerNo, scope)
            .orElseThrow(() -> new OwnerContractException(OwnerErrorCodes.NOT_FOUND_OWNER,
                "货主不存在或不在数据域内"));
    }

    /**
     * 受理单的目标货主标识（API-024 至 API-026 写受理单时用）。
     *
     * <p>取不到返回 {@code null}，不在这里判存在性：契约把「目标不存在」留给受理后判定
     * （{@code NOT_FOUND_OWNER} 经 API-027 返回）。域外目标同样取不到，于是列表域账号
     * 也查不到这张受理单——与「域外按不存在处理」同一口径，不泄露存在性。
     */
    public Long subjectOwnerIdOf(String ownerNo, OwnerScope scope) {
        return repository.findByOwnerNo(ownerNo, scope).map(OwnerArchive::id).orElse(null);
    }

    /**
     * 操作人展示名：契约 1.5 的 {@code lastStatusChange.operator} 是账号名。
     *
     * <p>取不到账号（例如迁移账号 {@code 0}）时回退成标识字符串：留痕仍可读，
     * 不伪造一个人名，也不让详情因为一条历史账号缺失而整页失败。
     */
    public String operatorName(long accountId) {
        return accountRepository.findById(accountId)
            .map(Account::accountName)
            .orElseGet(() -> String.valueOf(accountId));
    }

    /** 下一页锚点：取本页最后一条在当前排序字段上的值，与仓储层 ORDER BY 同一列。 */
    private OwnerPageCursor cursorOf(OwnerArchive archive, OwnerListSort sort) {
        return switch (sort.key()) {
            case OWNER_NAME -> OwnerPageCursor.ofText(sort, archive.fields().ownerName(),
                archive.ownerNo());
            case UPDATE_TIME -> OwnerPageCursor.ofTime(sort, archive.updatedAt(), archive.ownerNo());
            case OWNER_NO -> OwnerPageCursor.ofText(sort, archive.ownerNo(), archive.ownerNo());
        };
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 列表结果：条目、下一页游标与是否还有数据；不带 {@code dataTimestamp}（读权威库，无副本）。 */
    public record OwnerPage(List<OwnerArchive> items, String nextCursor, boolean hasMore) {
    }
}
