package com.huoling.wms.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * AR-09 跨模块只传 DTO：模块 domain 包对其他模块不可见。
 * 模块清单按 14 的 AR-09 逐项落定，共十个：warehouse、location、stock、task、order、
 * strategy、processing、billingfact、device、team。
 * M01 主数据不在此列：03 的 domain 层允许依赖「M01 的值对象」，kernel 的
 * masterdata.domain 只有值对象、枚举与规则类，跨单元引用它们是设计的落点而非违规。
 *
 * <p>AR-11（本次变更新增）：货主档案（{@code masterdata.owner}）与商品档案
 * （{@code masterdata.archive}）是同一个 Hub 单元里的两个业务包，二者 MUST NOT 有编译期依赖
 * ——共用能力（受理、幂等、限流）一律下沉到中立包，任一侧也不许顺手引用另一侧的聚合或仓储。
 * 双向各一条规则：只写一个方向的话，反向依赖照样能编译通过。
 */
@AnalyzeClasses(packages = "com.huoling.wms", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryRulesTest {

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

    /** AR-11 正向：货主档案包不得依赖商品档案包。 */
    @ArchTest
    static final ArchRule 货主档案不依赖商品档案 =
        noClasses()
            .that().resideInAPackage("..masterdata.owner..")
            .should().dependOnClassesThat()
            .resideInAPackage("..masterdata.archive..");

    /** AR-11 反向：商品档案包不得依赖货主档案包。 */
    @ArchTest
    static final ArchRule 商品档案不依赖货主档案 =
        noClasses()
            .that().resideInAPackage("..masterdata.archive..")
            .should().dependOnClassesThat()
            .resideInAPackage("..masterdata.owner..");

    private static ArchRule 模块外不得引用(String module) {
        return noClasses()
            .that().resideOutsideOfPackages("..wms." + module + "..")
            .should().dependOnClassesThat()
            .resideInAPackage("..wms." + module + ".domain..");
    }
}
