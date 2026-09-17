/*
 * Copyright (c) 2026 Advanced Dataspaces VTT
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 */

package org.eclipse.edc.participantcontext.connector;

import org.eclipse.edc.participantcontext.single.spi.SingleParticipantContextSupplier;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContext;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.spi.system.ServiceExtension;

import static org.eclipse.edc.spi.system.ServiceExtensionContext.ANONYMOUS_PARTICIPANT;

/** Supplies the one participant context used by this standalone dataplane. */
@Extension(DataplaneSingleParticipantContextExtension.NAME)
public class DataplaneSingleParticipantContextExtension implements ServiceExtension {

    public static final String NAME = "Standalone Dataplane Participant Context";

    @Setting(description = "Participant identity served by this dataplane", key = "edc.participant.id", defaultValue = ANONYMOUS_PARTICIPANT)
    private String participantId;

    @Setting(description = "Optional participant context id", key = "edc.participant.context.id", required = false)
    private String participantContextId;

    @Provider(isDefault = true)
    public SingleParticipantContextSupplier participantContextSupplier() {
        var contextId = participantContextId != null ? participantContextId : participantId;
        var participantContext = ParticipantContext.Builder.newInstance()
                .participantContextId(contextId)
                .identity(participantId)
                .build();
        return () -> ServiceResult.success(participantContext);
    }
}
