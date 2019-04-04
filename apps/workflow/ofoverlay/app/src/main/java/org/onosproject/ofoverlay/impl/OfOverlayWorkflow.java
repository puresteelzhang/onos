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
package org.onosproject.ofoverlay.impl;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onosproject.net.device.DeviceService;
import org.onosproject.workflow.api.ImmutableListWorkflow;
import org.onosproject.workflow.api.Workflow;
import org.onosproject.workflow.api.WorkflowExecutionService;
import org.onosproject.workflow.api.WorkflowStore;
import org.onosproject.workflow.api.WorkplaceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.ScheduledExecutorService;

import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static org.onlab.util.Tools.groupedThreads;

/**
 * Class for Open-flow overlay configuration workflow.
 */
@Component(immediate = true)
public class OfOverlayWorkflow {

    private static final Logger log = LoggerFactory.getLogger(OfOverlayWorkflow.class);

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected WorkflowStore workflowStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected WorkplaceStore workplaceStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected WorkflowExecutionService workflowExecutionService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    private ScheduledExecutorService eventMapTriggerExecutor;

    @Activate
    public void activate() {
        log.info("Activated");

        eventMapTriggerExecutor = newSingleThreadScheduledExecutor(
                groupedThreads("onos/of-overlay", "eventmap-trigger-executor"));

        registerWorkflows();

    }

    @Deactivate
    public void deactivate() {
        log.info("Deactivated");
    }

    /**
     * Registers workflows.
     */
    private void registerWorkflows() {
        // registering class-loader
        workflowStore.registerLocal(this.getClass().getClassLoader());

        // registering new workflow definition
        URI uri = URI.create("of-overlay.workflow-nova");
        Workflow workflow = ImmutableListWorkflow.builder()
                .id(uri)
                //.attribute(WorkflowAttribute.REMOVE_AFTER_COMPLETE)
                .chain(Ovs.CreateOvsdbDevice.class.getName())
                .chain(Ovs.UpdateOvsVersion.class.getName())
                .chain(Ovs.UpdateOverlayBridgeId.class.getName())
                .chain(Ovs.CreateOverlayBridge.class.getName())
                .chain(Ovs.UpdateUnderlayBridgeId.class.getName())
                .chain(Ovs.CreateUnderlayBridge.class.getName())
                .chain(Ovs.CreateOverlayBridgeVxlanPort.class.getName())
                .chain(Ovs.AddPhysicalPortsOnUnderlayBridge.class.getName())
                .chain(Ovs.ConfigureUnderlayBridgeLocalIp.class.getName())
                .build();
        workflowStore.register(workflow);

        // registering new workflow definition based on multi-event handling
        uri = URI.create("of-overlay.workflow-nova-multiEvent-test");
        workflow = ImmutableListWorkflow.builder()
                .id(uri)
                //.attribute(WorkflowAttribute.REMOVE_AFTER_COMPLETE)
                .chain(Ovs.CreateOvsdbDevice.class.getName())
                .chain(Ovs.UpdateOvsVersion.class.getName())
                .chain(Ovs.UpdateOverlayBridgeId.class.getName())
                .chain(Ovs.CreateOverlayBridgeMultiEvent.class.getName())
                .chain(Ovs.UpdateUnderlayBridgeId.class.getName())
                .chain(Ovs.CreateUnderlayBridge.class.getName())
                .chain(Ovs.CreateOverlayBridgeVxlanPort.class.getName())
                .chain(Ovs.AddPhysicalPortsOnUnderlayBridge.class.getName())
                .chain(Ovs.ConfigureUnderlayBridgeLocalIp.class.getName())
                .build();
        workflowStore.register(workflow);

        uri = URI.create("of-overlay.clean-workflow-nova");
        workflow = ImmutableListWorkflow.builder()
                .id(uri)
                //.attribute(WorkflowAttribute.REMOVE_AFTER_COMPLETE)
                .chain(Ovs.DeleteOverlayBridgeConfig.class.getName())
                .chain(Ovs.RemoveOverlayBridgeOfDevice.class.getName())
                .chain(Ovs.DeleteUnderlayBridgeConfig.class.getName())
                .chain(Ovs.RemoveUnderlayBridgeOfDevice.class.getName())
                .chain(Ovs.RemoveOvsdbDevice.class.getName())
                .build();
        workflowStore.register(workflow);

        uri = URI.create("of-overlay.clean-workflow-nova-waitAll-Bridge-Del");
        workflow = ImmutableListWorkflow.builder()
                .id(uri)
                //.attribute(WorkflowAttribute.REMOVE_AFTER_COMPLETE)
                .chain(Ovs.DeleteOverlayBridgeConfig.class.getName())
                .chain(Ovs.DeleteUnderlayBridgeConfig.class.getName())
                .chain(Ovs.RemoveBridgeOfDevice.class.getName())
                .chain(Ovs.RemoveOvsdbDevice.class.getName())
                .build();
        workflowStore.register(workflow);

        uri = URI.create("of-overlay.workflow-ovs-leaf");
        workflow = ImmutableListWorkflow.builder()
                .id(uri)
                .chain(Ovs.CreateOvsdbDevice.class.getName())
                .chain(Ovs.UpdateOvsVersion.class.getName())
                .chain(Ovs.UpdateUnderlayBridgeId.class.getName())
                .chain(Ovs.CreateUnderlayBridge.class.getName())
                .chain(Ovs.AddPhysicalPortsOnUnderlayBridge.class.getName())
                .build();
        workflowStore.register(workflow);

        uri = URI.create("of-overlay.workflow-ovs-spine");
        workflow = ImmutableListWorkflow.builder()
                .id(uri)
                .chain(Ovs.CreateOvsdbDevice.class.getName())
                .chain(Ovs.UpdateOvsVersion.class.getName())
                .chain(Ovs.UpdateUnderlayBridgeId.class.getName())
                .chain(Ovs.CreateUnderlayBridge.class.getName())
                .chain(Ovs.AddPhysicalPortsOnUnderlayBridge.class.getName())
                .build();
        workflowStore.register(workflow);

        deviceService.addListener(
                event -> {
                    // trigger EventTask for DeviceEvent
                    eventMapTriggerExecutor.submit(
                            () -> workflowExecutionService.eventMapTrigger(
                                    event,
                                    // event hint supplier
                                    (ev) -> {
                                        if (ev == null || ev.subject() == null) {
                                            return null;
                                        }
                                        String hint = event.subject().id().toString();
                                        log.debug("hint: {}", hint);
                                        return hint;
                                    }
                            )
                    );
                }
        );

    }

}
