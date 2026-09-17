/*
 *  Copyright (c) 2026 Eclipse Foundation.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 */

package org.eclipse.edc.connector.dataplane.auth;

import org.eclipse.edc.api.auth.spi.ControlClientAuthenticationProvider;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Provides;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.ServiceExtension;

import java.util.Map;

/**
 * Supplies credentials for the legacy 0.18 dataplane self-registration client.
 *
 * EDC 0.18 defaults this provider to an empty header map. The control plane in
 * this deployment protects its management API with delegated bearer-token
 * authentication, so the value is supplied as a secret at runtime.
 */
@Extension(StaticControlPlaneAuthenticationExtension.NAME)
@Provides(ControlClientAuthenticationProvider.class)
public class StaticControlPlaneAuthenticationExtension implements ServiceExtension {

    public static final String NAME = "Dataplane Control Plane Client Authentication";

    @Setting(
            key = "edc.controlplane.api.auth.value",
            description = "Authorization header value used by dataplane self-registration, for example 'Bearer <token>'",
            required = false
    )
    private String authorizationValue;

    @Provider
    public ControlClientAuthenticationProvider controlClientAuthenticationProvider() {
        return () -> authorizationValue == null || authorizationValue.isBlank()
                ? Map.of()
                : Map.of("Authorization", authorizationValue);
    }
}
