/*
 *  Copyright (c) 2026 Metaform Systems, Inc.
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

import org.eclipse.edc.participantcontext.spi.store.ParticipantContextStore;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContext;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import static org.eclipse.edc.spi.system.ServiceExtensionContext.ANONYMOUS_PARTICIPANT;

@Extension(value = ClassicParticipantContextServicesExtension.NAME)
public class ClassicParticipantContextServicesExtension implements ServiceExtension {

    public static final String NAME = "Classic Participant Context Services Extension";

    @Setting(description = "Configures the participant id this runtime is operating on behalf of", key = "edc.participant.id", defaultValue = ANONYMOUS_PARTICIPANT)
    public String participantId;

    @Setting(description = "Configures the participant context id for the single participant runtime", key = "edc.participant.context.id", required = false)
    public String participantContextId;

    @Inject
    private Monitor monitor;

    @Inject
    private ParticipantContextStore participantContextStore;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        // Resolve the values from the merged runtime configuration as well as
        // the setting fields. This is required when the filesystem config is
        // mounted by a container and the setting injection runs before it is
        // available to this extension.
        participantId = configuredValue(context, "edc.participant.id", participantId);
        participantContextId = configuredValue(context, "edc.participant.context.id", participantContextId);

        if (participantId == null || participantContextId == null) {
            throw new IllegalStateException("Both edc.participant.id and edc.participant.context.id must be configured");
        }
    }
    
    @Override
    public void prepare() {
        initializeParticipantContext();
    }

    private void initializeParticipantContext() {
        var configuredParticipantId = resolveSetting("edc.participant.id", participantId);
        var configuredContextId = resolveSetting("edc.participant.context.id", participantContextId);
        var contextId = configuredContextId != null ? configuredContextId : configuredParticipantId;

        if (configuredParticipantId == null || contextId == null) {
            throw new IllegalStateException("Both edc.participant.id and edc.participant.context.id must be configured");
        }

        participantId = configuredParticipantId;
        participantContextId = contextId;
        var participantContext = participantContextBuilder(contextId)
                .identity(configuredParticipantId).build();

        if (participantContextStore.findById(contextId).failed()) {
            participantContextStore.create(participantContext)
                    .orElseThrow(f -> new EdcException(f.getFailureDetail()));
        } else {
            participantContextStore.update(participantContext)
                    .orElseThrow(f -> new EdcException(f.getFailureDetail()));
        }
    }

    private String configuredValue(ServiceExtensionContext context, String key, String defaultValue) {
        var value = context.getConfig().getString(key, defaultValue);
        return value != null ? value : System.getProperty(key, defaultValue);
    }

    private String resolveSetting(String key, String settingValue) {
        var value = settingValue;
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
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
}
