package mqtt.bridge.service;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import mqtt.bridge.configuration.MQTTConfiguration;
import mqtt.bridge.core.C8yAgent;

@Slf4j
@Configuration
@EnableScheduling
@Service
public class MQTTClient {

    private static final String ADDITION_TEST_DUMMY = "_d1";
    private static final int WAIT_PERIOD_MS = 10000;
    private static final String STATUS_MQTT_EVENT_TYPE = "mqtt_status_event";
    private static final String STATUS_SERVICE_EVENT_TYPE = "mqtt_service_event";

    MQTTConfiguration mqttConfiguration;
    private MqttClient mqttClient;

    @Autowired
    private C8yAgent c8yAgent;

    @Autowired
    @Qualifier("cachedThreadPool")
    private ExecutorService cachedThreadPool;

    private Future<Boolean> connectTask;
    private Future<Boolean> initializeTask;

    @Autowired
    private ObjectMapper objectMapper;

    public void submitInitialize() {
        // test if init task is still running, then we don't need to start another task
        log.info("Called initialize(): {}", initializeTask == null || initializeTask.isDone());
        if ((initializeTask == null || initializeTask.isDone())) {
            initializeTask = cachedThreadPool.submit(() -> initialize());
        }
    }

    private boolean initialize() {
        var firstRun = true;
        while ( !MQTTConfiguration.isActive(mqttConfiguration)) {
            if (!firstRun) {
                try {
                    log.info("Retrieving MQTT configuration in {}s ...",
                            WAIT_PERIOD_MS / 1000);
                    Thread.sleep(WAIT_PERIOD_MS);
                } catch (InterruptedException e) {
                    log.error("Error initializing MQTT client: ", e);
                }
            }
            mqttConfiguration = c8yAgent.loadConfiguration();
            firstRun = false;
        }
        return true;
    }

    public void submitConnect() {
        // test if connect task is still running, then we don't need to start another
        // task
        log.info("Called connect(): connectTask.isDone() {}",
                connectTask == null || connectTask.isDone());
        if (connectTask == null || connectTask.isDone()) {
            connectTask = cachedThreadPool.submit(() -> connect());
        }
    }

    private boolean connect() {
        log.info("Establishing the MQTT connection now (phase I), shouldConnect:", shouldConnect());
        if (isConnected()) {
            disconnect();
        }
        var firstRun = true;
        while (!isConnected() && shouldConnect()) {
            log.debug("Establishing the MQTT connection now (phase II): {}, {}", MQTTConfiguration.isValid(mqttConfiguration), MQTTConfiguration.isActive(mqttConfiguration));
            if (!firstRun) {
                try {
                    Thread.sleep(WAIT_PERIOD_MS);
                } catch (InterruptedException e) {
                    log.error("Error on reconnect: ", e);
                }
            }
            try {
                if (MQTTConfiguration.isActive(mqttConfiguration)) {
                    String prefix = mqttConfiguration.useTLS ? "ssl://" : "tcp://";
                    String broker = prefix + mqttConfiguration.mqttHost + ":" + mqttConfiguration.mqttPort;
                    mqttClient = new MqttClient(broker, mqttConfiguration.getClientId() + ADDITION_TEST_DUMMY,
                            new MemoryPersistence());
                    MqttConnectOptions connOpts = new MqttConnectOptions();
                    connOpts.setCleanSession(true);
                    connOpts.setAutomaticReconnect(false);
                    connOpts.setUserName(mqttConfiguration.getUser());
                    connOpts.setPassword(mqttConfiguration.getPassword().toCharArray());
                    mqttClient.connect(connOpts);
                    log.info("Successfully connected to broker {}", mqttClient.getServerURI());
                    c8yAgent.createEvent("Successfully connected to broker " + mqttClient.getServerURI(),
                            STATUS_MQTT_EVENT_TYPE,
                            DateTime.now(), null);
                }
            } catch (MqttException e) {
                log.error("Error on reconnect: ", e);
            }
            firstRun = false;
        }

        try {
            Thread.sleep(WAIT_PERIOD_MS / 30);
        } catch (InterruptedException e) {
            log.error("Error on reconnect: ", e);
        }

        try {
            subscribe("$SYS/#", 0);
        } catch (MqttException e) {
            log.error("Error on reconnect: ", e);
            return false;
        }
        return true;
    }

