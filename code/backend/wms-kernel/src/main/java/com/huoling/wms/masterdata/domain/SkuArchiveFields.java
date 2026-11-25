package com.huoling.wms.masterdata.domain;

import java.math.BigDecimal;

/**
 * 商品档案的可填字段（依据 `26` 3.2 的字段分层：货主必填五项加附属字段，系统生成字段不在此）。
 *
 * <p>字段校验只有这里一套：长度、格式、包装换算率联动都由 {@link #requireComplete()} 判定，
 * Hub 的创建与提交、Site 的变更申请校验都调用它，不各写一套。
 *
 * <p>数量与重量一律 {@link BigDecimal}，禁止 {@code float} / {@code double}（INV 与 01 数量口径）。
 */
public record SkuArchiveFields(
        String name,
        String ownerCode,
        String barcode,
        String baseUnit,
        String storageCondition,
        String category,
        String specification,
        String brand,
        String barcodeType,
        String packageSpec,
        BigDecimal packageConversionRate,
        BigDecimal netWeight,
        BigDecimal grossWeight,
        BigDecimal volume,
        BigDecimal length,
        BigDecimal width,
        BigDecimal height,
        boolean highValue,
        boolean hazardous,
        boolean fragile,
        boolean batchManaged,
        boolean serialManaged,
        Integer shelfLifeDays,
        String imageUrl,
        String remark) {

    private static final int NAME_MAX = 128;
    private static final int OWNER_CODE_MAX = 64;
    private static final int BARCODE_MAX = 64;
    private static final int BASE_UNIT_MAX = 16;
    private static final int STORAGE_CONDITION_MAX = 32;
    private static final int CATEGORY_MAX = 64;
    private static final int SPECIFICATION_MAX = 128;
    private static final int BRAND_MAX = 64;
    private static final int BARCODE_TYPE_MAX = 32;
    private static final int PACKAGE_SPEC_MAX = 64;
    private static final int IMAGE_URL_MAX = 512;
    private static final int REMARK_MAX = 512;

    /**
     * 必填与形态校验：货主系统编码、名称、条码、基本单位、保管条件缺一即拒绝（`26` 3.2）。
     *
     * <p>契约 API-001 的请求体带齐这五项，草稿态也要求齐备；判重是另一件事，草稿态不做（`26` 3.3）。
     */
    public void requireComplete() {
        requireText(name, "name", NAME_MAX);
        requireText(ownerCode, "ownerCode", OWNER_CODE_MAX);
        requireText(barcode, "barcode", BARCODE_MAX);
        requireText(baseUnit, "baseUnit", BASE_UNIT_MAX);
        requireText(storageCondition, "storageCondition", STORAGE_CONDITION_MAX);

        optionalText(category, "category", CATEGORY_MAX);
        optionalText(specification, "specification", SPECIFICATION_MAX);
        optionalText(brand, "brand", BRAND_MAX);
        optionalText(barcodeType, "barcodeType", BARCODE_TYPE_MAX);
        optionalText(packageSpec, "packageSpec", PACKAGE_SPEC_MAX);
        optionalText(imageUrl, "imageUrl", IMAGE_URL_MAX);
        optionalText(remark, "remark", REMARK_MAX);

        requireNonNegative(netWeight, "netWeight");
        requireNonNegative(grossWeight, "grossWeight");
        requireNonNegative(volume, "volume");
        requireNonNegative(length, "length");
        requireNonNegative(width, "width");
        requireNonNegative(height, "height");

        if (shelfLifeDays != null && shelfLifeDays <= 0) {
            throw invalid("字段 shelfLifeDays 必须为正整数");
        }
        // `26` 3.2：包装换算率在有包装规格时必填，库位占用核算要用（见 04）。
        boolean hasPackageSpec = packageSpec != null && !packageSpec.isBlank();
        if (hasPackageSpec) {
            if (packageConversionRate == null) {
                throw invalid("字段 packageConversionRate 在有包装规格时必填");
            }
            if (packageConversionRate.signum() <= 0) {
                throw invalid("字段 packageConversionRate 必须大于零");
            }
        } else if (packageConversionRate != null && packageConversionRate.signum() <= 0) {
            throw invalid("字段 packageConversionRate 必须大于零");
        }
    }

    /** 变更申请的目标值改写：只改一个字段，其余字段原样保留。 */
    public SkuArchiveFields withChange(SkuChangeableField field, String targetValue) {
        if (field == null) {
            throw invalid("缺少变更字段");
        }
        if (targetValue == null || targetValue.isBlank()) {
            throw invalid("缺少变更目标值");
        }
        return switch (field) {
            case NAME -> withName(targetValue);
            case BARCODE -> withBarcode(targetValue);
            case STORAGE_CONDITION -> withStorageCondition(targetValue);
            case CATEGORY -> withCategory(targetValue);
            case SPECIFICATION -> withSpecification(targetValue);
            case BRAND -> withBrand(targetValue);
            case PACKAGE_SPEC -> withPackageSpec(targetValue);
            case PACKAGE_CONVERSION_RATE -> withPackageConversionRate(number(targetValue, field));
            case NET_WEIGHT -> withNetWeight(number(targetValue, field));
            case GROSS_WEIGHT -> withGrossWeight(number(targetValue, field));
            case VOLUME -> withVolume(number(targetValue, field));
            case REMARK -> withRemark(targetValue);
        };
    }

    private SkuArchiveFields withName(String value) {
        return new SkuArchiveFields(value, ownerCode, barcode, baseUnit, storageCondition, category,
            specification, brand, barcodeType, packageSpec, packageConversionRate, netWeight,
            grossWeight, volume, length, width, height, highValue, hazardous, fragile,
            batchManaged, serialManaged, shelfLifeDays, imageUrl, remark);
    }

    private SkuArchiveFields withBarcode(String value) {
        return new SkuArchiveFields(name, ownerCode, value, baseUnit, storageCondition, category,
            specification, brand, barcodeType, packageSpec, packageConversionRate, netWeight,
            grossWeight, volume, length, width, height, highValue, hazardous, fragile,
            batchManaged, serialManaged, shelfLifeDays, imageUrl, remark);
    }

    private SkuArchiveFields withStorageCondition(String value) {
        return new SkuArchiveFields(name, ownerCode, barcode, baseUnit, value, category,
            specification, brand, barcodeType, packageSpec, packageConversionRate, netWeight,
            grossWeight, volume, length, width, height, highValue, hazardous, fragile,
            batchManaged, serialManaged, shelfLifeDays, imageUrl, remark);
    }

    private SkuArchiveFields withCategory(String value) {
        return new SkuArchiveFields(name, ownerCode, barcode, baseUnit, storageCondition, value,
            specification, brand, barcodeType, packageSpec, packageConversionRate, netWeight,
            grossWeight, volume, length, width, height, highValue, hazardous, fragile,
            batchManaged, serialManaged, shelfLifeDays, imageUrl, remark);
    }

    private SkuArchiveFields withSpecification(String value) {
        return new SkuArchiveFields(name, ownerCode, barcode, baseUnit, storageCondition, category,
            value, brand, barcodeType, packageSpec, packageConversionRate, netWeight,
            grossWeight, volume, length, width, height, highValue, hazardous, fragile,
            batchManaged, serialManaged, shelfLifeDays, imageUrl, remark);
    }

    private SkuArchiveFields withBrand(String value) {
        return new SkuArchiveFields(name, ownerCode, barcode, baseUnit, storageCondition, category,
            specification, value, barcodeType, packageSpec, packageConversionRate, netWeight,
            grossWeight, volume, length, width, height, highValue, hazardous, fragile,
            batchManaged, serialManaged, shelfLifeDays, imageUrl, remark);
    }

    private SkuArchiveFields withPackageSpec(String value) {
        return new SkuArchiveFields(name, ownerCode, barcode, baseUnit, storageCondition, category,
            specification, brand, barcodeType, value, packageConversionRate, netWeight,
            grossWeight, volume, length, width, height, highValue, hazardous, fragile,
            batchManaged, serialManaged, shelfLifeDays, imageUrl, remark);
    }

    private SkuArchiveFields withPackageConversionRate(BigDecimal value) {
        return new SkuArchiveFields(name, ownerCode, barcode, baseUnit, storageCondition, category,
            specification, brand, barcodeType, packageSpec, value, netWeight,
            grossWeight, volume, length, width, height, highValue, hazardous, fragile,
            batchManaged, serialManaged, shelfLifeDays, imageUrl, remark);
    }

    private SkuArchiveFields withNetWeight(BigDecimal value) {
        return new SkuArchiveFields(name, ownerCode, barcode, baseUnit, storageCondition, category,
            specification, brand, barcodeType, packageSpec, packageConversionRate, value,
            grossWeight, volume, length, width, height, highValue, hazardous, fragile,
            batchManaged, serialManaged, shelfLifeDays, imageUrl, remark);
    }

    private SkuArchiveFields withGrossWeight(BigDecimal value) {
        return new SkuArchiveFields(name, ownerCode, barcode, baseUnit, storageCondition, category,
            specification, brand, barcodeType, packageSpec, packageConversionRate, netWeight,
            value, volume, length, width, height, highValue, hazardous, fragile,
            batchManaged, serialManaged, shelfLifeDays, imageUrl, remark);
    }

    private SkuArchiveFields withVolume(BigDecimal value) {
        return new SkuArchiveFields(name, ownerCode, barcode, baseUnit, storageCondition, category,
            specification, brand, barcodeType, packageSpec, packageConversionRate, netWeight,
            grossWeight, value, length, width, height, highValue, hazardous, fragile,
            batchManaged, serialManaged, shelfLifeDays, imageUrl, remark);
    }

    private SkuArchiveFields withRemark(String value) {
        return new SkuArchiveFields(name, ownerCode, barcode, baseUnit, storageCondition, category,
            specification, brand, barcodeType, packageSpec, packageConversionRate, netWeight,
            grossWeight, volume, length, width, height, highValue, hazardous, fragile,
            batchManaged, serialManaged, shelfLifeDays, imageUrl, value);
    }

    private BigDecimal number(String raw, SkuChangeableField field) {
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw invalid("字段 " + field + " 的目标值必须是定点数");
        }
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid("字段 " + field + " 必填");
        }
        if (value.length() > maxLength) {
            throw invalid("字段 " + field + " 长度上限 " + maxLength);
        }
    }

    private static void optionalText(String value, String field, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw invalid("字段 " + field + " 长度上限 " + maxLength);
        }
    }

    private static void requireNonNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw invalid("字段 " + field + " 不得为负数");
        }
    }

    private static SkuRuleViolation invalid(String message) {
        return new SkuRuleViolation(SkuRuleCodes.PARAM_INVALID, message);
    }
}
