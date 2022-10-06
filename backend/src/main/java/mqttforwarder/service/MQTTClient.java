package mqttforwarder.service;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import mqttforwarder.configuration.MQTTConfiguration;
import mqttforwarder.core.C8yAgent;

@Slf4j
@Configuration
@EnableScheduling
@Service
public class MQTTClient {

    public static final Long KEY_MONITORING_UNSPECIFIED = -1L;
    private static final String STATUS_MQTT_EVENT_TYPE = "mqtt_status_event";
    private static final String STATUS_MAPPING_EVENT_TYPE = "mqtt_mapping_event";
    private static final String STATUS_SERVICE_EVENT_TYPE = "mqtt_service_event";

    MQTTConfiguration mqttConfiguration;
    private MqttClient mqttClient;

    @Autowired
    private C8yAgent c8yAgent;

    private ExecutorService newCachedThreadPool = Executors.newCachedThreadPool();

    private Future reconnectTask;
    private Future initTask;

    private boolean initilized = false;

    @Autowired
    private ObjectMapper objectMapper;

    private void runInit() {
        while (!isInitilized()) {
            log.info("Try to retrieve MQTT connection configuration now ...");
            mqttConfiguration = c8yAgent.loadConfiguration();
            log.info("Configuration found: {}", mqttConfiguration);
            if (mqttConfiguration != null && mqttConfiguration.active) {
                try {
                    String prefix = mqttConfiguration.useTLS ? "ssl://" : "tcp://";
                    String broker = prefix + mqttConfiguration.mqttHost + ":" + mqttConfiguration.mqttPort;
                    mqttClient = new MqttClient(broker, mqttConfiguration.getClientId(),
                            new MemoryPersistence());
                    setInitilized(true);
                    log.info("Connecting to MQTT Broker {}", broker);
                } catch (HttpServerErrorException e) {
                    log.error("Failed to authenticate to MQTT broker", e);

                } catch (MqttException e) {
                    log.error("Failed to connect to MQTT broker", e);
                }
            }

            try {
                log.info("Try to retrieve MQTT connection configuration in 30s, active: {}...", mqttConfiguration.active);
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                log.error("Error on reconnect: ", e);
            }

        }
    }

    public void init() {
        // test if init task is still running, then we don't need to start another task
        log.info("Called init(): {}", initTask == null ? false : initTask.isDone());
        if ((initTask != null && initTask.isDone()) || initTask == null) {
            initTask = newCachedThreadPool.submit(() -> runInit());
        }
    }

    private void runReconnect() {
        // subscriptionsService.runForTenant(c8yAgent.tenant, () -> {
        log.info("Try to reestablish the MQTT connection now I");
        disconnect();
        while (!isConnected()) {
            log.debug("Try to reestablish the MQTT connection now II");
            init();
            try {
                connect();
            } catch (MqttException e) {
                log.error("Error on reconnect: ", e);
            }

            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                log.error("Error on reconnect: ", e);
            }
        }
        // });

    }

    public void reconnect() {
        // test if reconnect task is still running, then we don't need to start another
        // task
        log.info("Called reconnect(): {}",
                reconnectTask == null ? false : reconnectTask.isDone());
        if ((reconnectTask != null && reconnectTask.isDone()) || reconnectTask == null) {
            reconnectTask = newCachedThreadPool.submit(() -> {
                runReconnect();
            });
        }
    }

    private void connect() throws MqttException {
        if (isConnectionConfigured() && !isConnected()) {
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            connOpts.setAutomaticReconnect(false);
            connOpts.setUserName(mqttConfiguration.getUser());
            connOpts.setPassword(mqttConfiguration.getPassword().toCharArray());
            mqttClient.connect(connOpts);
            log.info("Successfully connected to Broker {}", mqttClient.getServerURI());
            c8yAgent.createEvent("Successfully connected to Broker " + mqttClient.getServerURI(),
                    STATUS_MQTT_EVENT_TYPE,
                    DateTime.now(), null);
        }
    }

    public boolean isConnected() {
        return mqttClient != null ? mqttClient.isConnected() : false;
    }

    public void disconnect() {
        log.info("Disconnecting from MQTT Broker: {}, {}", (mqttClient == null ? null : mqttClient.getServerURI()),
                isInitilized());
        try {
            if (isInitilized() && mqttClient != null && mqttClient.isConnected()) {
                log.debug("Disconnected from MQTT Broker I: {}", mqttClient.getServerURI());
                mqttClient.unsubscribe("#");
                mqttClient.unsubscribe("$SYS");
                mqttClient.disconnect();
                log.debug("Disconnected from MQTT Broker II: {}", mqttClient.getServerURI());
            }
        } catch (MqttException e) {
            log.error("Error on disconnecting MQTT Client: ", e);
        }
    }

    public void disconnectFromBroker() {
        mqttConfiguration = c8yAgent.setConfigurationActive(false);
        disconnect();
    }

    public void connectToBroker() {
        mqttConfiguration = c8yAgent.setConfigurationActive(true);
        reconnect();
    }

    public MQTTConfiguration getConnectionDetails() {
        return c8yAgent.loadConfiguration();
    }

    public void saveConfiguration(final MQTTConfiguration configuration) {
        c8yAgent.saveConfiguration(configuration);
        // invalidate broker client
        setInitilized(false);
        reconnect();
    }

    public boolean isConnectionConfigured() {
        return (mqttConfiguration != null) && !StringUtils.isEmpty(mqttConfiguration.mqttHost) &&
                !(mqttConfiguration.mqttPort == 0) &&
                !StringUtils.isEmpty(mqttConfiguration.user) &&
                !StringUtils.isEmpty(mqttConfiguration.password) &&
                !StringUtils.isEmpty(mqttConfiguration.clientId);
    }

    public boolean isConnectionActicated() {
        return (mqttConfiguration != null) && mqttConfiguration.active;
    }

    private boolean isInitilized() {
        return initilized;
    }

    private void setInitilized(boolean init) {
        initilized = init;
    }

    @Scheduled(fixedRate = 30000)
    public void runHouskeeping() {
        try {
            String statusReconnectTask = (reconnectTask == null ? "stopped"
                    : reconnectTask.isDone() ? "stopped" : "running");
            String statusInitTask = (initTask == null ? "stopped" : initTask.isDone() ? "stopped" : "running");
            log.info("Status: reconnectTask {}, initTask {}, isConnected {}", statusReconnectTask,
                    statusInitTask, isConnected());
            sendStatusConfiguration();
        } catch (Exception ex) {
            log.error("Error during house keeping execution: {}", ex);
        }
    }

    private void sendStatusConfiguration() {
        ServiceStatus serviceStatus;
        if (isConnected()) {
            serviceStatus = ServiceStatus.connected();
        } else if (isConnectionActicated()) {
            serviceStatus = ServiceStatus.activated();
        } else if (isConnectionConfigured()) {
            serviceStatus = ServiceStatus.configured();
        } else {
            serviceStatus = ServiceStatus.notReady();
        }
        c8yAgent.sendStatusConfiguration(STATUS_SERVICE_EVENT_TYPE, serviceStatus);
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
