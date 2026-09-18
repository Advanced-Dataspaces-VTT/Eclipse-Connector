/*
 *  Copyright (c) 2025 Metaform Systems, Inc.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Metaform Systems, Inc. - initial API and implementation
 *
 */

package org.eclipse.edc.participantcontext.connector;

import org.eclipse.edc.participantcontext.connector.config.store.SingleParticipantContextConfigStore;
import org.eclipse.edc.participantcontext.single.spi.SingleParticipantContextSupplier;
import org.eclipse.edc.participantcontext.spi.config.model.ParticipantContextConfiguration;
import org.eclipse.edc.participantcontext.spi.config.store.ParticipantContextConfigStore;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContext;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.eclipse.edc.spi.system.ServiceExtensionContext.ANONYMOUS_PARTICIPANT;

@Extension(value = ClassicParticipantContextDefaultServicesExtension.NAME)
public class ClassicParticipantContextDefaultServicesExtension implements ServiceExtension {

    public static final String NAME = "Single Participant Context Default Services Extension";

    @Setting(description = "Configures the participant id this runtime is operating on behalf of", key = "edc.participant.id", defaultValue = ANONYMOUS_PARTICIPANT)
    public String participantId;

    @Setting(description = "Configures the participant context id for the single participant runtime", key = "edc.participant.context.id", required = false)
    public String participantContextId;

    @Inject
    private Monitor monitor;

    @Override
    public String name() {
        return NAME;
    }

    @Provider(isDefault = true)
    public SingleParticipantContextSupplier participantContextSupplier(ServiceExtensionContext context) {
        var configuredParticipantId = resolveSetting(context, "edc.participant.id", participantId);
        var configuredContextId = resolveSetting(context, "edc.participant.context.id", participantContextId);
        var contextId = configuredContextId != null ? configuredContextId : configuredParticipantId;
        if (configuredParticipantId == null || contextId == null) {
            throw new IllegalStateException("Both edc.participant.id and edc.participant.context.id must be configured");
        }
        var participantContext = participantContextBuilder(contextId)
                .identity(configuredParticipantId)
                .build();
        return () -> ServiceResult.success(participantContext);
    }

    private String resolveSetting(ServiceExtensionContext context, String key, String settingValue) {
        var value = context.getConfig().getString(key, settingValue);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key, settingValue);
        }
        if (value == null || value.isBlank()) {
            var envKey = key.toUpperCase().replace('.', '_');
            value = System.getenv(envKey);
        }
        return value == null || value.isBlank() ? null : value;
    }

    private ParticipantContext.Builder participantContextBuilder(String contextId) {
        var builder = ParticipantContext.Builder.newInstance();
        try {
            // 0.18 stores the context key separately from Entity.id. Invoke
            // both builder APIs so this launcher also works with the older
            // ParticipantContext ABI present in this source tree.
            builder.getClass().getMethod("participantContextId", String.class).invoke(builder, contextId);
            builder.getClass().getMethod("id", String.class).invoke(builder, contextId);
            return builder;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ParticipantContext builder does not support the required identifier API", e);
        }
    }


    @Provider
    public ParticipantContextConfigStore participantContextConfigStore(ServiceExtensionContext context) {
        var configuredParticipantId = resolveSetting(context, "edc.participant.id", participantId);
        var configuredContextId = resolveSetting(context, "edc.participant.context.id", participantContextId);
        var contextId = configuredContextId != null ? configuredContextId : configuredParticipantId;

        var cfg = ParticipantContextConfiguration.Builder.newInstance()
                .participantContextId(contextId)
                .entries(context.getConfig().getEntries())
                .build();
        return new SingleParticipantContextConfigStore(cfg);
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        // Keep this compatible with runtimes assembled from published 0.18
        // dataplane modules: explicitly resolve the values from the merged
        // configuration instead of relying only on field setting injection.
        participantId = resolveSetting(context, "edc.participant.id", participantId);
        participantContextId = resolveSetting(context, "edc.participant.context.id", participantContextId);
        if (participantContextId == null) {
            participantContextId = participantId;
        }

        if (participantId == null) {
            throw new IllegalStateException("Both edc.participant.id and edc.participant.context.id must be configured");
        }
        if (ANONYMOUS_PARTICIPANT.equals(participantContextId)) {
            monitor.warning("The runtime is configured as an anonymous participant. DO NOT DO THIS IN PRODUCTION.");
        }
    }

}
