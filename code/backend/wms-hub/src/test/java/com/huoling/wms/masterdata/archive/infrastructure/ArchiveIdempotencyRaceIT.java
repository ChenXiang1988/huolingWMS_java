package com.huoling.wms.masterdata.archive.infrastructure;

import com.huoling.wms.hub.HubApplication;
import com.huoling.wms.masterdata.archive.domain.ArchiveIdempotencyRepository;
import com.huoling.wms.masterdata.archive.domain.IdempotencyRecord;
import com.huoling.wms.masterdata.archive.support.ArchiveDbTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发同键占位的真实事务语义（design「幂等与异步事件」：占位与受理记录同事务写入）。
 *
 * <p>这条用例把并发窗口压成确定的两步，不用多线程赌时序：
 * 先用另一条连接占住唯一键（模拟并发中先到的一方），再在同一事务里尝试占位并继续读。
 *
 * <p>为什么必须能读到首次记录：契约「重复与处理中」要求落后的请求回到读取分支回放首次受理号。
 * 若占位失败后事务已中止，后续读取会以 SQLSTATE 25P02 报错，请求就落到契约外的 500，
 * 而不是 {@code IDEMPOTENT_IN_PROGRESS}。
 */
@SpringBootTest(classes = HubApplication.class)
class ArchiveIdempotencyRaceIT extends ArchiveDbTestSupport {

    private static final String API = "API-001";

    @Autowired
    private ArchiveIdempotencyRepository idempotencyRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("占位撞唯一键之后同一事务仍能继续判定，读到首次记录而不是事务已中止")
    void 占位冲突后事务仍可用() {
        String scope = "race-" + nextId();
        String key = "race-key-" + nextId();
        registerIdempotencyKey(key);
        String fingerprint = idempotencyService.fingerprint(API, "create", "body-race");

        // 先到的一方已经占位并提交
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
            insert into hub_idempotency (id, scope_key, api_code, idempotency_key, fingerprint,
                status, result_json, error_code, created_at, expires_at)
            values (?, ?, ?, ?, ?, 'IN_PROGRESS', null, null, ?, ?)
            """, nextId(), scope, API, key, fingerprint, now, now.plusHours(24));

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            boolean reserved = idempotencyRepository.reserve(scope, API, key, fingerprint,
                Instant.now(), Instant.now().plus(Duration.ofHours(24)));

            assertThat(reserved).as("唯一键已被占用，后到的一方占位必须返回 false").isFalse();

            // 契约要求的下一步：回到读取分支重新判定，必须能读到首次记录
            IdempotencyRecord record = idempotencyRepository.find(scope, API, key);
            assertThat(record).as("占位冲突之后必须仍能读到首次记录").isNotNull();
        });
    }
}
