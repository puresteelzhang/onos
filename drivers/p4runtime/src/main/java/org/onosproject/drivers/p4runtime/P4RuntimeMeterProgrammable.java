/*
 * Copyright 2017-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.onosproject.drivers.p4runtime;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Striped;
import org.onosproject.drivers.p4runtime.mirror.P4RuntimeMeterMirror;
import org.onosproject.drivers.p4runtime.mirror.TimedEntry;
import org.onosproject.net.DeviceId;
import org.onosproject.net.meter.Band;
import org.onosproject.net.meter.DefaultBand;
import org.onosproject.net.meter.DefaultMeter;
import org.onosproject.net.meter.DefaultMeterFeatures;
import org.onosproject.net.meter.Meter;
import org.onosproject.net.meter.MeterFeatures;
import org.onosproject.net.meter.MeterOperation;
import org.onosproject.net.meter.MeterProgrammable;
import org.onosproject.net.meter.MeterScope;
import org.onosproject.net.meter.MeterState;
import org.onosproject.net.pi.model.PiMeterId;
import org.onosproject.net.pi.model.PiMeterModel;
import org.onosproject.net.pi.model.PiPipelineModel;
import org.onosproject.net.pi.runtime.PiMeterCellConfig;
import org.onosproject.net.pi.runtime.PiMeterCellHandle;
import org.onosproject.net.pi.runtime.PiMeterCellId;
import org.onosproject.net.pi.service.PiMeterTranslator;
import org.onosproject.net.pi.service.PiTranslatedEntity;
import org.onosproject.net.pi.service.PiTranslationException;
import org.onosproject.p4runtime.api.P4RuntimeWriteClient.WriteRequest;
import org.onosproject.p4runtime.api.P4RuntimeWriteClient.WriteResponse;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.onosproject.net.meter.MeterOperation.Type.ADD;
import static org.onosproject.net.meter.MeterOperation.Type.MODIFY;
import static org.onosproject.net.meter.MeterOperation.Type.REMOVE;
import static org.onosproject.p4runtime.api.P4RuntimeWriteClient.UpdateType;

/**
 * Implementation of MeterProgrammable behaviour for P4Runtime.
 */
public class P4RuntimeMeterProgrammable extends AbstractP4RuntimeHandlerBehaviour implements MeterProgrammable {

    private static final Striped<Lock> WRITE_LOCKS = Striped.lock(30);

    private PiMeterTranslator translator;
    private P4RuntimeMeterMirror meterMirror;
    private PiPipelineModel pipelineModel;

    @Override
    protected boolean setupBehaviour(String opName) {
        if (!super.setupBehaviour(opName)) {
            return false;
        }

        translator = translationService.meterTranslator();
        meterMirror = handler().get(P4RuntimeMeterMirror.class);
        pipelineModel = pipeconf.pipelineModel();
        return true;
    }

    @Override
    public CompletableFuture<Boolean> performMeterOperation(MeterOperation meterOp) {

        if (!setupBehaviour("performMeterOperation()")) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.completedFuture(processMeterOp(meterOp));
    }

    private boolean processMeterOp(MeterOperation meterOp) {
        PiMeterCellConfig piMeterCellConfig;
        final PiMeterCellHandle handle = PiMeterCellHandle.of(deviceId,
                                            (PiMeterCellId) meterOp.meter().meterCellId());
        boolean result = true;
        WRITE_LOCKS.get(deviceId).lock();
        try {
            switch (meterOp.type()) {
                case ADD:
                case MODIFY:
                    // Create a config for modify operation
                    try {
                        piMeterCellConfig = translator.translate(meterOp.meter(), pipeconf);
                    } catch (PiTranslationException e) {
                        log.warn("Unable translate meter, aborting meter operation {}: {}",
                            meterOp.type(), e.getMessage());
                        log.debug("exception", e);
                        return false;
                    }
                    translator.learn(handle, new PiTranslatedEntity<>(meterOp.meter(), piMeterCellConfig, handle));
                    break;
                case REMOVE:
                    // Create a empty config for reset operation
                    PiMeterCellId piMeterCellId = (PiMeterCellId) meterOp.meter().meterCellId();
                    piMeterCellConfig = PiMeterCellConfig.reset(piMeterCellId);
                    translator.forget(handle);
                    break;
                default:
                    log.warn("Meter Operation type {} not supported", meterOp.type());
                    return false;
            }

            WriteRequest request = client.write(p4DeviceId, pipeconf);
            appendEntryToWriteRequestOrSkip(request, handle, piMeterCellConfig);
            if (!request.pendingUpdates().isEmpty()) {
                result = request.submitSync().isSuccess();
                if (result) {
                    meterMirror.applyWriteRequest(request);
                }
            }
        } finally {
            WRITE_LOCKS.get(deviceId).unlock();
        }
        return result;
    }

