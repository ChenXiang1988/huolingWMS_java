package com.huoling.wms.masterdata.acceptance.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huoling.wms.masterdata.acceptance.domain.Acceptance;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceErrorCodes;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceException;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceQuery;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceRepository;
import com.huoling.wms.masterdata.acceptance.domain.AcceptanceStatus;
import com.huoling.wms.masterdata.acceptance.domain.IdempotencyDecision;
import com.huoling.wms.masterdata.domain.RuleViolation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * 写接口受理（契约「写接口统一响应」、design「幂等与异步受理」）。
 *
 * <p>本次由 {@code masterdata.archive} 上移到 {@code masterdata.acceptance}：商品档案与货主档案共用一份机制，
 * 两个业务各写自己的 {@link AcceptanceCommand} 与业务动作，机制不变。
 *
 * <p>一次受理分两步：
 * <ol>
 *   <li>同事务写幂等记录与受理记录（{@code PROCESSING}），立刻拿到 {@code acceptNo} 与受理时状态快照；</li>
 *   <li>事务提交后异步执行业务，把受理记录推进到 {@code SUCCEEDED}（带结果摘要）或 {@code FAILED}（带错误码）。</li>
 * </ol>
 * 写接口响应只含 {@code acceptNo} 与 {@code status}，结果一律由受理查询接口返回，不在写接口上伪造成功。
 *
 * <p>重复提交只产生一条受理记录：同键同指纹回放首次受理号（无论首次是处理中、成功还是失败），
 * 同键异体返回 {@code IDEMPOTENT_KEY_CONFLICT}；占位与受理记录之间仍有并发窗口，
 * 这一段按契约返回可重试的 {@code IDEMPOTENT_IN_PROGRESS} 并带 {@code retryAfterMs}。
 *
 * <p>Bean 名显式给出：身份模块已有一个同简单名的 {@code identity.application.AcceptanceService}
 * （账号受理），两者默认 bean 名都是 {@code acceptanceService}，会让应用上下文启动失败。
 * 按类型注入仍然唯一，这里只把名字错开，不改任何行为。
 */
