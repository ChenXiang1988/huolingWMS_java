package com.huoling.wms.masterdata.archive.infrastructure.persistence;

/**
 * 货主选择器的投影行（API-020）：只读，不映射业务表实体。
 *
 * <p>当前有效版本没有货主档案表，选择器从货主端账号派生：{@code owner_id} 为货主标识，
 * 展示名取该货主下账号名的首个值，列别名与响应字段同为 {@code ownerName}
 * （已登记为契约缺口，主数据表落地后替换本投影）。
 */
public class OwnerDirectoryPO {

    private Long ownerId;
    private String ownerName;

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }
}