    @Override
    public CompletableFuture<Collection<Meter>> getMeters() {

        if (!setupBehaviour("getMeters()")) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        Collection<PiMeterCellConfig> piMeterCellConfigs;

        Set<PiMeterId> meterIds = new HashSet<>();
        for (PiMeterModel mode : pipelineModel.meters()) {
            meterIds.add(mode.id());
        }

        piMeterCellConfigs = client.read(p4DeviceId, pipeconf)
                .meterCells(meterIds).submitSync().all(PiMeterCellConfig.class);

        meterMirror.sync(deviceId, piMeterCellConfigs);

        if (piMeterCellConfigs.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        List<PiMeterCellId> inconsistentOrDefaultCells = Lists.newArrayList();
        List<Meter> meters = Lists.newArrayList();

        // Check the consistency of meter config
        for (PiMeterCellConfig config : piMeterCellConfigs) {
            PiMeterCellHandle handle = PiMeterCellHandle.of(deviceId, config);
            DefaultMeter meter = (DefaultMeter) forgeMeter(config, handle);
            if (meter == null) {
                // A default config cannot be used to forge meter
                // because meter has at least 1 band while default config has no band
                inconsistentOrDefaultCells.add(config.cellId());
            } else {
                meters.add(meter);
            }
        }

        // Reset all inconsistent meter cells to default state
        if (!inconsistentOrDefaultCells.isEmpty()) {
            WriteRequest request = client.write(p4DeviceId, pipeconf);
            for (PiMeterCellId cellId : inconsistentOrDefaultCells) {
                PiMeterCellHandle handle = PiMeterCellHandle.of(deviceId, cellId);
                appendEntryToWriteRequestOrSkip(request, handle, PiMeterCellConfig.reset(cellId));
            }
            WriteResponse response = request.submitSync();
            meterMirror.applyWriteResponse(response);
        }

        return CompletableFuture.completedFuture(meters);
    }

    @Override
    public CompletableFuture<Collection<MeterFeatures>> getMeterFeatures() {

        if (!setupBehaviour("getMeterFeatures()")) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        Collection<MeterFeatures> meterFeatures = new HashSet<>();
        pipeconf.pipelineModel().meters().forEach(
            m -> meterFeatures.add(new P4RuntimeMeterFeaturesBuilder(m, deviceId).build()));

        return CompletableFuture.completedFuture(meterFeatures);
    }

    private Meter forgeMeter(PiMeterCellConfig config, PiMeterCellHandle handle) {
        final Optional<PiTranslatedEntity<Meter, PiMeterCellConfig>>
            translatedEntity = translator.lookup(handle);
        final TimedEntry<PiMeterCellConfig> timedEntry = meterMirror.get(handle);

        // A meter cell config might not be present in the translation store if it
        // is default configuration.
        if (translatedEntity.isEmpty()) {
            if (!config.isDefaultConfig()) {
                log.warn("Meter Cell Config obtained from device {} is different from " +
                         "one in in translation store: device={}, store=Default", deviceId, config);
            } else {
                log.debug("Configs obtained from device: {} and present in the store are default, " +
                          "skipping the forge section");
            }
            return null;
        }
        // The config is not consistent
        if (!translatedEntity.get().translated().equals(config)) {
            log.warn("Meter Cell Config obtained from device {} is different from " +
                             "one in in translation store: device={}, store={}",
                     deviceId, config, translatedEntity.get().translated());
            return null;
        }
        if (timedEntry == null) {
            log.warn("Meter entry handle not found in device mirror: {}", handle);
            return null;
        }

        // Forge a meter with MeterCellId, Bands and DeviceId
        // Other values are not required because we cannot retrieve them from the south
        DefaultMeter meter = (DefaultMeter) DefaultMeter.builder()
                            .withBands(config.meterBands().stream().map(b -> DefaultBand.builder()
                                    .withRate(b.rate())
                                    .burstSize(b.burst())
                                    .ofType(Band.Type.NONE)
                                    .build()).collect(Collectors.toList()))
                            .withCellId(config.cellId())
                            .forDevice(deviceId)
                            .build();
        meter.setState(MeterState.ADDED);
        return meter;
    }

    private boolean appendEntryToWriteRequestOrSkip(
            final WriteRequest writeRequest,
            final PiMeterCellHandle handle,
            PiMeterCellConfig configToModify) {

        final TimedEntry<PiMeterCellConfig> configOnDevice = meterMirror.get(handle);

        if (configOnDevice != null && configOnDevice.entry().equals(configToModify)) {
            log.debug("Ignoring re-apply of existing entry: {}", configToModify);
            return true;
        }

        writeRequest.entity(configToModify, UpdateType.MODIFY);

        return false;
    }

    /**
     * P4 meter features builder.
     */
    public class P4RuntimeMeterFeaturesBuilder {
        private final PiMeterModel piMeterModel;
        private DeviceId deviceId;

        private static final long PI_METER_START_INDEX = 0L;
        private static final short PI_METER_MAX_BAND = 2;
        private static final short PI_METER_MAX_COLOR = 3;

        public P4RuntimeMeterFeaturesBuilder(PiMeterModel piMeterModel, DeviceId deviceId) {
            this.piMeterModel = checkNotNull(piMeterModel);
            this.deviceId = deviceId;
        }

        /**
         * To build a MeterFeatures using the PiMeterModel object
         * retrieved from pipeconf.
         *
         * @return the meter features object
         */
        public MeterFeatures build() {
            /*
             * We set the basic values before to extract the other information.
             */
            MeterFeatures.Builder builder = DefaultMeterFeatures.builder()
                    .forDevice(deviceId)
                    // The scope value will be PiMeterId
                    .withScope(MeterScope.of(piMeterModel.id().id()))
                    .withMaxBands(PI_METER_MAX_BAND)
                    .withMaxColors(PI_METER_MAX_COLOR)
                    .withStartIndex(PI_METER_START_INDEX)
                    .withEndIndex(piMeterModel.size() - 1);
            /*
             * Pi meter only support NONE type
             */
            Set<Band.Type> bands = Sets.newHashSet();
            bands.add(Band.Type.NONE);
            builder.withBandTypes(bands);
            /*
             * We extract the supported units;
             */
            Set<Meter.Unit> units = Sets.newHashSet();
            if (piMeterModel.unit() == PiMeterModel.Unit.BYTES) {
                units.add(Meter.Unit.KB_PER_SEC);
            } else if (piMeterModel.unit() == PiMeterModel.Unit.PACKETS) {
                units.add(Meter.Unit.PKTS_PER_SEC);
            }
            builder.withUnits(units);
            /*
             * Burst is supported ?
             */
            builder.hasBurst(true);
            /*
             * Stats are supported ?
             */
            builder.hasStats(false);

            return builder.build();
        }

        /**
         * To build an empty meter features.
         * @param deviceId the device id
         * @return the meter features
         */
        public MeterFeatures noMeterFeatures(DeviceId deviceId) {
            return DefaultMeterFeatures.noMeterFeatures(deviceId);
        }
    }
}
