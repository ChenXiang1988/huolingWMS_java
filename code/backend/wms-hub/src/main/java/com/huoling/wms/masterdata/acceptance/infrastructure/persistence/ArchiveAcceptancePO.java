package com.huoling.wms.masterdata.acceptance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * {@code hub_archive_acceptance} 的行映射；状态与结果列随后更新，其余列只写一次。
 *
 * <p>类名带 {@code Archive} 前缀是为了与身份模块的 {@code AcceptancePO}（映射 {@code wms_acceptance}）
 * 区分：两张表、两套语义，同名类在不同包下会让日志与调试都难以辨认。
 */
@TableName("hub_archive_acceptance")
public class ArchiveAcceptancePO {

    @TableId(type = IdType.INPUT)
    private Long id;
    private String acceptNo;
    private String apiCode;
    private String scopeKey;
    private Long accountId;
    private String idempotencyKey;
    private String statusSnapshot;
    private String acceptStatus;
    private String resultJson;
    private String errorCode;
    private String errorMessage;
    private Long subjectOwnerId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAcceptNo() {
        return acceptNo;
    }

    public void setAcceptNo(String acceptNo) {
        this.acceptNo = acceptNo;
    }

    public String getApiCode() {
        return apiCode;
    }

    public void setApiCode(String apiCode) {
        this.apiCode = apiCode;
    }

    public String getScopeKey() {
        return scopeKey;
    }

    public void setScopeKey(String scopeKey) {
        this.scopeKey = scopeKey;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getStatusSnapshot() {
        return statusSnapshot;
    }

    public void setStatusSnapshot(String statusSnapshot) {
        this.statusSnapshot = statusSnapshot;
    }

    public String getAcceptStatus() {
        return acceptStatus;
    }

    public void setAcceptStatus(String acceptStatus) {
        this.acceptStatus = acceptStatus;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getSubjectOwnerId() {
        return subjectOwnerId;
    }

    public void setSubjectOwnerId(Long subjectOwnerId) {
        this.subjectOwnerId = subjectOwnerId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