    private boolean shouldConnect() {
        return !MQTTConfiguration.isValid(mqttConfiguration) || MQTTConfiguration.isActive(mqttConfiguration);
    }

    public boolean isConnected() {
        return mqttClient != null ? mqttClient.isConnected() : false;
    }

    public void disconnect() {
        log.info("Disconnecting from MQTT broker: {}",
                (mqttClient == null ? null : mqttClient.getServerURI()));
        try {
            if (mqttClient.isConnected()) {
                log.debug("Disconnected from MQTT broker I: {}", mqttClient.getServerURI());
                mqttClient.unsubscribe("$SYS");
                mqttClient.disconnect();
                log.debug("Disconnected from MQTT broker II: {}", mqttClient.getServerURI());
            }
        } catch (MqttException e) {
            log.error("Error on disconnecting MQTT Client: ", e);
        }
    }

    public void disconnectFromBroker() {
        mqttConfiguration = c8yAgent.setConfigurationActive(false);
        disconnect();
        sendStatusService();
    }

    public void connectToBroker() {
        mqttConfiguration = c8yAgent.setConfigurationActive(true);
        submitConnect();
        sendStatusService();
    }

    public void subscribe(String topic, Integer qos) throws MqttException {

        log.debug("Subscribing on topic {}", topic);
        c8yAgent.createEvent("Subscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null);
        if (qos != null)
            mqttClient.subscribe(topic, qos);
        else
            mqttClient.subscribe(topic);
        log.debug("Successfully subscribed on topic {}", topic);

    }

    private void unsubscribe(String topic) throws MqttException {
        log.info("Unsubscribing from topic {}", topic);
        c8yAgent.createEvent("Unsubscribing on topic " + topic, STATUS_MQTT_EVENT_TYPE, DateTime.now(), null);
        mqttClient.unsubscribe(topic);
    }

    public MQTTConfiguration getConnectionDetails() {
        return c8yAgent.loadConfiguration();
    }

    public void saveConfiguration(final MQTTConfiguration configuration) {
        c8yAgent.saveConfiguration(configuration);
        disconnect();
        // invalidate broker client
        mqttConfiguration = null;
        submitInitialize();
        submitConnect();
    }


    @Scheduled(fixedRate = 30000)
    public void runHouskeeping() {
        try {
            String statusConnectTask = (connectTask == null ? "stopped"
                    : connectTask.isDone() ? "stopped" : "running");
            String statusInitializeTask = (initializeTask == null ? "stopped" : initializeTask.isDone() ? "stopped" : "running");
            log.info("Status: connectTask {}, initializeTask {}, isConnected {}", statusConnectTask,
                    statusInitializeTask, isConnected());
                    sendStatusService();
        } catch (Exception ex) {
            log.error("Error during house keeping execution: {}", ex);
        }
    }

    private void sendStatusService() {
        ServiceStatus statusService;
        statusService = getServiceStatus();
        c8yAgent.sendStatusService(STATUS_SERVICE_EVENT_TYPE, statusService);
    }

    public ServiceStatus getServiceStatus() {
        ServiceStatus serviceStatus;
        if (isConnected()) {
            serviceStatus = ServiceStatus.connected();
        } else if (MQTTConfiguration.isActive(mqttConfiguration)) {
            serviceStatus = ServiceStatus.activated();
        } else if (MQTTConfiguration.isValid(mqttConfiguration)) {
            serviceStatus = ServiceStatus.configured();
        } else {
            serviceStatus = ServiceStatus.notReady();
        }
        return serviceStatus;
    }

    public Long forwardPayload(String topic, Map<String, Object> payload) throws MqttPersistenceException, MqttException, JsonProcessingException {
        Long result = null;
        byte[] pb = objectMapper.writeValueAsBytes(payload);
        MqttMessage msg = new MqttMessage();
        msg.setPayload(pb);
        msg.setQos(mqttConfiguration.qos.ordinal());
        mqttClient.publish(topic, msg);
        return result;
    }

    public void runOperation(ServiceOperation operation) {
        if (operation.getOperation().equals(Operation.CONNECT)) {
            connectToBroker();
        } else if (operation.getOperation().equals(Operation.DISCONNECT)) {
            disconnectFromBroker();
        }
    }
}
