/**
 * Copyright (c) 2014 openHAB UG (haftungsbeschraenkt) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.irobot.handler;

import static org.openhab.binding.irobot.IRobotBindingConstants.*;

import java.io.IOException;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Hashtable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.irobot.internal.IdentProtocol;
import org.openhab.binding.irobot.internal.IdentProtocol.IdentData;
import org.openhab.binding.irobot.internal.MQTTProtocol;
import org.openhab.binding.irobot.internal.MQTTProtocol.Schedule;
import org.openhab.binding.irobot.internal.RawMQTT;
import org.openhab.binding.irobot.roomba.RoombaConfiguration;
import org.openhab.binding.irobot.roomba.RoombaMqttBrokerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;

/**
 * The {@link RoombaHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author hkuhn42 - Initial contribution
 */
public class RoombaHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(RoombaHandler.class);
    private final ExecutorService singleThread = Executors.newSingleThreadExecutor();
    private static final int reconnectDelay = 5; // In seconds
    private @Nullable Future<?> reconnectReq;
    private RoombaConfiguration config;
    private String blid = null;
    protected RoombaMqttBrokerConnection connection;
    private Hashtable<String, State> lastState = new Hashtable<String, State>();
    private Schedule lastSchedule = null;
    private boolean auto_passes = true;
    private Boolean two_passes = null;
    private boolean carpet_boost = true;
    private Boolean vac_high = null;
    private boolean isPaused = false;

    public RoombaHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        logger.trace("initialize()");
        config = getConfigAs(RoombaConfiguration.class);
        updateStatus(ThingStatus.UNKNOWN);
        connect();
    }

    @Override
    public void dispose() {
        logger.trace("dispose()");

        singleThread.execute(() -> {
            if (reconnectReq != null) {
                reconnectReq.cancel(false);
                reconnectReq = null;
            }

            if (connection != null) {
                connection.stop();
                connection = null;
            }
        });
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String ch = channelUID.getId();
        if (command instanceof RefreshType) {
            State value = lastState.get(ch);

            if (value != null) {
                updateState(ch, value);
            }

            return;
        }

        if (ch.equals(CHANNEL_COMMAND)) {
            if (command instanceof StringType) {
                String cmd = command.toString();

                if (cmd.equals(CMD_CLEAN)) {
                    cmd = isPaused ? "resume" : "start";
                }

                sendRequest(new MQTTProtocol.CommandRequest(cmd));
            }
        } else if (ch.startsWith(CHANNEL_SCHED_SWITCH_PREFIX)) {
            MQTTProtocol.Schedule schedule = lastSchedule;

            // Schedule can only be updated in a bulk, so we have to store current
            // schedule and modify components.
            if (command instanceof OnOffType && schedule != null && schedule.cycle != null) {
                for (int i = 0; i < CHANNEL_SCHED_SWITCH.length; i++) {
                    if (ch.equals(CHANNEL_SCHED_SWITCH[i])) {
                        MQTTProtocol.Schedule newSchedule = new MQTTProtocol.Schedule(schedule.cycle);

                        newSchedule.enableCycle(i, command.equals(OnOffType.ON));
                        sendSchedule(newSchedule);
                        break;
                    }
                }
            }
        } else if (ch.equals(CHANNEL_SCHEDULE)) {
            if (command instanceof DecimalType) {
                int bitmask = ((DecimalType) command).intValue();
                sendSchedule(new MQTTProtocol.Schedule(bitmask));
            }
        } else if (ch.equals(CHANNEL_EDGE_CLEAN)) {
            if (command instanceof OnOffType) {
                sendDelta(new MQTTProtocol.OpenOnly(command.equals(OnOffType.OFF)));
            }
        } else if (ch.equals(CHANNEL_ALWAYS_FINISH)) {
            if (command instanceof OnOffType) {
                sendDelta(new MQTTProtocol.BinPause(command.equals(OnOffType.OFF)));
            }
        } else if (ch.equals(CHANNEL_POWER_BOOST)) {
            if (command instanceof StringType) {
                String cmd = command.toString();
                sendDelta(new MQTTProtocol.PowerBoost(cmd.equals(BOOST_AUTO), cmd.equals(BOOST_PERFORMANCE)));
            }
        } else if (ch.equals(CHANNEL_CLEAN_PASSES)) {
            if (command instanceof StringType) {
                String cmd = command.toString();
                sendDelta(new MQTTProtocol.CleanPasses(!cmd.equals(PASSES_AUTO), cmd.equals(PASSES_2)));
            }
        }
    }

    private void sendSchedule(MQTTProtocol.Schedule schedule) {
        sendDelta(new MQTTProtocol.CleanSchedule(schedule));
    }

    private void sendDelta(MQTTProtocol.StateValue state) {
        sendRequest(new MQTTProtocol.DeltaRequest(state));
    }

    private void sendRequest(MQTTProtocol.Request request) {
        String json = new Gson().toJson(request);

        logger.trace("Sending {}: {}", request.getTopic(), json);
        connection.publish(request.getTopic(), json.getBytes());
    }

    private void connect() {
        // In order not to mess up our connection state we need to make sure
        // that any two calls are never running concurrently. We use
        // singleThreadExecutorService for this purpose
        singleThread.execute(() -> {
            String error = null;

            logger.info("Connecting to {}", config.ipaddress);

            try {
                InetAddress host = InetAddress.getByName(config.ipaddress);

                if (blid == null) {
                    DatagramSocket identSocket = IdentProtocol.sendRequest(host);
                    DatagramPacket identPacket = IdentProtocol.receiveResponse(identSocket);

                    identSocket.close();
                    IdentProtocol.IdentData ident;

                    try {
                        ident = IdentProtocol.decodeResponse(identPacket);
                    } catch (JsonParseException e) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "Malformed IDENT response");
                        return;
                    }

                    if (ident.ver < IdentData.MIN_SUPPORTED_VERSION) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                "Unsupported version " + ident.ver);
                        return;
                    }

                    if (!ident.product.equals(IdentData.PRODUCT_ROOMBA)) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                "Not a Roomba: " + ident.product);
                        return;
                    }

                    blid = ident.blid;
                }

                logger.debug("BLID is: {}", blid);

                if (!config.havePassword()) {
                    RawMQTT mqtt;

                    try {
                        mqtt = new RawMQTT(host, 8883);
                    } catch (KeyManagementException | NoSuchAlgorithmException e1) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e1.toString());
                        return; // This is internal system error, no retry
                    }

                    mqtt.requestPassword();
                    RawMQTT.Packet response = mqtt.readPacket();
                    mqtt.close();

                    if (response != null && response.isValidPasswdPacket()) {
                        RawMQTT.PasswdPacket passwdPacket = new RawMQTT.PasswdPacket(response);

                        config.password = passwdPacket.getPassword();
                        if (config.havePassword()) {
                            Configuration configuration = editConfiguration();

                            configuration.put(RoombaConfiguration.FIELD_PASSWORD, config.password);
                            updateConfiguration(configuration);
                        }
                    }
                }

                if (!config.havePassword()) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                            "Authentication on the robot is required");
                    scheduleReconnect();
                    return;
                }

                logger.debug("Password is: " + config.password);

                // BLID is used as both client ID and username. The name of BLID also came from Roomba980-python
                connection = new RoombaMqttBrokerConnection(config.ipaddress, blid, this);
                connection.start(blid, config.password);

            } catch (IOException e) {
                error = e.toString();
            }

            if (error != null) {
                logger.error(error);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, error);
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        reconnectReq = scheduler.schedule(() -> {
            connect();
        }, reconnectDelay, TimeUnit.SECONDS);
    }

    public void onConnected() {
        updateStatus(ThingStatus.ONLINE);
    }

    public void onDisconnected(Throwable error) {
        String message = error.getMessage();

        logger.error("MQTT connection failed: {}", message);
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, message);
        scheduleReconnect();
    }

    public void processMessage(String topic, byte[] payload) {
        String jsonStr = new String(payload);
        logger.trace("Got topic {} data {}", topic, jsonStr);

        try {
            // We are not consuming all the fields, so we have to create the reader explicitly
            // If we use fromJson(String) or fromJson(java.util.reader), it will throw
            // "JSON not fully consumed" exception, because not all the reader's content has been
            // used up. We want to avoid that also for compatibility reasons because newer iRobot
            // versions may add fields.
            JsonReader jsonReader = new JsonReader(new StringReader(jsonStr));
            MQTTProtocol.StateMessage msg = new Gson().fromJson(jsonReader, IdentData.class);

            // Since all the fields are in fact optional, and a single message never
            // contains all of them, we have to check presence of each individually
            if (msg.state == null || msg.state.reported == null) {
                return;
            }

            MQTTProtocol.GenericState reported = msg.state.reported;

            if (reported.cleanMissionStatus != null) {
                String cycle = reported.cleanMissionStatus.cycle;
                String phase = reported.cleanMissionStatus.phase;
                String command;

                if (cycle.equals("none")) {
                    command = CMD_STOP;
                } else {
                    switch (phase) {
                        case "stop":
                        case "stuck": // CHECKME: could also be equivalent to "stop" command
                        case "pause": // Never observed in Roomba 930
                            command = CMD_PAUSE;
                            break;
                        case "hmUsrDock":
                        case "dock": // Never observed in Roomba 930
                            command = CMD_DOCK;
                            break;
                        default:
                            command = cycle; // "clean" or "spot"
                            break;
                    }
                }

                isPaused = command.equals(CMD_PAUSE);

                reportString(CHANNEL_CYCLE, cycle);
                reportString(CHANNEL_PHASE, phase);
                reportString(CHANNEL_COMMAND, command);
                reportString(CHANNEL_ERROR, String.valueOf(reported.cleanMissionStatus.error));
            }

            if (reported.batPct != null) {
                reportInt(CHANNEL_BATTERY, reported.batPct);
            }

            if (reported.bin != null) {
                String binStatus;

                // The bin cannot be both full and removed simultaneously, so let's
                // encode it as a single value
                if (!reported.bin.present) {
                    binStatus = BIN_REMOVED;
                } else if (reported.bin.full) {
                    binStatus = BIN_FULL;
                } else {
                    binStatus = BIN_OK;
                }

                reportString(CHANNEL_BIN, binStatus);
            }

            if (reported.signal != null) {
                reportInt(CHANNEL_RSSI, reported.signal.rssi);
                reportInt(CHANNEL_SNR, reported.signal.snr);
            }

            if (reported.cleanSchedule != null) {
                MQTTProtocol.Schedule schedule = reported.cleanSchedule;

                if (schedule.cycle != null) {
                    int binary = 0;

                    for (int i = 0; i < CHANNEL_SCHED_SWITCH.length; i++) {
                        boolean on = schedule.cycleEnabled(i);

                        reportSwitch(CHANNEL_SCHED_SWITCH[i], on);
                        if (on) {
                            binary |= (1 << i);
                        }
                    }

                    reportInt(CHANNEL_SCHEDULE, binary);
                }

                lastSchedule = schedule;
            }

            if (reported.openOnly != null) {
                reportSwitch(CHANNEL_EDGE_CLEAN, !reported.openOnly);
            }

            if (reported.binPause != null) {
                reportSwitch(CHANNEL_ALWAYS_FINISH, !reported.binPause);
            }

            // To make the life more interesting, paired values may not appear together in the
            // same message, so we have to keep track of current values.
            if (reported.carpetBoost != null) {
                carpet_boost = reported.carpetBoost;
                if (reported.carpetBoost) {
                    // When set to true, overrides vacHigh
                    reportString(CHANNEL_POWER_BOOST, BOOST_AUTO);
                } else if (vac_high != null) {
                    reportVacHigh();
                }
            }

            if (reported.vacHigh != null) {
                vac_high = reported.vacHigh;
                if (!carpet_boost) {
                    // Can be overridden by "carpetBoost":true
                    reportVacHigh();
                }
            }

            if (reported.noAutoPasses != null) {
                auto_passes = !reported.noAutoPasses;
                if (!reported.noAutoPasses) {
                    // When set to false, overrides twoPass
                    reportString(CHANNEL_CLEAN_PASSES, PASSES_AUTO);
                } else if (two_passes != null) {
                    reportTwoPasses();
                }
            }

            if (reported.twoPass != null) {
                two_passes = reported.twoPass;
                if (!auto_passes) {
                    // Can be overridden by "noAutoPasses":false
                    reportTwoPasses();
                }
            }

            reportProperty(Thing.PROPERTY_FIRMWARE_VERSION, reported.softwareVer);
            reportProperty("navSwVer", reported.navSwVer);
            reportProperty("wifiSwVer", reported.wifiSwVer);
            reportProperty("mobilityVer", reported.mobilityVer);
            reportProperty("bootloaderVer", reported.bootloaderVer);
            reportProperty("umiVer", reported.umiVer);
        } catch (JsonParseException e) {
            logger.error("Failed to parse JSON message from {}: {}", config.ipaddress, e);
            logger.error("Raw contents: {}", payload);
        }
    }

    private void reportVacHigh() {
        reportString(CHANNEL_POWER_BOOST, vac_high ? BOOST_PERFORMANCE : BOOST_ECO);
    }

    private void reportTwoPasses() {
        reportString(CHANNEL_CLEAN_PASSES, two_passes ? PASSES_2 : PASSES_1);
    }

    private void reportString(String channel, String str) {
        reportState(channel, StringType.valueOf(str));
    }

    private void reportInt(String channel, int n) {
        reportState(channel, new DecimalType(n));
    }

    private void reportSwitch(String channel, boolean s) {
        reportState(channel, OnOffType.from(s));
    }

    private void reportState(String channel, State value) {
        lastState.put(channel, value);
        updateState(channel, value);
    }

    private void reportProperty(String property, String value) {
        if (value != null) {
            updateProperty(property, value);
        }
    }
}
