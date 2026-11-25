package com.huoling.wms.masterdata.archive.api;

import com.huoling.wms.masterdata.domain.SkuArchiveFields;

import java.math.BigDecimal;

/**
 * 三个写接口请求体的公共字段面（货主创建、货主编辑、运营代建）。
 *
 * <p>请求体在线上是扁平结构，因此每个接口各自声明字段；把「请求体到领域字段」的转换收在
 * 这一个默认方法里，避免三处各写一遍映射导致字段漏填。
 *
 * <p>字段英文名以契约「字段结构与枚举」为准：规格型号在线上的字段名是 {@code spec}，
 * 库内列与 kernel 的字段名仍是 {@code specification}，两套口径只在这一个方法里对接。
 */
public interface ArchiveFieldSource {

    String name();

    String ownerCode();

    String barcode();

    String baseUnit();

    String storageCondition();

    String category();

    /** 契约字段名 {@code spec}（规格型号）。 */
    String spec();

    String brand();

    String barcodeType();

    String packageSpec();

    BigDecimal packageConversionRate();

    BigDecimal netWeight();

    BigDecimal grossWeight();

    BigDecimal volume();

    BigDecimal length();

    BigDecimal width();

    BigDecimal height();

    Boolean highValue();

    Boolean hazardous();

    Boolean fragile();

    Boolean batchManaged();

    Boolean serialManaged();

    Integer shelfLifeDays();

    String imageUrl();

    String remark();

    /** 转领域字段；数量与重量保持 {@link BigDecimal}，不做浮点转换。 */
    default SkuArchiveFields toFields() {
        return new SkuArchiveFields(name(), ownerCode(), barcode(), baseUnit(), storageCondition(),
            category(), spec(), brand(), barcodeType(), packageSpec(),
            packageConversionRate(), netWeight(), grossWeight(), volume(), length(), width(), height(),
            Boolean.TRUE.equals(highValue()), Boolean.TRUE.equals(hazardous()),
            Boolean.TRUE.equals(fragile()), Boolean.TRUE.equals(batchManaged()),
            Boolean.TRUE.equals(serialManaged()), shelfLifeDays(), imageUrl(), remark());
    }
}
