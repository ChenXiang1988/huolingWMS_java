package com.huoling.wms.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * AR-03 四个可启动单元互不依赖，逐单元断言。
 *
 * <p>本次变更追加 AR-11 的第二条：单仓端只允许引用 kernel 的货主状态与字段类型
 * （{@code OwnerStatus}、{@code OwnerFields}），MUST NOT 引用 Hub 的货主档案包。
 * 理由：货主档案的权威源在 Hub（D12），单仓端只有 {@code site_owner_status} 只读副本；
 * 一旦有人把 Hub 的聚合搬进单仓端做判定，副本与权威源就会长出第二套状态机。
 * 两条规则一正一反：一条挡「引用 Hub 的包」，一条挡「引用 kernel 里别的货主类型」。
 */
@AnalyzeClasses(packages = "com.huoling.wms", importOptions = ImportOption.DoNotIncludeTests.class)
class UnitIsolationRulesTest {

    /** 单仓端允许引用的 kernel 货主类型：只有状态枚举与字段规则。 */
    private static final Set<String> ALLOWED_OWNER_KERNEL_TYPES =
        Set.of("OwnerStatus", "OwnerFields");

    @ArchTest
    static final ArchRule 运营端不依赖其他单元 =
        noClasses().that().resideInAPackage("..wms.hub..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..wms.site..", "..wms.open..", "..wms.billing..");

    @ArchTest
    static final ArchRule 单仓端不依赖其他单元 =
        noClasses().that().resideInAPackage("..wms.site..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..wms.hub..", "..wms.open..", "..wms.billing..");

    @ArchTest
    static final ArchRule 接入层不依赖其他单元 =
        noClasses().that().resideInAPackage("..wms.open..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..wms.hub..", "..wms.site..", "..wms.billing..");

    @ArchTest
    static final ArchRule 计费域不依赖其他单元 =
        noClasses().that().resideInAPackage("..wms.billing..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..wms.hub..", "..wms.site..", "..wms.open..");

    /** AR-11：单仓端不得引用 Hub 的货主档案包（聚合、仓储、应用服务都算）。 */
    @ArchTest
    static final ArchRule 单仓端不引用Hub货主档案 =
        noClasses().that().resideInAPackage("..wms.site..")
            .should().dependOnClassesThat()
            .resideInAPackage("..masterdata.owner..");

    /** AR-11：单仓端对 kernel 货主类型的引用只允许状态与字段两类。 */
    @ArchTest
    static final ArchRule 单仓端只引用Kernel的货主状态与字段 =
        classes().that().resideInAPackage("..wms.site..")
            .should(只引用货主状态与字段());

    private static ArchCondition<JavaClass> 只引用货主状态与字段() {
        return new ArchCondition<>("只引用 kernel 的 OwnerStatus 与 OwnerFields") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getDirectDependenciesFromSelf().forEach(dependency -> {
                    JavaClass target = dependency.getTargetClass();
                    boolean ownerKernelType = "com.huoling.wms.masterdata.domain"
                        .equals(target.getPackageName())
                        && target.getSimpleName().startsWith("Owner");
                    if (ownerKernelType && !ALLOWED_OWNER_KERNEL_TYPES.contains(target.getSimpleName())) {
                        events.add(SimpleConditionEvent.violated(item,
                            item.getName() + " 引用了 kernel 的货主类型 " + target.getSimpleName()
                                + "：单仓端只允许引用 OwnerStatus 与 OwnerFields，"
                                + "其余货主规则由 Hub 判定"));
                    }
                });
            }
        };
    }
}
