package com.huoling.wms.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * AR-09 跨模块只传 DTO：模块 domain 包对其他模块不可见。
 * 十一个模块的包名按 11 落定，M01 补 masterdata，其余沿用 14 的示例。
 */
@AnalyzeClasses(packages = "com.huoling.wms", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryRulesTest {

    @ArchTest
    static final ArchRule 主数据模块不暴露领域实体 = 模块外不得引用("masterdata");

    @ArchTest
    static final ArchRule 库位模块不暴露领域实体 = 模块外不得引用("location");

    @ArchTest
    static final ArchRule 库存模块不暴露领域实体 = 模块外不得引用("stock");

    @ArchTest
    static final ArchRule 任务模块不暴露领域实体 = 模块外不得引用("task");

    @ArchTest
    static final ArchRule 单据模块不暴露领域实体 = 模块外不得引用("order");

    @ArchTest
    static final ArchRule 策略模块不暴露领域实体 = 模块外不得引用("strategy");

    @ArchTest
    static final ArchRule 加工模块不暴露领域实体 = 模块外不得引用("processing");

    @ArchTest
    static final ArchRule 作业事实模块不暴露领域实体 = 模块外不得引用("billingfact");

    @ArchTest
    static final ArchRule 设备模块不暴露领域实体 = 模块外不得引用("device");

    @ArchTest
    static final ArchRule 仓网模块不暴露领域实体 = 模块外不得引用("warehouse");

    @ArchTest
    static final ArchRule 班组模块不暴露领域实体 = 模块外不得引用("team");

    private static ArchRule 模块外不得引用(String module) {
        return noClasses()
            .that().resideOutsideOfPackages("..wms." + module + "..")
            .should().dependOnClassesThat()
            .resideInAPackage("..wms." + module + ".domain..");
    }
}
