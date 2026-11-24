package com.huoling.wms.arch;

import com.huoling.wms.common.domain.AggregateRoot;
import com.huoling.wms.common.domain.ValueObject;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * 分层、聚合根、值对象与事件发布的构建期断言：AR-01、AR-02、AR-04 至 AR-08。
 * AR-03 见 UnitIsolationRulesTest，AR-09 见 ModuleBoundaryRulesTest，
 * AR-10 无法用包依赖表达，由 scripts/check-warehouse-type.sh 扫描。
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
        noMethods()
            .that().arePublic()
            .and().areDeclaredInClassesThat().areAnnotatedWith(AggregateRoot.class)
            .should().haveNameMatching("set[A-Z].*");

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
                String name = m.getName();
                return name.startsWith("get") || name.startsWith("is") || name.startsWith("set");
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
