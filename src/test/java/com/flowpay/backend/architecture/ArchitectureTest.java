package com.flowpay.backend.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.flowpay.backend")
class ArchitectureTest {

    private static final String[] BUSINESS_MODULES = {
            "com.flowpay.backend.identity..",
            "com.flowpay.backend.merchant..",
            "com.flowpay.backend.payment..",
            "com.flowpay.backend.idempotency..",
            "com.flowpay.backend.refund..",
            "com.flowpay.backend.ledger..",
            "com.flowpay.backend.webhook.."
    };

    private static final String[] BUSINESS_INFRASTRUCTURE = {
            "com.flowpay.backend.identity.infrastructure..",
            "com.flowpay.backend.merchant.infrastructure..",
            "com.flowpay.backend.payment.infrastructure..",
            "com.flowpay.backend.idempotency.infrastructure..",
            "com.flowpay.backend.refund.infrastructure..",
            "com.flowpay.backend.ledger.infrastructure..",
            "com.flowpay.backend.webhook.infrastructure.."
    };

    @ArchTest
    static final ArchRule domainMustNotDependOnInfrastructure = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule businessCoreMustNotDependOnBusinessInfrastructure = noClasses()
            .that().resideInAnyPackage(BUSINESS_MODULES)
            .and().resideOutsideOfPackage("..infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage(BUSINESS_INFRASTRUCTURE)
            .allowEmptyShould(true);


    @ArchTest
    static final ArchRule commonMustNotDependOnInfrastructure = noClasses()
            .that().resideInAPackage("com.flowpay.backend.common..")
            .should().dependOnClassesThat().resideInAPackage("com.flowpay.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule commonMustNotDependOnBusinessModules = noClasses()
            .that().resideInAPackage("com.flowpay.backend.common..")
            .should().dependOnClassesThat().resideInAnyPackage(BUSINESS_MODULES)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule restControllersBelongToApiPackages = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage("..api..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule restControllersMustNotDependOnRepositories = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule restControllersMustNotDependOnInfrastructure = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule merchantMustNotDependOnIdentityInfrastructure = noClasses()
            .that().resideInAPackage("com.flowpay.backend.merchant..")
            .should().dependOnClassesThat().resideInAPackage("com.flowpay.backend.identity.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule paymentMustNotDependOnMerchantInfrastructure = noClasses()
            .that().resideInAPackage("com.flowpay.backend.payment..")
            .should().dependOnClassesThat().resideInAPackage("com.flowpay.backend.merchant.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule paymentMustNotDependOnRefund = noClasses()
            .that().resideInAPackage("com.flowpay.backend.payment..")
            .should().dependOnClassesThat().resideInAPackage("com.flowpay.backend.refund..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule refundMustNotDependOnPaymentInternals = noClasses()
            .that().resideInAPackage("com.flowpay.backend.refund..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.flowpay.backend.payment.domain..",
                    "com.flowpay.backend.payment.infrastructure.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule refundMustNotDependOnMerchantInfrastructure = noClasses()
            .that().resideInAPackage("com.flowpay.backend.refund..")
            .should().dependOnClassesThat().resideInAPackage(
                    "com.flowpay.backend.merchant.infrastructure.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule refundMustNotDependOnIdempotencyInfrastructure = noClasses()
            .that().resideInAPackage("com.flowpay.backend.refund..")
            .should().dependOnClassesThat().resideInAPackage(
                    "com.flowpay.backend.idempotency.infrastructure.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule refundJpaEntitiesMustRemainPackagePrivate = classes()
            .that().resideInAPackage(
                    "com.flowpay.backend.refund.infrastructure.persistence.."
            )
            .and().haveSimpleNameEndingWith("Entity")
            .should().notBePublic()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule refundJpaRepositoriesMustRemainPackagePrivate = classes()
            .that().resideInAPackage(
                    "com.flowpay.backend.refund.infrastructure.persistence.."
            )
            .and().haveSimpleNameEndingWith("JpaRepository")
            .should().notBePublic()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule idempotencyMustNotDependOnPaymentInfrastructure = noClasses()
            .that().resideInAPackage("com.flowpay.backend.idempotency..")
            .should().dependOnClassesThat().resideInAPackage("com.flowpay.backend.payment.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule idempotencyMustNotDependOnPayment = noClasses()
            .that().resideInAPackage("com.flowpay.backend.idempotency..")
            .should().dependOnClassesThat().resideInAPackage("com.flowpay.backend.payment..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule idempotencyMustNotDependOnRefund = noClasses()
            .that().resideInAPackage("com.flowpay.backend.idempotency..")
            .should().dependOnClassesThat().resideInAPackage("com.flowpay.backend.refund..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule paymentCoreMustNotDependOnIdempotencyInfrastructure = noClasses()
            .that().resideInAnyPackage(
                    "com.flowpay.backend.payment.api..",
                    "com.flowpay.backend.payment.application..",
                    "com.flowpay.backend.payment.domain.."
            )
            .should().dependOnClassesThat().resideInAPackage("com.flowpay.backend.idempotency.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule idempotencyJpaEntitiesMustRemainPackagePrivate = classes()
            .that().resideInAPackage(
                    "com.flowpay.backend.idempotency.infrastructure.persistence.."
            )
            .and().haveSimpleNameEndingWith("Entity")
            .should().notBePublic()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule idempotencyJpaRepositoriesMustRemainPackagePrivate = classes()
            .that().resideInAPackage(
                    "com.flowpay.backend.idempotency.infrastructure.persistence.."
            )
            .and().haveSimpleNameEndingWith("JpaRepository")
            .should().notBePublic()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationCodeMustNotAccessSecurityContextDirectly = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "org.springframework.security.core.context.SecurityContextHolder"
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule topLevelPackagesMustBeFreeOfCycles = slices()
            .matching("com.flowpay.backend.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);
}
