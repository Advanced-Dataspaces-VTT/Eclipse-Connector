/*
 * Copyright (c) 2026 Advanced Dataspaces VTT
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0.
 */

package org.eclipse.edc.connector.core.validator;

import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.types.domain.DataAddress;
import org.eclipse.edc.validator.spi.DataAddressValidatorRegistry;
import org.eclipse.edc.validator.spi.ValidationResult;
import org.eclipse.edc.validator.spi.Validator;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/** Registry for source and destination validators used by data-plane extensions. */
public class DataAddressValidatorRegistryImpl implements DataAddressValidatorRegistry {

    private final Map<String, Validator<DataAddress>> sourceValidators = new HashMap<>();
    private final Map<String, Validator<DataAddress>> destinationValidators = new HashMap<>();
    private final Monitor monitor;

    public DataAddressValidatorRegistryImpl(Monitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void registerSourceValidator(String type, Validator<DataAddress> validator) {
        sourceValidators.put(type, validator);
    }

    @Override
    public void registerDestinationValidator(String type, Validator<DataAddress> validator) {
        destinationValidators.put(type, validator);
    }

    @Override
    public ValidationResult validateSource(DataAddress dataAddress) {
        return sourceValidators.getOrDefault(dataAddress.getType(), d -> warning("source", dataAddress))
                .validate(dataAddress);
    }

    @Override
    public ValidationResult validateDestination(DataAddress dataAddress) {
        return destinationValidators.getOrDefault(dataAddress.getType(), d -> warning("destination", dataAddress))
                .validate(dataAddress);
    }

    @NotNull
    private ValidationResult warning(String direction, DataAddress dataAddress) {
        monitor.warning("No %s DataAddress validator registered for type %s".formatted(direction, dataAddress.getType()));
        return ValidationResult.success();
    }
}
