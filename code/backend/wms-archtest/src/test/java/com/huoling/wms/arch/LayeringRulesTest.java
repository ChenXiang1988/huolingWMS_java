package com.huoling.wms.arch;

import com.huoling.wms.common.domain.AggregateRoot;
import com.huoling.wms.common.domain.ValueObject;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.conditions.ArchConditions;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 十四条红线的构建期落点：03 的依赖规则与 14 的 AR-01 至 AR-09。
 * AR-10（仓型比较只在 strategy 包）无法用包依赖表达，由 CI 脚本扫描，见 scripts/check-warehouse-type.sh。
 */
@AnalyzeClasses(packages = "com.huoling.wms", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringRulesTest {

    /** AR-01 分层只能单向：domain 不得依赖 application 与 infrastructure。 */
    @ArchTest
    static final ArchRule 分层只能单向 =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..application..", "..infrastructure..");

    /** AR-02 领域层不依赖框架：实体上出现框架注解即失败。 */
    @ArchTest
    static final ArchRule 领域层不依赖框架 =
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "org.apache.ibatis..", "jakarta.persistence..",
                "com.baomidou..", "org.redisson..", "org.springframework.amqp..");

    /** AR-02 补充：infrastructure 不得反向依赖 application。 */
    @ArchTest
    static final ArchRule 基础设施层不反向依赖应用层 =
        noClasses()
            .that().resideInAPackage("..infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("..application..");

    /** AR-04 聚合根不暴露 setter。 */
    @ArchTest
    static final ArchRule 聚合根不暴露setter =
        methods()
            .that().arePublic()
            .and().areDeclaredInClassesThat().areAnnotatedWith(AggregateRoot.class)
            .should().notHaveNameMatching("set[A-Z].*");

    /** AR-05 聚合根必须有行为，不能只是数据载体。 */
    @ArchTest
    static final ArchRule 聚合根必须有行为 =
        classes()
            .that().areAnnotatedWith(AggregateRoot.class)
            .should(至少有一个业务方法());

    /** AR-06 状态字段必须私有且为枚举，禁止字符串状态与外部直写。 */
    @ArchTest
    static final ArchRule 状态字段必须私有且为枚举 =
        fields()
            .that().haveNameMatching(".*[Ss]tatus")
            .and().areDeclaredInClassesThat().areAnnotatedWith(AggregateRoot.class)
            .should().bePrivate()
            .andShould(类型为枚举());

    /** AR-07 值对象不可变。 */
    @ArchTest
    static final ArchRule 值对象不可变 =
        fields()
            .that().areDeclaredInClassesThat().areAnnotatedWith(ValueObject.class)
            .should().beFinal()
            .andShould().bePrivate();

    /** AR-08 领域事件只能由 domain 发布，应用层不得绕过聚合根直接发事件。 */
    @ArchTest
    static final ArchRule 事件只由领域层发布 =
        noClasses()
            .that().resideOutsideOfPackage("..domain..")
            .should().accessClassesThat().haveSimpleName("DomainEventPublisher");

    /** AR-03 四个可启动单元互不依赖，逐单元断言。 */
    @ArchTest
    static final class 启动单元互不依赖 {

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

    /**
     * AR-09 跨模块只传 DTO：模块 domain 包对其他模块不可见。
     * 十一个业务模块的包名按 11 落定，M01 补 masterdata，其余沿用 14 的示例。
     */
    @ArchTest
    static final class 跨模块不暴露领域实体 {

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

    private static ArchCondition<JavaClass> 至少有一个业务方法() {
        return new ArchCondition<>("除访问器外至少有一个 public 业务方法") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean hasBehavior = item.getMethods().stream()
                    .filter(m -> m.getModifiers().contains(JavaModifier.PUBLIC))
                    .anyMatch(m -> !isAccessor(m) && !isObjectMethod(m));
                if (!hasBehavior) {
                    events.add(SimpleConditionEvent.violated(item,
                        item.getName() + " 只有访问器，没有业务方法"));
                }
            }

            private boolean isAccessor(JavaMethod m) {
                return m.getName().startsWith("get") || m.getName().startsWith("is");
            }

            private boolean isObjectMethod(JavaMethod m) {
                return Set.of("equals", "hashCode", "toString").contains(m.getName());
            }
        };
    }

    private static ArchCondition<JavaField> 类型为枚举() {
        return new ArchCondition<>("类型为枚举") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                boolean isEnum = field.getRawType().isEnum();
                events.add(new SimpleConditionEvent(field, isEnum,
                    field.getFullName() + (isEnum ? " 是枚举" : " 不是枚举，状态必须用枚举表达")));
            }
        };
    }
}
