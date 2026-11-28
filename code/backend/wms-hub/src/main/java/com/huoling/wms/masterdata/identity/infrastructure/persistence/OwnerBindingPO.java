package com.huoling.wms.masterdata.identity.infrastructure.persistence;

/**
 * 货主绑定信息的投影行：只读 {@code wms_owner}，不映射货主聚合实体。
 *
 * <p>列别名与 {@code OwnerBindingDirectory.OwnerBinding} 的字段同名，投影层不做二次改名；
 * 也不需要 {@code credit_code}、{@code contact_*} 等字段——身份模块不展示、不校验这些内容。
 */
public class OwnerBindingPO {

    private Long ownerId;
    private String ownerNo;
    private String ownerName;
    private String status;

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerNo() {
        return ownerNo;
    }

    public void setOwnerNo(String ownerNo) {
        this.ownerNo = ownerNo;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
