package com.huoling.wms.masterdata.owner.application;

import com.huoling.wms.masterdata.domain.RuleViolation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 货主档案的指标与日志落点（design「可观测与兼容回滚」，tasks 1.18）。
 *
 * <p>设计章列的六项里，Hub 侧的五项在这里落地（第六项 {@code site_owner_cache_miss_total} 在单仓端，
 * 见 {@code OwnerGateMetrics}）；指标名与设计章逐字一致，便于按名对接看板：
 * <ul>
 *   <li>{@code owner_archive_accept_total{api, result}}：受理按接口与结果计数。
 *       {@code result} 取 {@code accepted}（首次受理）、{@code replayed}（同键重复）、
 *       {@code succeeded}（执行成功）、{@code failed}（执行失败，失败码进日志不进标签，
 *       避免无界标签把时序撑爆）；</li>
 *   <li>{@code owner_archive_write_duration}：写动作耗时，P95 对契约 800 ms 预算；</li>
 *   <li>{@code owner_scope_denied_total{reason}}：数据域拒绝次数（空域、端不符、非全域建档）；</li>
 *   <li>{@code owner_archive_accept_processing}：受理积压（仍在处理的受理单条数），
 *       由 {@link OwnerAcceptanceBacklog} 定时刷新；</li>
 *   <li>{@code owner_status_downlink_lag_seconds}：状态下行延迟，投递成功后按
 *       {@code now - changedAt} 上报（{@code changedAt} 是权威数据的产生时间，不是投递时间）。
 *       由 {@link OwnerStatusDeliveryMetrics} 在投递器回调里接线；</li>
 *   <li>{@code owner_status_dlq_depth}：转进死信的货主状态事件条数，死信转发成功后上报。
 *       口径是本实例累计转入死信的条数（单调递增，供告警与人工重放对账），
 *       不是 RabbitMQ 队列的实时长度——Hub 侧不回读队列，读不到就不假装读到了。</li>
 * </ul>
 *
 * <p>日志字段按设计章：{@code apiCode}、{@code acceptNo}、{@code ownerNo}、{@code action}、
 * {@code version}、状态迁移、原因（截断 64 字）。信用代码与联系电话全文一律不记。
 */
@Component
public class OwnerMetrics {

    private static final Logger log = LoggerFactory.getLogger(OwnerMetrics.class);

    /** 原因在日志里的截断长度（设计章「日志字段」）。 */
    public static final int REASON_LOG_MAX = 64;

    private static final String ACCEPT_TOTAL = "owner_archive_accept_total";
    private static final String WRITE_DURATION = "owner_archive_write_duration";
    private static final String SCOPE_DENIED = "owner_scope_denied_total";
    private static final String ACCEPT_PROCESSING = "owner_archive_accept_processing";
    private static final String DOWNLINK_LAG = "owner_status_downlink_lag_seconds";
    private static final String DLQ_DEPTH = "owner_status_dlq_depth";

    private final MeterRegistry registry;
    private final AtomicReference<Double> downlinkLag = new AtomicReference<>();
    private final AtomicLong acceptanceBacklog = new AtomicLong();
    private final AtomicLong dlqDepth = new AtomicLong();
    private volatile boolean downlinkGaugeRegistered;
    private volatile boolean backlogGaugeRegistered;
    private volatile boolean dlqGaugeRegistered;

    public OwnerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 受理入口：首次受理与同键重复分开计数（{@code replayed} 仍是同一次业务结果）。
     *
     * <p>自带 try/catch 是硬要求：本方法在受理单已经落库、响应还没写出的那一刻调用
     * （{@code HubOwnerArchiveController} 四个写接口皆是），向上抛会把「已受理」变成 500，
     * 调用方重试时幂等键又已占位。观测失败只记一行日志，不改业务结果。
     */
    public void acceptSubmitted(String apiCode, boolean replayed) {
        try {
            Counter.builder(ACCEPT_TOTAL)
                .tag("api", apiCode)
                .tag("result", replayed ? "replayed" : "accepted")
                .register(registry)
                .increment();
        } catch (RuntimeException e) {
            log.warn("货主档案受理计数上报失败 api={} result={}：{}", apiCode,
                replayed ? "replayed" : "accepted", e.getMessage());
        }
    }

    /**
     * 包住一次受理执行：成功与失败都计数并记耗时，失败码原样抛给受理机制。
     *
     * <p>放在调用点而不是受理机制里：受理机制由商品档案与货主档案共用，
     * 指标要按业务分接口统计，机制不该认识货主档案的接口号。
     *
     * <p>计数的异常在 {@link #recordExecution} 里自吞：动作已经成功，受理机制紧接着把受理单
     * 落成 {@code SUCCEEDED}，此时因为指标注册失败把异常抛回受理机制，成功就被记成失败。
     */
    public Object timed(String apiCode, Supplier<Object> action) {
        long start = System.nanoTime();
        try {
            Object result = action.get();
            recordExecution(apiCode, "succeeded", start);
            return result;
        } catch (RuleViolation e) {
            // 业务失败不吞：受理机制据错误码把受理单落成 FAILED，失败码进日志便于分派。
            log.info("货主档案受理执行失败 apiCode={} code={} message={}", apiCode, e.code(),
                e.getMessage());
            recordExecution(apiCode, "failed", start);
            throw e;
        }
    }

