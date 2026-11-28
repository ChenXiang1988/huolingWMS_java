package com.huoling.wms.masterdata.owner.application;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.huoling.wms.masterdata.domain.OwnerAction;
import com.huoling.wms.masterdata.domain.OwnerFields;
import com.huoling.wms.masterdata.domain.OwnerStatus;
import com.huoling.wms.masterdata.owner.domain.OwnerArchive;
import com.huoling.wms.masterdata.owner.domain.OwnerArchiveRepository;
import com.huoling.wms.masterdata.owner.domain.OwnerContractException;
import com.huoling.wms.masterdata.owner.domain.OwnerErrorCodes;
import com.huoling.wms.masterdata.owner.domain.OwnerNumberSource;
import com.huoling.wms.masterdata.owner.domain.OwnerScope;
import com.huoling.wms.masterdata.owner.domain.OwnerStatusChangedEvent;
import com.huoling.wms.masterdata.owner.domain.OwnerStatusOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * 货主档案写侧应用服务（API-023 至 API-026 的执行动作）。
 *
 * <p>每个写动作一个本地事务（design「事务边界与并发控制」）：建档一行、改档/停用/启用单行更新。
 * 状态与版本推进全在聚合里，本类只负责取数、判域与落库。
 *
 * <p>并发冲突的区分：聚合先用版本号前置比对（{@code CONFLICT_VERSION}），
 * `update ... where version = ?` 受影响行数为 0 时同样按 {@code CONFLICT_VERSION} 处理——
 * 两笔并发停用只有先到者成功，后到者拿到可重试的版本冲突，不会出现「停用两次、两台留痕」。
 *
 * <p>返回值是契约 1.5 的受理结果摘要（{@code ownerId}、{@code ownerNo}、{@code status}、{@code version}）：
 * 四个动作同一形状，API-027 原样回出，调用方不必按动作拼字段。
 *
 * <p>状态迁移在这里留一条日志（设计章「日志字段」）：{@code ownerId}、{@code ownerNo}、动作、
 * 迁移前后状态、版本与操作人；原因截断 64 字，信用代码与联系电话一律不进日志。
 */
@Service
public class OwnerCommandService {

    private static final Logger log = LoggerFactory.getLogger(OwnerCommandService.class);

    private final OwnerArchiveRepository repository;
    private final OwnerNumberSource numbers;
    private final OwnerStatusOutbox outbox;
    private final ApplicationEventPublisher events;

    public OwnerCommandService(OwnerArchiveRepository repository, OwnerNumberSource numbers,
                               OwnerStatusOutbox outbox, ApplicationEventPublisher events) {
        this.repository = repository;
        this.numbers = numbers;
        this.outbox = outbox;
        this.events = events;
    }

    /** API-023：建档。要求全域数据域（OI-08），编号由序列生成，信用代码重复由唯一索引兜底。 */
    @Transactional
    public OwnerResultView create(OwnerScope scope, OwnerFields fields, long operatorId) {
        requireScope(scope);
        scope.requireAll();
        Instant now = Instant.now();
        // 主键在应用侧生成（11 的口径），与商品档案一致；存量档案沿用原 owner_id，由迁移脚本保证。
        OwnerArchive archive = OwnerArchive.create(IdWorker.getId(), numbers.nextOwnerNo(), fields,
            operatorId, now);
        try {
            repository.insert(archive);
        } catch (DataIntegrityViolationException e) {
            throw duplicateOf(e);
        }
        announceStatusChange(archive);
        logTransition(archive, OwnerAction.CREATE, null, operatorId);
        return OwnerResultView.of(archive);
    }

    /** API-024：整档替换四项。停用态由状态机拒绝（{@code STATE_INVALID}）。 */
    @Transactional
    public OwnerResultView edit(String ownerNo, OwnerScope scope, Long expectedVersion,
                                OwnerFields fields, long operatorId) {
        OwnerArchive archive = require(ownerNo, scope);
        long loadedVersion = archive.version();
        OwnerStatus before = archive.status();
        archive.edit(expectedVersion, fields, Instant.now(), operatorId);
        persist(archive, loadedVersion);
        logTransition(archive, OwnerAction.EDIT, before, operatorId);
        return OwnerResultView.of(archive);
    }

    /** API-025：停用。原因必填，状态与留痕同一次写入；停用不阻断存量单据与任务。 */
    @Transactional
    public OwnerResultView disable(String ownerNo, OwnerScope scope, Long expectedVersion,
                                   String reason, long operatorId) {
        OwnerArchive archive = require(ownerNo, scope);
        long loadedVersion = archive.version();
        OwnerStatus before = archive.status();
        archive.disable(expectedVersion, reason, Instant.now(), operatorId);
        persist(archive, loadedVersion);
        announceStatusChange(archive);
        logTransition(archive, OwnerAction.DISABLE, before, operatorId);
        return OwnerResultView.of(archive);
    }

