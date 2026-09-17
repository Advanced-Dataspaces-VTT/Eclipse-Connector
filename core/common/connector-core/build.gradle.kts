/*
 *  Copyright (c) 2021 - 2022 Fraunhofer-Gesellschaft zur Förderung der angewandten Forschung e.V.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Fraunhofer-Gesellschaft zur Förderung der angewandten Forschung e.V. - initial API and implementation
 *       Bayerische Motoren Werke Aktiengesellschaft (BMW AG) - initial API and implementation
 *
 */

plugins {
    `java-library`
}

dependencies {
    api(project(":spi:control-plane-spi"))
    api(project(":core:common:security-core"))

    // Interfaces used by the embedded EDC 0.18 dataplane services.
    implementation("org.eclipse.edc:auth-spi:0.18.0")
    implementation("org.eclipse.edc:http-lib:0.18.0")
    implementation("org.eclipse.edc:http-spi:0.18.0")
    implementation("org.eclipse.edc:validator-spi:0.18.0")

    implementation(project(":core:common:lib:core-lib"))
    implementation(project(":core:control-plane:lib:control-plane-lib"))

    testImplementation(project(":core:common:junit"))
    testImplementation(libs.awaitility)
}
