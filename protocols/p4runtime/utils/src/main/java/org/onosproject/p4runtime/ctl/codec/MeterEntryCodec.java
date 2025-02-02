/*
 * Copyright 2019-present Open Networking Foundation
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

package org.onosproject.p4runtime.ctl.codec;

import org.onosproject.net.pi.model.PiMeterId;
import org.onosproject.net.pi.model.PiPipeconf;
import org.onosproject.net.pi.runtime.PiMeterBand;
import org.onosproject.net.pi.runtime.PiMeterCellConfig;
import org.onosproject.net.pi.runtime.PiMeterCellHandle;
import org.onosproject.net.pi.runtime.PiMeterCellId;
import org.onosproject.p4runtime.ctl.utils.P4InfoBrowser;
import p4.v1.P4RuntimeOuterClass;

/**
 * Codec for P4Runtime MeterEntry.
 */
public final class MeterEntryCodec
        extends AbstractEntityCodec<PiMeterCellConfig, PiMeterCellHandle,
        P4RuntimeOuterClass.MeterEntry, Object> {

    static P4RuntimeOuterClass.MeterConfig getP4Config(PiMeterCellConfig piConfig)
            throws CodecException {
        // The config has no band, we don't have to create a P4RT meter config
        if (piConfig.isDefaultConfig()) {
            return null;
        }
        // If it is not a reset operation, the config must be a modify config and has exactly 2 bands
        if (!piConfig.isModifyConfig()) {
            throw new CodecException("Number of meter bands should be 2 (Modify) or 0 (Reset)");
        }
        final PiMeterBand[] bands = piConfig.meterBands().toArray(new PiMeterBand[0]);
        long cir, cburst, pir, pburst;
        // The band with bigger burst is peak if rate of them is equal.
        if (bands[0].rate() > bands[1].rate() ||
                (bands[0].rate() == bands[1].rate() &&
                        bands[0].burst() >= bands[1].burst())) {
            cir = bands[1].rate();
            cburst = bands[1].burst();
            pir = bands[0].rate();
            pburst = bands[0].burst();
        } else {
            cir = bands[0].rate();
            cburst = bands[0].burst();
            pir = bands[1].rate();
            pburst = bands[1].burst();
        }
        return P4RuntimeOuterClass.MeterConfig.newBuilder()
                .setCir(cir)
                .setCburst(cburst)
                .setPir(pir)
                .setPburst(pburst)
                .build();
    }

    @Override
    protected P4RuntimeOuterClass.MeterEntry encode(
            PiMeterCellConfig piEntity, Object ignored, PiPipeconf pipeconf,
            P4InfoBrowser browser)
            throws P4InfoBrowser.NotFoundException, CodecException {
        final int meterId = browser.meters().getByName(
                piEntity.cellId().meterId().id()).getPreamble().getId();
        P4RuntimeOuterClass.MeterEntry.Builder builder =
            P4RuntimeOuterClass.MeterEntry.newBuilder()
                .setMeterId(meterId)
                .setIndex(P4RuntimeOuterClass.Index.newBuilder()
                                .setIndex(piEntity.cellId().index()).build());
        // We keep the config field unset if it is reset scenario
        P4RuntimeOuterClass.MeterConfig meterConfig = getP4Config(piEntity);
        if (meterConfig != null) {
            builder = builder.setConfig(meterConfig);
        }
        return builder.build();
    }

    @Override
    protected P4RuntimeOuterClass.MeterEntry encodeKey(
            PiMeterCellHandle handle, Object metadata, PiPipeconf pipeconf,
            P4InfoBrowser browser)
            throws P4InfoBrowser.NotFoundException {
        return keyMsgBuilder(handle.cellId(), browser).build();
    }

    @Override
    protected P4RuntimeOuterClass.MeterEntry encodeKey(
            PiMeterCellConfig piEntity, Object metadata, PiPipeconf pipeconf,
            P4InfoBrowser browser)
            throws P4InfoBrowser.NotFoundException {
        return keyMsgBuilder(piEntity.cellId(), browser).build();
    }

    private P4RuntimeOuterClass.MeterEntry.Builder keyMsgBuilder(
            PiMeterCellId cellId, P4InfoBrowser browser)
            throws P4InfoBrowser.NotFoundException {
        final int meterId = browser.meters().getByName(
                cellId.meterId().id()).getPreamble().getId();
        return P4RuntimeOuterClass.MeterEntry.newBuilder()
                .setMeterId(meterId)
                .setIndex(P4RuntimeOuterClass.Index.newBuilder()
                                  .setIndex(cellId.index()).build());
    }

    @Override
    protected PiMeterCellConfig decode(
            P4RuntimeOuterClass.MeterEntry message, Object ignored,
            PiPipeconf pipeconf, P4InfoBrowser browser)
            throws P4InfoBrowser.NotFoundException {
        final String meterName = browser.meters()
                .getById(message.getMeterId())
                .getPreamble()
                .getName();
        PiMeterCellId cellId =
            PiMeterCellId.ofIndirect(PiMeterId.of(meterName), message.getIndex().getIndex());
        // When a field is unset, gRPC (P4RT) will return a default value
        // So, if the meter config is unset, the value of rate and burst will be 0,
        // while 0 is a meaningful value and not equals to UNSET in P4RT.
        // We cannot extract the values directly.
        P4RuntimeOuterClass.MeterConfig p4Config =
            message.hasConfig() ? message.getConfig() : null;

        return getPiMeterCellConfig(cellId, p4Config);
    }

    public static PiMeterCellConfig getPiMeterCellConfig(
            PiMeterCellId cellId, P4RuntimeOuterClass.MeterConfig p4Config) {
        PiMeterCellConfig.Builder builder =
            PiMeterCellConfig.builder().withMeterCellId(cellId);
        if (p4Config != null) {
            builder = builder
                .withMeterBand(new PiMeterBand(p4Config.getCir(), p4Config.getCburst()))
                .withMeterBand(new PiMeterBand(p4Config.getPir(), p4Config.getPburst()));
        }
        return builder.build();
    }
}