    /** API-026：重新启用。只能由人发起，无任何系统自动恢复路径（OI-12）。 */
    @Transactional
    public OwnerResultView enable(String ownerNo, OwnerScope scope, Long expectedVersion,
                                  String reason, long operatorId) {
        OwnerArchive archive = require(ownerNo, scope);
        long loadedVersion = archive.version();
        OwnerStatus before = archive.status();
        archive.enable(expectedVersion, reason, Instant.now(), operatorId);
        persist(archive, loadedVersion);
        announceStatusChange(archive);
        logTransition(archive, OwnerAction.ENABLE, before, operatorId);
        return OwnerResultView.of(archive);
    }

    /**
     * 数据域先判后查：空域直接拒绝，绝不回落成全部（OI-08）。
     *
     * <p>仓储实现里还有一层同样的判定：两层都拦，任何一条写路径绕过服务也拒绝。
     */
    private void requireScope(OwnerScope scope) {
        if (scope == null) {
            throw new OwnerContractException(OwnerErrorCodes.FORBIDDEN_OWNER, "缺少货主数据域，拒绝访问");
        }
        scope.requireNotEmpty();
    }

    /** 域外与不存在同一响应：不泄露货主是否存在（契约 1.2「越权表现」）。 */
    private OwnerArchive require(String ownerNo, OwnerScope scope) {
        requireScope(scope);
        return repository.findByOwnerNo(ownerNo, scope)
            .orElseThrow(() -> new OwnerContractException(OwnerErrorCodes.NOT_FOUND_OWNER,
                "货主不存在或不在数据域内"));
    }

    private void persist(OwnerArchive archive, long loadedVersion) {
        try {
            if (!repository.update(archive, loadedVersion)) {
                throw new OwnerContractException(OwnerErrorCodes.CONFLICT_VERSION,
                    "货主档案已被修改，当前版本与提交版本不一致，请刷新后重提");
            }
        } catch (DataIntegrityViolationException e) {
            throw duplicateOf(e);
        }
    }

    /** 唯一索引冲突翻译成契约码：信用代码全局唯一，停用档同样占位（OI-04）。 */
    private OwnerContractException duplicateOf(DataIntegrityViolationException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("credit_code")) {
            return new OwnerContractException(OwnerErrorCodes.RULE_DUPLICATE_CREDIT_CODE,
                "统一社会信用代码已存在档案，含停用档");
        }
        return new OwnerContractException(OwnerErrorCodes.DEPENDENCY_UNAVAILABLE,
            "货主档案写入失败，请稍后重试");
    }

    /**
     * 状态变更的两件事：写本地消息表（同事务）与发进程内事件（提交后由身份模块失效会话）。
     *
     * <p>只在建档、停用、启用三条路径调用；改档不改状态，因而不发（design「触发点」）。
     *
     * <p>进程内事件与本地消息是两条用途不同的通道：前者让「停用后已建会话下一次请求被拒」
     * 在提交后立即生效（监听方按 {@code AFTER_COMMIT} 收），后者保证跨单元至少一次投递。
     * 两条都发，不互相替代。
     */
    private void announceStatusChange(OwnerArchive archive) {
        OwnerStatusChangedEvent event = OwnerStatusChangedEvent.of(archive);
        outbox.append(event);
        events.publishEvent(event);
    }

    /** 状态迁移日志：字段取设计章「日志字段」，原因截断，敏感字段不记。 */
    private void logTransition(OwnerArchive archive, OwnerAction action, OwnerStatus before,
                               long operatorId) {
        log.info("货主档案写入 ownerId={} ownerNo={} action={} {} -> {} version={} operatorId={} reason={}",
            archive.id(), archive.ownerNo(), action,
            before == null ? "-" : before.name(), archive.status().name(), archive.version(),
            operatorId, OwnerMetrics.truncate(archive.lastStatusChange().reason()));
    }

    /**
     * 受理结果摘要（契约 API-027 的 {@code result}）：四个动作同一形状。
     *
     * <p>{@code ownerId} 是十进制字符串：契约里标识一律用字符串，避免大整数在调用方丢精度。
     */
    public record OwnerResultView(String ownerId, String ownerNo, OwnerStatus status, long version) {

        static OwnerResultView of(OwnerArchive archive) {
            return new OwnerResultView(String.valueOf(archive.id()), archive.ownerNo(),
                archive.status(), archive.version());
        }
    }
}
