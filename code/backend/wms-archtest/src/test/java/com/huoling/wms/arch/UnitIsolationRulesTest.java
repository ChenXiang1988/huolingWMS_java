package com.huoling.wms.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * AR-03 四个可启动单元互不依赖，逐单元断言。
 */
@AnalyzeClasses(packages = "com.huoling.wms", importOptions = ImportOption.DoNotIncludeTests.class)
class UnitIsolationRulesTest {

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
}
