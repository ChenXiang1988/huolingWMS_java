package com.huoling.wms.masterdata.archive.infrastructure.persistence;

/**
 * 货主选择器的投影行（API-020）：只读，不映射业务表实体。
 *
 * <p>数据源已由「货主端账号派生」换成权威主数据 {@code wms_owner}（tasks 1.12）：
 * {@code id} 为货主标识，{@code owner_no} 为货主编号，{@code owner_name} 为展示名。
 * 列别名与契约响应字段同名，投影层不做二次改名。
 *
 * <p>投影只读：没有 setter 之外的写方法，也不参与任何写路径。
 */
public class OwnerDirectoryPO {

    private Long ownerId;
    private String ownerNo;
    private String ownerName;

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
}