    /**
     * 数据域拒绝：按原因分类计数，同时留一条可定位的日志。
     *
     * <p>计数与日志分开兜底：本方法在下发 403 之前调用，指标抛错会把预期的
     * {@code FORBIDDEN_OWNER} 顶成一个 500，越权拒绝变成系统错误——观测失败不能改拒绝结果。
     * 日志是这条拒绝唯一的业务留痕，无论计数成败都要写出去。
     */
    public void scopeDenied(String reason, String detail) {
        try {
            registry.counter(SCOPE_DENIED, "reason", reason).increment();
        } catch (RuntimeException e) {
            log.warn("货主档案数据域拒绝计数上报失败 reason={}：{}", reason, e.getMessage());
        }
        log.warn("货主档案数据域拒绝 reason={} detail={}", reason,
            truncate(detail));
    }

    /**
     * 受理积压：仍在处理的货主档案受理单条数（由定时任务刷新）。
     *
     * <p>用 {@link AtomicLong} 持有数值、指标只注册一次：{@code registry.gauge(name, value)}
     * 在指标已存在时直接返回旧的那个，把数字当参数传进去只有第一次生效，
     * 后续刷新会变成永远不动的手写常量。
     */
    public void acceptanceBacklog(long processing) {
        acceptanceBacklog.set(processing);
        if (!backlogGaugeRegistered) {
            synchronized (this) {
                if (!backlogGaugeRegistered) {
                    registry.gauge(ACCEPT_PROCESSING, acceptanceBacklog, AtomicLong::get);
                    backlogGaugeRegistered = true;
                }
            }
        }
    }

    /**
     * 状态下行延迟：投递成功后由 {@link OwnerStatusDeliveryMetrics} 调用（tasks 1.13、1.18）。
     *
     * <p>取「投递完成时间 - 事件 changedAt」，因此它同时覆盖队列积压与投递器退避：
     * 事件在停机期间积压时，这个值会随之变大，而不是只反映最后一次发送的耗时。
     *
     * <p>首次调用才注册指标：没有下行事件时端点上不出现该指标，
     * 不会被误读成「延迟为零」。
     */
    public void statusDownlinkLag(Duration lag) {
        if (lag == null) {
            return;
        }
        double seconds = Math.max(0d, lag.toMillis() / 1000d);
        downlinkLag.set(seconds);
        if (!downlinkGaugeRegistered) {
            synchronized (this) {
                if (!downlinkGaugeRegistered) {
                    registry.gauge(DOWNLINK_LAG, downlinkLag, AtomicReference::get);
                    downlinkGaugeRegistered = true;
                }
            }
        }
    }

    /**
     * 死信深度：一条货主状态事件成功转发进死信队列后加一并上报（tasks 1.18）。
     *
     * <p>口径是「本实例累计转入死信的条数」。Hub 侧不回读 RabbitMQ 队列长度——
     * 那需要额外的管理接口权限与连接，失败风险远大于它带来的精度；累计值同样能回答
     * 「有没有消息在烂掉、从什么时候开始烂」，配合事件标识日志即可人工重放。
     *
     * <p>与下行延迟同理，首次调用才注册：没有死信时端点上不出现该指标，
     * 不会被误读成「死信深度为零」。
     */
    public void deadLetterForwarded() {
        dlqDepth.incrementAndGet();
        if (!dlqGaugeRegistered) {
            synchronized (this) {
                if (!dlqGaugeRegistered) {
                    registry.gauge(DLQ_DEPTH, dlqDepth, AtomicLong::get);
                    dlqGaugeRegistered = true;
                }
            }
        }
    }

    /** 原因截断：超过 64 字只留前缀，避免日志被长原因冲垮。 */
    public static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= REASON_LOG_MAX ? value : value.substring(0, REASON_LOG_MAX) + "…";
    }

    /** 执行结果计数与耗时；观测异常一律自吞，不改变业务结果（成功不翻成失败、失败码不被顶替）。 */
    private void recordExecution(String apiCode, String result, long startNanos) {
        try {
            Counter.builder(ACCEPT_TOTAL)
                .tag("api", apiCode)
                .tag("result", result)
                .register(registry)
                .increment();
            Timer.builder(WRITE_DURATION)
                .tag("api", apiCode)
                .register(registry)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            log.warn("货主档案写动作指标上报失败 api={} result={}：{}", apiCode, result,
                e.getMessage());
        }
    }
}
