/*
 * Copyright (c) 2026 Advanced Dataspaces VTT
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 */

import java.util.zip.ZipFile

plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

dependencies {
    // Includes the control plane, DSP HTTP endpoints and data-plane signaling.
    implementation(project(":dist:bom:controlplane-dcp-bom"))
    implementation(project(":extensions:control-plane:api:management-api-v5"))
    implementation(project(":core:common:cel-core"))
    implementation(project(":extensions:common:api:management-api-authorization"))
    implementation(project(":extensions:common:api:management-api-oauth2-authentication"))
    implementation(project(":data-protocols:dsp:dsp-virtual"))

    // Persistent stores and secrets used by the GX participant deployment.
    implementation(project(":dist:bom:controlplane-feature-sql-bom"))
    implementation(project(":extensions:common:vault:vault-hashicorp"))
    implementation(project(":extensions:common:iam:decentralized-claims:decentralized-claims-sts:decentralized-claims-sts-signature-registrar"))
    implementation(project(":extensions:common:iam:decentralized-claims:decentralized-claims-sts:decentralized-claims-sts-registry"))
    implementation(project(":extensions:common:iam:decentralized-claims:decentralized-claims-sts:decentralized-claims-sts-remote-registrar"))

    // Shared services required by the embedded data-plane runtime.
    implementation("org.eclipse.edc:control-api-configuration:0.18.0")
    implementation("org.eclipse.edc:control-plane-api-client:0.18.0")
    implementation("org.eclipse.edc:transfer-data-plane-signaling:0.18.0")
    implementation("org.eclipse.edc:validator-lib:0.18.0")
    implementation("org.eclipse.edc:http-lib:0.18.0")

    // Embed the EDC 0.18 data-plane runtime in this control-plane image.
    // These are published EDC modules because this customized source tree no
    // longer carries the standalone data-plane subprojects.
    implementation("org.eclipse.edc:data-plane-core:0.18.0")
    implementation("org.eclipse.edc:data-plane-http:0.18.0")
    implementation("org.eclipse.edc:data-plane-http-oauth2:0.18.0")
    implementation("org.eclipse.edc:data-plane-iam:0.18.0")
    implementation("org.eclipse.edc:data-plane-self-registration:0.18.0")
    implementation("org.eclipse.edc:data-plane-signaling-api:0.18.0")
    implementation("org.eclipse.edc:data-plane-signaling-client:0.18.0")
    implementation("org.eclipse.edc:data-plane-selector-client:0.18.0")
    implementation("org.eclipse.edc:data-plane-store-sql:0.18.0")
    implementation("org.eclipse.edc:validator-data-address-http-data:0.18.0")
    implementation("org.eclipse.edc.aws:data-plane-aws-s3:0.18.0")
    implementation("org.eclipse.edc.aws:validator-data-address-s3:0.18.0")

    // JSON-P implementation required at runtime by the connector extensions.
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
    archiveFileName.set("controlplane.jar")
}

edcBuild {
    publish.set(false)
}

val verifyServiceDescriptors = tasks.register("verifyServiceDescriptors") {
    dependsOn(tasks.named("shadowJar"))
    doLast {
        val descriptor = "META-INF/services/org.eclipse.edc.spi.system.ServiceExtension"
        fun services(file: java.io.File): Set<String> = ZipFile(file).use { zip ->
            zip.getEntry(descriptor)?.let { entry ->
                zip.getInputStream(entry).bufferedReader().useLines { lines ->
                    lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()
                }
            } ?: emptySet()
        }
        val expected = configurations.runtimeClasspath.get().files
            .filter { it.extension == "jar" }.flatMap { services(it) }.toSet()
        val actual = services(layout.buildDirectory.file("libs/controlplane.jar").get().asFile)
        check(expected.isNotEmpty()) { "No runtime service descriptors found" }
        check(actual == expected) { "Service registrations differ: missing=${expected - actual}, extra=${actual - expected}" }
        logger.lifecycle("Verified ${actual.size} EDC service registrations")
    }
}

tasks.named("check") { dependsOn(verifyServiceDescriptors) }
