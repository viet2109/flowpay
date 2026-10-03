package com.flowpay.backend.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(
        packages = "com.flowpay.backend",
        importOptions = ImportOption.DoNotIncludeTests.class
)
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
    static final ArchRule financialModulesMustNotDependOnWebhook = noClasses()
            .that().resideInAnyPackage("com.flowpay.backend.payment..",
                    "com.flowpay.backend.refund..", "com.flowpay.backend.ledger..")
            .should().dependOnClassesThat().resideInAPackage("com.flowpay.backend.webhook..");

    @ArchTest
    static final ArchRule webhookMustNotAccessSourceImplementationDetails = noClasses()
            .that().resideInAPackage("com.flowpay.backend.webhook..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.flowpay.backend.payment.api..", "com.flowpay.backend.payment.domain..",
                    "com.flowpay.backend.payment.infrastructure..", "com.flowpay.backend.refund.api..",
                    "com.flowpay.backend.refund.domain..", "com.flowpay.backend.refund.infrastructure..",
                    "com.flowpay.backend.ledger..");

    @ArchTest
    static final ArchRule messagingMustUseOnlyWebhookApplicationContractAndPublicEventValues = noClasses()
            .that().resideInAPackage("com.flowpay.backend.infrastructure.messaging..")
            .should().dependOnClassesThat(DescribedPredicate.<JavaClass>describe(
                    "Webhook HTTP/persistence implementation or non-contract domain types",
                    type -> type.getPackageName().startsWith("com.flowpay.backend.webhook.api.")
                            || type.getPackageName().equals("com.flowpay.backend.webhook.api")
                            || type.getPackageName().startsWith("com.flowpay.backend.webhook.infrastructure.")
                            || type.getPackageName().equals("com.flowpay.backend.webhook.infrastructure")
                            || (type.getPackageName().startsWith("com.flowpay.backend.webhook.domain")
                            && !java.util.Set.of("com.flowpay.backend.webhook.domain.WebhookEventType",
                            "com.flowpay.backend.webhook.domain.WebhookResourceType").contains(type.getName()))));

    @ArchTest
    static final ArchRule webhookControllersMustNotOrchestrateDeliveryOrCryptography = noClasses()
            .that().resideInAPackage("com.flowpay.backend.webhook.api..")
            .and().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat(DescribedPredicate.<JavaClass>describe(
                    "Webhook delivery execution or cryptography internals",
                    type -> java.util.Set.of(
                            "com.flowpay.backend.webhook.application.WebhookHttpClientPort",
                            "com.flowpay.backend.webhook.application.WebhookSigner",
                            "com.flowpay.backend.webhook.application.WebhookSecretCipher",
                            "com.flowpay.backend.webhook.application.WebhookDeliveryWorker",
                            "com.flowpay.backend.webhook.application.WebhookDeliveryExecutionService"
                    ).contains(type.getName())));

    @ArchTest
    static final ArchRule webhookMustUseOnlyMerchantAccessContract = noClasses()
            .that().resideInAPackage("com.flowpay.backend.webhook..")
            .should().dependOnClassesThat(DescribedPredicate.<JavaClass>describe(
                    "Merchant application internals outside the public access contract",
                    type -> type.getPackageName().startsWith("com.flowpay.backend.merchant.application")
                            && !java.util.Set.of("com.flowpay.backend.merchant.application.MerchantAccessApi",
                            "com.flowpay.backend.merchant.application.ActiveMerchantSnapshot").contains(type.getName())));

    @ArchTest
    static final ArchRule webhookMaterializationMustNotPerformDeliveryOrCryptography = noClasses()
            .that().haveFullyQualifiedName("com.flowpay.backend.webhook.application.WebhookEventMaterializationService")
            .should().dependOnClassesThat(DescribedPredicate.<JavaClass>describe(
                    "Webhook HTTP or signing components",
                    type -> java.util.Set.of("WebhookHttpClientPort", "WebhookSigner", "WebhookSecretCipher",
                            "WebhookDeliveryWorker", "WebhookDeliveryExecutionService").contains(type.getSimpleName())
                            && type.getPackageName().startsWith("com.flowpay.backend.webhook.")));

    @ArchTest
    static final ArchRule webhookMustUseOnlyMerchantPublicApplicationApi = noClasses()
            .that().resideInAPackage("com.flowpay.backend.webhook..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.flowpay.backend.merchant.domain..",
                    "com.flowpay.backend.merchant.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule webhookMustNotAccessAnotherModulesRepositories = noClasses()
            .that().resideInAPackage("com.flowpay.backend.webhook..")
            .should().dependOnClassesThat(DescribedPredicate.<JavaClass>describe(
                    "business repositories outside Webhook",
                    type -> type.getPackageName().startsWith("com.flowpay.backend.")
                            && !type.getPackageName().startsWith("com.flowpay.backend.webhook.")
                            && type.getSimpleName().endsWith("Repository")));

    @ArchTest
    static final ArchRule webhookJpaEntitiesMustRemainPackagePrivate = classes()
            .that().resideInAPackage("com.flowpay.backend.webhook.infrastructure.persistence..")
            .and().haveSimpleNameEndingWith("Entity")
            .should().notBePublic();

    @ArchTest
    static final ArchRule webhookJpaRepositoriesMustRemainPackagePrivate = classes()
            .that().resideInAPackage("com.flowpay.backend.webhook.infrastructure.persistence..")
            .and().haveSimpleNameEndingWith("JpaRepository")
            .should().notBePublic();

    @ArchTest
    static final ArchRule webhookDomainMustRemainTechnologyIndependent = noClasses()
            .that().resideInAPackage("com.flowpay.backend.webhook.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "org.hibernate..",
                    "tools.jackson..", "com.fasterxml.jackson..", "java.net.http..");

    @ArchTest
    static final ArchRule webhookDomainMustNotImportSourceBusinessModules = noClasses()
            .that().resideInAPackage("com.flowpay.backend.webhook.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.flowpay.backend.payment..", "com.flowpay.backend.refund..");

    @ArchTest
    static final ArchRule webhookCoreMustNotDependOnTransportOrSourceModules = noClasses()
            .that().resideInAnyPackage("com.flowpay.backend.webhook.application..", "com.flowpay.backend.webhook.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.amqp..", "com.rabbitmq..",
                    "com.flowpay.backend.payment..", "com.flowpay.backend.refund..",
                    "com.flowpay.backend.infrastructure.messaging..");

    @ArchTest
    static final ArchRule webhookHttpAdapterMustNotDependOnRepositories = noClasses()
            .that().resideInAPackage("com.flowpay.backend.webhook.infrastructure.http..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule webhookBusinessCoreMustNotUseHttpClientTechnology = noClasses()
            .that().resideInAnyPackage("com.flowpay.backend.webhook.application..", "com.flowpay.backend.webhook.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("java.net.http..", "org.springframework.web.client..");

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
    static final ArchRule paymentMustNotDependOnLedger = noClasses()
            .that().resideInAPackage("com.flowpay.backend.payment..")
            .should().dependOnClassesThat().resideInAPackage(
                    "com.flowpay.backend.ledger.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule ledgerMustNotDependOnPayment = noClasses()
            .that().resideInAPackage("com.flowpay.backend.ledger..")
            .should().dependOnClassesThat().resideInAPackage(
                    "com.flowpay.backend.payment.."
            )
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
    static final ArchRule refundMustNotDependOnLedger = noClasses()
            .that().resideInAPackage("com.flowpay.backend.refund..")
            .should().dependOnClassesThat().resideInAPackage(
                    "com.flowpay.backend.ledger.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule ledgerMustNotDependOnRefund = noClasses()
            .that().resideInAPackage("com.flowpay.backend.ledger..")
            .should().dependOnClassesThat().resideInAPackage(
                    "com.flowpay.backend.refund.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule ledgerMustNotDependOnMerchantInfrastructure = noClasses()
            .that().resideInAPackage("com.flowpay.backend.ledger..")
            .should().dependOnClassesThat().resideInAPackage(
                    "com.flowpay.backend.merchant.infrastructure.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule ledgerMustNotDependOnIdempotency = noClasses()
            .that().resideInAPackage("com.flowpay.backend.ledger..")
            .should().dependOnClassesThat().resideInAPackage(
                    "com.flowpay.backend.idempotency.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule ledgerJpaEntitiesMustRemainPackagePrivate = classes()
            .that().resideInAPackage(
                    "com.flowpay.backend.ledger.infrastructure.persistence.."
            )
            .and().haveSimpleNameEndingWith("Entity")
            .should().notBePublic()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule ledgerJpaRepositoriesMustRemainPackagePrivate = classes()
            .that().resideInAPackage(
                    "com.flowpay.backend.ledger.infrastructure.persistence.."
            )
            .and().haveSimpleNameEndingWith("JpaRepository")
            .should().notBePublic()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule ledgerMustNotExposeHttpControllersInPhaseFive = classes()
            .that().resideInAPackage("com.flowpay.backend.ledger..")
            .should().notBeAnnotatedWith(RestController.class)
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
    static final ArchRule producerEventContractsMustRemainBrokerAndConsumerIndependent = noClasses()
            .that().resideInAnyPackage(
                    "com.flowpay.backend.payment.application.event..",
                    "com.flowpay.backend.refund.application.event.."
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.amqp..",
                    "com.fasterxml.jackson..",
                    "jakarta.persistence..",
                    "com.flowpay.backend.ledger..",
                    "com.flowpay.backend.webhook.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule outboxPersistenceMustRemainBrokerIndependent = noClasses()
            .that().resideInAPackage(
                    "com.flowpay.backend.infrastructure.messaging.outbox.."
            )
            .should().dependOnClassesThat().resideInAPackage(
                    "org.springframework.amqp.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule rabbitTemplateMustRemainInTopLevelMessagingInfrastructure = noClasses()
            .that().resideOutsideOfPackage(
                    "com.flowpay.backend.infrastructure.messaging.."
            )
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "org.springframework.amqp.rabbit.core.RabbitTemplate"
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule businessModulesMustRemainBrokerIndependent = noClasses()
            .that().resideInAnyPackage(BUSINESS_MODULES)
            .should().dependOnClassesThat().resideInAPackage(
                    "org.springframework.amqp.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule businessModulesMustNotDependOnMessagingInfrastructure = noClasses()
            .that().resideInAnyPackage(BUSINESS_MODULES)
            .should().dependOnClassesThat().resideInAPackage(
                    "com.flowpay.backend.infrastructure.messaging.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule amqpTransportMustRemainInTopLevelMessagingInfrastructure = noClasses()
            .that().resideOutsideOfPackage(
                    "com.flowpay.backend.infrastructure.messaging.."
            )
            .should().dependOnClassesThat().resideInAPackage(
                    "org.springframework.amqp.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule messagingMustNotDependOnSourceImplementationDetails = noClasses()
            .that().resideInAPackage(
                    "com.flowpay.backend.infrastructure.messaging.."
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.flowpay.backend.payment.api..",
                    "com.flowpay.backend.payment.domain..",
                    "com.flowpay.backend.payment.infrastructure..",
                    "com.flowpay.backend.refund.api..",
                    "com.flowpay.backend.refund.domain..",
                    "com.flowpay.backend.refund.infrastructure.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule ledgerMustNotDependOnMessagingInfrastructure = noClasses()
            .that().resideInAPackage("com.flowpay.backend.ledger..")
            .should().dependOnClassesThat().resideInAPackage(
                    "com.flowpay.backend.infrastructure.messaging.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule ledgerEventConsumerMustNotAccessSourcePersistence = noClasses()
            .that().resideInAPackage(
                    "com.flowpay.backend.infrastructure.messaging.rabbit.."
            )
            .and().haveSimpleNameStartingWith("LedgerIntegrationEvent")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.flowpay.backend.payment.infrastructure..",
                    "com.flowpay.backend.refund.infrastructure..",
                    "com.flowpay.backend.merchant.infrastructure.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule topLevelPackagesMustBeFreeOfCycles = slices()
            .matching("com.flowpay.backend.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);
}
