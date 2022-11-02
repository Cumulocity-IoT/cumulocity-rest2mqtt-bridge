package mqtt.bridge.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import mqtt.bridge.configuration.ConfigurationConnection;
import mqtt.bridge.configuration.ServiceConfiguration;
import mqtt.bridge.core.C8yAgent;

@Slf4j
@Configuration
@EnableScheduling
@Service
public class MQTTClient {

    private static final String ADDITION_TEST_DUMMY = "";
    private static final int WAIT_PERIOD_MS = 10000;
    private static final String STATUS_MQTT_EVENT_TYPE = "mqtt_status_event";
    private static final String STATUS_SERVICE_EVENT_TYPE = "mqtt_service_event";

    private ConfigurationConnection connectionConfiguration;
    private Certificate cert;

    private ServiceConfiguration serviceConfiguration;

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

    @AllArgsConstructor
    @Data
    public static class Certificate {
        private String fingerprint;
        private String certInPemFormat;
    }

    public void submitInitialize() {
        // test if init task is still running, then we don't need to start another task
        log.info("Called initialize(): {}", initializeTask == null || initializeTask.isDone());
        if ((initializeTask == null || initializeTask.isDone())) {
            initializeTask = cachedThreadPool.submit(() -> initialize());
        }
    }

    private boolean initialize() {
        var firstRun = true;
        while (!canConnect()) {
            if (!firstRun) {
                try {
                    log.info("Retrieving MQTT configuration in {}s ...",
                            WAIT_PERIOD_MS / 1000);
                    Thread.sleep(WAIT_PERIOD_MS);
                } catch (InterruptedException e) {
                    log.error("Error initializing MQTT client: ", e);
                }
            }
            connectionConfiguration = c8yAgent.loadConnectionConfiguration();
            if (connectionConfiguration.useSelfSignedCertificate) {
                cert = c8yAgent.loadCertificateByName(connectionConfiguration.nameCertificate);
            }
            serviceConfiguration = c8yAgent.loadServiceConfiguration();
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

    private boolean connect() throws Exception {
        log.info("Establishing the MQTT connection now (phase I), shouldConnect:", shouldConnect());
        if (isConnected()) {
            disconnect();
        }
        // stay in the loop until successful
        boolean successful = false;
        while (!successful) {
            var firstRun = true;
            while (!isConnected() && shouldConnect()) {
                log.info("Establishing the MQTT connection now (phase II): {}, {}",
                        ConfigurationConnection.isValid(connectionConfiguration),
                        canConnect());
                if (!firstRun) {
                    try {
                        Thread.sleep(WAIT_PERIOD_MS);
                    } catch (InterruptedException e) {
                        log.error("Error on reconnect: ", e);
                    }
                }
                try {
                    if (canConnect()) {
                        String prefix = connectionConfiguration.useTLS ? "ssl://" : "tcp://";
                        String broker = prefix + connectionConfiguration.mqttHost + ":"
                                + connectionConfiguration.mqttPort;
                        // mqttClient = new MqttClient(broker, MqttClient.generateClientId(), new
                        // MemoryPersistence());
                        mqttClient = new MqttClient(broker, connectionConfiguration.getClientId() + ADDITION_TEST_DUMMY,
                                new MemoryPersistence());
                        MqttConnectOptions connOpts = new MqttConnectOptions();
                        connOpts.setCleanSession(true);
                        connOpts.setAutomaticReconnect(false);
                        connOpts.setUserName(connectionConfiguration.getUser());
                        connOpts.setPassword(connectionConfiguration.getPassword().toCharArray());
                        if (connectionConfiguration.useSelfSignedCertificate) {
                            log.debug("Using certificate: {}", cert.certInPemFormat);

                            try {
                                KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
                                trustStore.load(null, null);
                                trustStore.setCertificateEntry("Custom CA",
                                        (X509Certificate) CertificateFactory.getInstance("X509")
                                                .generateCertificate(new ByteArrayInputStream(
                                                        cert.certInPemFormat.getBytes(Charset.defaultCharset()))));

                                TrustManagerFactory tmf = TrustManagerFactory
                                        .getInstance(TrustManagerFactory.getDefaultAlgorithm());
                                tmf.init(trustStore);
                                TrustManager[] trustManagers = tmf.getTrustManagers();

                                SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
                                sslContext.init(null, trustManagers, null);
                                SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

                                // where options is the MqttConnectOptions object
                                connOpts.setSocketFactory(sslSocketFactory);
                            } catch (NoSuchAlgorithmException | CertificateException | IOException | KeyStoreException
                                    | KeyManagementException e) {
                                log.error("Exception when configuraing socketFactory for TLS!", e);
                                throw new Exception(e);
                            }
                        }
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
                subscribe("$SYS/#", 0);
            } catch (MqttException e) {
                log.error("Error on reconnect, retrying ... ", e);
            }
            successful = true;

        }

        return true;
    }

    private boolean canConnect() {
        return ConfigurationConnection.isEnabled(connectionConfiguration)
                && (!connectionConfiguration.useSelfSignedCertificate
                        || (connectionConfiguration.useSelfSignedCertificate &&
                                cert != null));
    }

    private boolean shouldConnect() {
        return !ConfigurationConnection.isValid(connectionConfiguration)
                || ConfigurationConnection.isEnabled(connectionConfiguration);
    }

    public boolean isConnected() {
        return mqttClient != null ? mqttClient.isConnected() : false;
    }

    public void disconnect() {
        log.info("Disconnecting from MQTT broker: {}",
                (mqttClient == null ? null : mqttClient.getServerURI()));
        try {
            if (isConnected()) {
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
        connectionConfiguration = c8yAgent.enableConnection(false);
        disconnect();
        sendStatusService();
    }

    public void connectToBroker() {
        connectionConfiguration = c8yAgent.enableConnection(true);
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

    public ConfigurationConnection loadConnectionConfiguration() {
        return c8yAgent.loadConnectionConfiguration();
    }

    public void saveConnectionConfiguration(ConfigurationConnection configuration) {
        c8yAgent.saveConnectionConfiguration(configuration);
        disconnect();
        // invalidate broker client
        connectionConfiguration = null;
        submitInitialize();
        submitConnect();
    }

    public ServiceConfiguration loadServiceConfiguration() {
        return c8yAgent.loadServiceConfiguration();
    }

    public void saveServiceConfiguration(ServiceConfiguration configuration) {
        serviceConfiguration = configuration;
        c8yAgent.saveServiceConfiguration(configuration);
    }

    @Scheduled(fixedRate = 30000)
    public void runHouskeeping() {
        try {
            String statusConnectTask = (connectTask == null ? "stopped"
                    : connectTask.isDone() ? "stopped" : "running");
            String statusInitializeTask = (initializeTask == null ? "stopped"
                    : initializeTask.isDone() ? "stopped" : "running");
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
        } else if (ConfigurationConnection.isEnabled(connectionConfiguration)) {
            serviceStatus = ServiceStatus.activated();
        } else if (ConfigurationConnection.isValid(connectionConfiguration)) {
            serviceStatus = ServiceStatus.configured();
        } else {
            serviceStatus = ServiceStatus.notReady();
        }
        return serviceStatus;
    }

    public Long forwardPayload(String topic, Map<String, Object> payload)
            throws MqttPersistenceException, MqttException, JsonProcessingException {
        if (serviceConfiguration.logPayload) {
            log.info("Forward payload {} to topic {}", payload, topic);
        }
        Long result = null;
        byte[] pb = objectMapper.writeValueAsBytes(payload);
        MqttMessage msg = new MqttMessage();
        msg.setPayload(pb);
        msg.setQos(connectionConfiguration.qos.ordinal());
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
