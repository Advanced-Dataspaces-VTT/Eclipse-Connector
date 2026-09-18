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
    // Provides the selector implementation used by the control plane's
    // transfer signaling integration.
    implementation(project(":core:control-plane:control-plane-aggregate-services"))
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

    // JSON-P implementation required at runtime by the connector extensions.
    runtimeOnly(libs.parsson)
}

// The embedded compatibility helpers pull the old 0.18 web-spi transitively.
// Its SchemaType shadows this source tree's web SPI and breaks JSON-LD request
// deserialization at runtime. The separate dataplane launcher still declares
// the compatibility web SPI it needs, so keep this exclusion local to the
// control-plane image.
configurations.configureEach {
    exclude(group = "org.eclipse.edc", module = "web-spi")
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
