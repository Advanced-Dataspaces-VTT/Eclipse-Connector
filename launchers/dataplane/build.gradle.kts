import java.util.zip.ZipFile

plugins {
    `java-library`
    application
    alias(libs.plugins.shadow)
}

dependencies {
    // The current source tree no longer contains the data-plane modules. Keep
    // this launcher on one coherent published EDC release instead of mixing
    // removed project paths or current-source control-plane classes with the
    // 0.18 data-plane API.
    implementation("org.eclipse.edc:boot:0.18.0")
    implementation("org.eclipse.edc:connector-core:0.18.0")
    implementation("org.eclipse.edc:runtime-core:0.18.0")
    implementation("org.eclipse.edc:token-core:0.18.0")
    implementation("org.eclipse.edc:api-core:0.18.0")
    implementation("org.eclipse.edc:api-observability:0.18.0")
    implementation("org.eclipse.edc:configuration-filesystem:0.18.0")
    implementation("org.eclipse.edc:http:0.18.0")
    implementation("org.eclipse.edc:control-plane-api-client:0.18.0")
    implementation("org.eclipse.edc:participant-context-core:0.18.0")
    implementation("org.eclipse.edc:participant-context-config-core:0.18.0")
    implementation("org.eclipse.edc:participantcontext-config-store-sql:0.18.0")
    implementation("org.eclipse.edc:participantcontext-store-sql:0.18.0")
    implementation("org.eclipse.edc:control-api-configuration:0.18.0")
    implementation("org.eclipse.edc:data-plane-core:0.18.0")
    implementation("org.eclipse.edc:data-plane-http:0.18.0")
    implementation("org.eclipse.edc:data-plane-http-oauth2:0.18.0")
    implementation("org.eclipse.edc:data-plane-iam:0.18.0")
    implementation("org.eclipse.edc:data-plane-self-registration:0.18.0")
    // The published 0.18 selector client registers with POST and serializes
    // the legacy DataPlaneInstance shape. The current control plane expects
    // DataPlaneRegistrationMessage over PUT, so the launcher provides the
    // compatible client implementation below.
    implementation("org.eclipse.edc:data-plane-selector-spi:0.18.0")
    implementation("org.eclipse.edc:data-plane-signaling-client:0.18.0")
    implementation("org.eclipse.edc:data-plane-store-sql:0.18.0")
    implementation("org.eclipse.edc:sql-core:0.18.0")
    implementation("org.eclipse.edc:sql-lease-core:0.18.0")
    implementation("org.eclipse.edc:sql-pool-apache-commons:0.18.0")
    implementation("org.eclipse.edc:transaction-local:0.18.0")
    implementation("org.eclipse.edc:validator-data-address-http-data:0.18.0")
    implementation("org.eclipse.edc.aws:data-plane-aws-s3:0.18.0")
    implementation("org.eclipse.edc.aws:validator-data-address-s3:0.18.0")
    implementation("software.amazon.awssdk:s3:2.46.13")
    // Native Data Plane Signaling SDK. The old EDC signaling API is excluded
    // above because it exposes the same /v1/dataflows routes and expects the
    // legacy EDR model.
    implementation(libs.dataplane.sdk.core)
    implementation(libs.dataplane.sdk.jakarta.ee)
    implementation("org.eclipse.edc:vault-hashicorp:0.18.0")

    runtimeOnly(libs.postgres)
    runtimeOnly(libs.parsson)
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()

    filesMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    archiveFileName.set("dataplane.jar")
}

edcBuild {
    publish.set(false)
}

val verifyServiceDescriptors = tasks.register("verifyServiceDescriptors") {
    dependsOn(tasks.named("shadowJar"))
    doLast {
        val descriptor = "META-INF/services/org.eclipse.edc.spi.system.ServiceExtension"
        fun serviceNames(lines: Sequence<String>): Set<String> = lines
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        fun services(file: java.io.File): Set<String> = ZipFile(file).use { zip ->
            zip.getEntry(descriptor)?.let { entry ->
                zip.getInputStream(entry).bufferedReader().useLines(::serviceNames)
            } ?: emptySet()
        }
        val localDescriptor = layout.buildDirectory.file(
            "resources/main/$descriptor"
        ).get().asFile
        val expected = configurations.runtimeClasspath.get().files
            .filter { it.extension == "jar" }.flatMap { services(it) }.toSet()
            .plus(localDescriptor.takeIf { it.exists() }?.bufferedReader()?.useLines(::serviceNames).orEmpty())
        val actual = services(layout.buildDirectory.file("libs/dataplane.jar").get().asFile)
        check(expected.isNotEmpty()) { "No runtime service descriptors found" }
        check(actual == expected) { "Service registrations differ: missing=${expected - actual}, extra=${actual - expected}" }
        logger.lifecycle("Verified ${actual.size} EDC service registrations")
    }
}

tasks.named("check") { dependsOn(verifyServiceDescriptors) }
