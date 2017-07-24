/*
 * Copyright 2014 Open Networking Laboratory
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
//CHECKSTYLE:OFF
package net.fnlab;


import org.apache.felix.scr.annotations.*;
import org.onlab.packet.*;
import org.onosproject.cluster.ControllerNode;
import org.onosproject.core.Application;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.*;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.*;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FilteringObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.TopologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Set;


/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class inbandmanagement {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

//    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
//    protected DeviceService deviceServiceService;

    //protected ControllerNode controllerNode;

    private ApplicationId appId;

    private PacketProcessor packetProcessor;

    private DeviceId firstDeviceId;

    private boolean firstStepHaveDone;

    private ConnectPoint switchArray[] = new ConnectPoint[100];

    private int switchCounter = 0;

    @Activate
    public void activate() {
        //register app
        appId = coreService.registerApplication("net.fnlab.inbandmanagement");
        firstStepHaveDone = false;
        //add packet processor
        packetProcessor = new InbandPacketProcessor();
        packetService.addProcessor(packetProcessor,PacketProcessor.director(2));

        log.info("huanhuan Started inbandcontrol",appId.id());
    }

    @Deactivate
    public void deactivate() {
        //remove rules set by this APP
        flowRuleService.removeFlowRulesById(appId);
        //set processor null
        packetProcessor = null;
        log.info("stopped");
    }

    private boolean isControlpacket(Ethernet eth) {
        short type = eth.getEtherType();
        return type == Ethernet.TYPE_LLDP || type == Ethernet.TYPE_BSN;
    }

//    private void inbandManagementFirstStep(InboundPacket pkt) {
//        installRuleUpToController(pkt, pkt.receivedFrom().port());
//    }

    private void inbandManagementUpToController(InboundPacket pkt) {
        Set<Path>paths = topologyService.getPaths(topologyService.currentTopology(),
                pkt.receivedFrom().deviceId(),
                firstDeviceId);
        Path path = paths.iterator().next();
        //set flow
        installRuleUpToController(pkt, path.src().port());
    }

    private void inbandManagementDownFromController(InboundPacket pkt) {
        Set<Path>paths = topologyService.getPaths(topologyService.currentTopology(),
                firstDeviceId,
                pkt.receivedFrom().deviceId());
        Path path = paths.iterator().next();
        for (Link link : path.links()){
            installRuleDownFromController(pkt, link.src().deviceId(),link.src().port(), pkt.parsed().getSourceMAC());
        }
    }

    private void installRuleDownFromController(InboundPacket pkt, DeviceId deviceId, PortNumber portNumber, MacAddress macAddress) {
        IPv4 ippkt = (IPv4) pkt.parsed().getPayload();
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();

        selectorBuilder.matchEthDst(macAddress)
                .matchIPProtocol(IPv4.PROTOCOL_TCP)
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPDst(IpPrefix.valueOf(ippkt.getSourceAddress(),32))
                .matchTcpSrc(TpPort.tpPort(6633));

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(portNumber)
                .build();

        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selectorBuilder.build())
                .withTreatment(treatment)
                .withPriority(61000)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .fromApp(appId)
                .makePermanent()
                .add();

        flowObjectiveService.forward(deviceId,forwardingObjective);
        log.info("huanhuan finish installrull down");
    }

    private void installRuleUpToController(InboundPacket pkt, PortNumber portNumber) {
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();

        selectorBuilder.matchIPProtocol(IPv4.PROTOCOL_TCP)
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchTcpDst(TpPort.tpPort(6633));

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(portNumber)
                .build();

        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selectorBuilder.build())
                .withTreatment(treatment)
                .withPriority(60000)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .fromApp(appId)
                .makePermanent()
                .add();
        flowObjectiveService.forward(pkt.receivedFrom().deviceId(),forwardingObjective);
        log.info("huanhuan finish installrull");
    }

    private class InbandPacketProcessor implements PacketProcessor{
        @Override
        public void process(PacketContext context){
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            log.info("huanhuan  "+pkt.receivedFrom().deviceId().toString());

            if(!(Arrays.asList(switchArray).contains(pkt.receivedFrom().deviceId()))){
                switchArray[++switchCounter] = pkt.receivedFrom();
            }

            //if the packet is inband request
            MacAddress dstMac = ethPkt.getDestinationMAC();
            log.info("huanhuan  "+ethPkt.getDestinationMAC().toString());
            if(!(dstMac.toString().equals("00:00:00:FF:FF:FF"))){
                return;
            }
            //if the packet is from the switch  that directly connect to the controller
            if(pkt.receivedFrom().deviceId().toString().equals("of:0000000000000011")){
                log.info("huanhuan firststep");
                //inbandManagementFirstStep(pkt);
                firstDeviceId = pkt.receivedFrom().deviceId();
                firstStepHaveDone = true;
                return;
            }
            if ((firstStepHaveDone) && (!(pkt.receivedFrom().deviceId().toString().equals("of:0000000000000011")))) {
                //other switches' inband request
                //up to the controller
                inbandManagementUpToController(pkt);

                //down from the controller
                inbandManagementDownFromController(pkt);
                return;
            }
        }

    }

}
