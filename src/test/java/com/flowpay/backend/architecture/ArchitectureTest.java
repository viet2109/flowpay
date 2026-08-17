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
            "com.flowpay.backend.refund..",
            "com.flowpay.backend.ledger..",
            "com.flowpay.backend.webhook.."
    };

    private static final String[] BUSINESS_INFRASTRUCTURE = {
            "com.flowpay.backend.identity.infrastructure..",
            "com.flowpay.backend.merchant.infrastructure..",
            "com.flowpay.backend.payment.infrastructure..",
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
    static final ArchRule topLevelPackagesMustBeFreeOfCycles = slices()
            .matching("com.flowpay.backend.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);
}