@Service("hubAcceptanceService")
public class AcceptanceService {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceService.class);

    /** 运营端受理作用域（契约「幂等约定」：运营端按端加接口加键）。 */
    public static final String SCOPE_HUB = "HUB";
    /** 单仓端受理作用域前缀，后接本仓标识。 */
    public static final String SCOPE_SITE_PREFIX = "SITE:";

    private static final int MAX_ATTEMPTS = 3;

    /** 线程池拒绝时给调用方的说明：与接受理记录里的错误说明同一句，避免两处说法不一致。 */
    private static final String POOL_REJECTED_MESSAGE = "受理线程池已满，请稍后按同一幂等键重试";
    /** 线程池拒绝时建议的重试间隔（毫秒）。 */
    private static final long POOL_REJECTED_RETRY_AFTER_MS = 1000L;

    private final AcceptanceRepository acceptances;
    private final IdempotencyService idempotency;
    private final AcceptanceNumbers numbers;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ThreadPoolTaskExecutor acceptanceExecutor;

    public AcceptanceService(AcceptanceRepository acceptances,
                             IdempotencyService idempotency,
                             AcceptanceNumbers numbers,
                             ObjectMapper objectMapper,
                             TransactionTemplate transactionTemplate,
                             ThreadPoolTaskExecutor acceptanceExecutor) {
        this.acceptances = acceptances;
        this.idempotency = idempotency;
        this.numbers = numbers;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.acceptanceExecutor = acceptanceExecutor;
    }

    /**
     * 受理一次写请求。
     *
     * @param command 受理要素：作用域、接口、幂等键、请求指纹来源、受理时状态快照与目标货主
     * @param action  事务提交后异步执行的业务动作，返回值进受理结果摘要
     */
    public Ticket submit(AcceptanceCommand command, Supplier<Object> action) {
        Submission submission = submitWithReplay(command, action);
        return new Ticket(submission.acceptNo(), submission.status());
    }

    /**
     * 与 {@link #submit(AcceptanceCommand, Supplier)} 同一套受理，但把「本次是否重复请求」一并回出。
     *
     * <p>货主档案契约 1.6 的受理票据带 {@code replayed}（首次 {@code false}、同键重复 {@code true}），
     * 商品档案票据没有该字段：机制只多回一个布尔量，由各业务决定是否放进响应，
     * 不在机制里按业务分叉。
     */
    public Submission submitWithReplay(AcceptanceCommand command, Supplier<Object> action) {
        String key = idempotency.requireKey(command.idempotencyKey());
        String fingerprint = idempotency.fingerprint(command.apiCode(), command.requestTarget(),
            command.requestBody());

        Reservation reservation = transactionTemplate.execute(
            status -> reserve(command, key, fingerprint));
        if (reservation == null) {
            throw new AcceptanceException(AcceptanceErrorCodes.INTERNAL_ERROR,
                "受理失败，请稍后重试");
        }
        if (reservation.fresh()) {
            dispatch(command, key, reservation.acceptNo(), action);
        }
        return new Submission(reservation.acceptNo(), reservation.statusSnapshot(),
            !reservation.fresh());
    }

    /**
     * 把执行交给受理线程池。
     *
     * <p>线程池按 {@code AbortPolicy} 拒绝时，受理记录必须落成 {@code FAILED} 并给出可重试错误：
     * 若只抛异常不落库，受理单会永久停在 {@code PROCESSING}，前端轮询到 60 秒超时也拿不到结论。
     * 这里先落失败再抛可重试的 {@code DEPENDENCY_UNAVAILABLE}（503，带重试间隔），
     * 与契约错误码表里「依赖不可用、可重试」同一档，不新增契约外错误码。
     *
     * <p>落库本身失败时不覆盖调用方看到的错误：仍抛可重试错误，落库异常只记日志，
     * 由补偿扫描在下一轮把这条陈旧的处理中受理单收尾。
     */
    private void dispatch(AcceptanceCommand command, String key, String acceptNo,
                          Supplier<Object> action) {
        try {
            acceptanceExecutor.execute(() -> execute(command, key, acceptNo, action));
        } catch (RejectedExecutionException e) {
            log.warn("受理单 {} 被受理线程池拒绝，受理记录落为失败并返回可重试错误", acceptNo, e);
            try {
                fail(command, key, acceptNo, AcceptanceErrorCodes.DEPENDENCY_UNAVAILABLE,
                    POOL_REJECTED_MESSAGE);
            } catch (RuntimeException persistenceFailure) {
                log.warn("受理单 {} 的失败状态未能落库，交由补偿扫描收尾", acceptNo, persistenceFailure);
            }
            throw new AcceptanceException(AcceptanceErrorCodes.DEPENDENCY_UNAVAILABLE,
                POOL_REJECTED_MESSAGE, POOL_REJECTED_RETRY_AFTER_MS);
        }
    }

    /**
     * 受理查询（商品档案 API-015/016、货主档案 API-027）。
     *
     * <p>可见范围由 {@link AcceptanceQuery} 给出：作用域、允许的接口号集合与查不到时的错误码。
     * 接口号集合是跨业务隔离的落点——不在集合内的受理单一律按不存在处理（契约 1.6）。
     *
     * @throws AcceptanceException 受理号不存在或不在可见范围内时抛 {@code query.notFoundCode()}
     */
    public View find(AcceptanceQuery query, String acceptNo) {
        if (acceptNo == null || acceptNo.isBlank()) {
            throw new AcceptanceException(AcceptanceErrorCodes.PARAM_INVALID, "缺少受理号 acceptNo");
        }
        Acceptance acceptance = acceptances.findByAcceptNo(acceptNo, query.apiCodes(),
            query.subjectOwnerIds());
        if (acceptance == null
            || (query.scopeKey() != null && !query.scopeKey().equals(acceptance.scopeKey()))) {
            // 越权与不存在同一响应：不泄露他人受理单是否存在。
            throw new AcceptanceException(query.notFoundCode(), "受理单不存在");
        }
        return new View(acceptance.acceptNo(), acceptance.status().name(),
            read(acceptance.resultJson()), acceptance.errorCode(), acceptance.errorMessage(),
            acceptance.updatedAt());
    }

    /** 写接口响应体：只有受理号与受理时状态快照（契约「写接口统一响应」）。 */
    public record Ticket(String acceptNo, String status) {
    }

    /**
     * 受理结果：受理号、受理时状态快照与本次是否重复请求。
     *
     * <p>{@code replayed} 是「本次请求命中了同一幂等键」的事实，与 {@link Ticket} 分开：
     * 商品档案的票据不含该字段（契约 1.10 已登记两处表达不一致），由各业务自行组装响应。
     */
    public record Submission(String acceptNo, String status, boolean replayed) {
    }

    /** 受理查询响应：受理状态、结果摘要、失败错误码与数据时间戳。 */
    public record View(String acceptNo, String acceptStatus, JsonNode result, String errorCode,
                       String message, Instant dataTimestamp) {
    }

    private Reservation reserve(AcceptanceCommand command, String key, String fingerprint) {
        Instant now = Instant.now();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            IdempotencyDecision.Outcome outcome = idempotency.decide(command.scopeKey(),
                command.apiCode(), key, fingerprint, now);
            switch (outcome) {
                case REPLAY -> {
                    Acceptance existing = requireExisting(command, key);
                    if (existing != null) {
                        return replay(existing);
                    }
                    throw inProgress(command, key);
                }
                case CONFLICT -> throw new AcceptanceException(
                    AcceptanceErrorCodes.IDEMPOTENT_KEY_CONFLICT,
                    "同一 Idempotency-Key 已受理过，本次请求体与首次不同");
                case IN_PROGRESS -> {
                    Acceptance existing = requireExisting(command, key);
                    if (existing != null) {
                        // 已有首次受理记录：重复请求回放首次受理号，不新建受理单（契约「重复与处理中」）。
                        return replay(existing);
                    }
                    throw inProgress(command, key);
                }
                case PROCEED -> {
                    Acceptance existing = acceptances.findByIdempotency(command.scopeKey(),
                        command.apiCode(), key);
                    if (existing != null) {
                        return replay(existing);
                    }
                    if (!idempotency.reserve(command.scopeKey(), command.apiCode(), key, fingerprint, now)) {
                        // 并发占位失败：回到读取分支，由占位成功的一方决定回放或处理中。
                        // 占位语句用 on conflict do nothing，撞唯一键不报错、事务不中止，
                        // 下一轮的读取才能拿到首次记录（否则 PostgreSQL 会以 25P02 拒绝后续语句）。
                        continue;
                    }
                    return insert(command, key, now);
                }
                default -> throw new AcceptanceException(AcceptanceErrorCodes.INTERNAL_ERROR,
                    "幂等判定异常");
            }
        }
        throw inProgress(command, key);
    }

    private Reservation insert(AcceptanceCommand command, String key, Instant now) {
        Acceptance acceptance = Acceptance.processing(numbers.nextAcceptNo(),
            command.apiCode(), command.scopeKey(), command.accountId(), key,
            command.statusSnapshot(), command.subjectOwnerId(), now);
        try {
            acceptances.insert(acceptance);
        } catch (DataIntegrityViolationException e) {
            Acceptance existing = requireExisting(command, key);
            if (existing == null) {
                throw new AcceptanceException(AcceptanceErrorCodes.INTERNAL_ERROR,
                    "受理记录写入失败，请稍后重试");
            }
            return replay(existing);
        }
        return new Reservation(acceptance.acceptNo(), acceptance.statusSnapshot(), true);
    }

    private Reservation replay(Acceptance acceptance) {
        return new Reservation(acceptance.acceptNo(), acceptance.statusSnapshot(), false);
    }

    private Acceptance requireExisting(AcceptanceCommand command, String key) {
        return acceptances.findByIdempotency(command.scopeKey(), command.apiCode(), key);
    }

    private AcceptanceException inProgress(AcceptanceCommand command, String key) {
        return new AcceptanceException(AcceptanceErrorCodes.IDEMPOTENT_IN_PROGRESS,
            "同一 Idempotency-Key 的请求正在受理中，请稍后重试",
            idempotency.retryAfterMs(command.scopeKey(), command.apiCode(), key));
    }

    /**
     * 执行受理的业务动作。
     *
     * <p>这里刻意<b>不</b>再包一层事务：业务动作各自声明事务边界
     * （建档、改档、停用、启用各一个本地事务；批量审核逐条独立事务）。
     * 若在这里开事务，批量的逐条事务会并进同一事务，单条失败即把整批标记为回滚，
     * 与契约 API-009「单条失败不影响其他条目」冲突。
     *
     * <p>失败一律按 {@link RuleViolation} 的码落库：kernel 的规则异常与各业务的应用异常都实现它，
     * 机制不必认识具体业务类型，也不会把业务失败误记成 {@code INTERNAL_ERROR}。
     */
    private void execute(AcceptanceCommand command, String key, String acceptNo,
                         Supplier<Object> action) {
        Instant now = Instant.now();
        try {
            Object result = action.get();
            String json = write(result);
            acceptances.markSucceeded(acceptNo, json, now);
            idempotency.markAccepted(command.scopeKey(), command.apiCode(), key, json);
        } catch (RuleViolation e) {
            fail(command, key, acceptNo, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("受理单 {} 执行失败", acceptNo, e);
            fail(command, key, acceptNo, AcceptanceErrorCodes.INTERNAL_ERROR,
                "执行失败，请带追踪编号报障");
        }
    }

    private void fail(AcceptanceCommand command, String key, String acceptNo, String code,
                      String message) {
        Instant now = Instant.now();
        acceptances.markFailed(acceptNo, code, message, now);
        // 失败也算「首次受理已有结果」：同键重复请求回放同一受理号，结果里带错误码，不产生第二条受理记录。
        idempotency.markAccepted(command.scopeKey(), command.apiCode(), key,
            "{\"acceptNo\":\"" + acceptNo + "\"}");
    }

    private String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AcceptanceException(AcceptanceErrorCodes.INTERNAL_ERROR, "受理结果序列化失败");
        }
    }

    private JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("受理结果解析失败，按空结果返回：{}", e.getMessage());
            return null;
        }
    }

    /** 受理结果：受理号、受理时状态快照，以及本次是否首次受理（首次才需要异步执行）。 */
    private record Reservation(String acceptNo, String statusSnapshot, boolean fresh) {
    }
}
