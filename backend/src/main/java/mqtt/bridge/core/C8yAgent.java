package mqtt.bridge.core;

import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import javax.annotation.PreDestroy;

import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.Agent;
import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.rest.representation.tenant.auth.TrustedCertificateRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.identity.IdentityApi;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.fasterxml.jackson.core.JsonProcessingException;

import c8y.IsDevice;
import lombok.extern.slf4j.Slf4j;
import mqtt.bridge.configuration.ConfigurationService;
import mqtt.bridge.configuration.ServiceConfiguration;
import mqtt.bridge.configuration.ConfigurationConnection;
import mqtt.bridge.service.MQTTClient;
import mqtt.bridge.service.ServiceStatus;

@Slf4j
@Service
public class C8yAgent {

    @Autowired
    private EventApi eventApi;

    @Autowired
    private InventoryApi inventoryApi;

    @Autowired
    private IdentityApi identityApi;

    @Autowired
    private MicroserviceSubscriptionsService subscriptionsService;

    @Autowired
    private MQTTClient mqttClient;

    @Autowired
    private ConfigurationService configurationService;

    private ManagedObjectRepresentation  agentRepresentation;
    ;

    private final String AGENT_ID = "MQTT_BRIDGE_SERVICE";
    private final String AGENT_NAME = "REST 2 MQTT Bridge Service";

    public String tenant;

    private MicroserviceCredentials credentials;

    @EventListener
    public void initialize(MicroserviceSubscriptionAddedEvent event) {
        tenant = event.getCredentials().getTenant();
        credentials = event.getCredentials();
        log.info("Event received for Tenant {}", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

        /* Connecting to Cumulocity */
        subscriptionsService.runForTenant(tenant, () -> {
            // register agent
            ExternalIDRepresentation agentIdRepresentation = null;
            try {
                agentIdRepresentation = getExternalId(AGENT_ID, null);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            if (agentIdRepresentation != null) {
                log.info("Agent with ID {} already exists {}", AGENT_ID,
                        agentIdRepresentation);
                agentRepresentation = agentIdRepresentation.getManagedObject();
            } else {
                ManagedObjectRepresentation agent = new ManagedObjectRepresentation();
                agent.setName(AGENT_NAME);
                agent.set(new Agent());
                agent.set(new IsDevice());
                agentRepresentation = inventoryApi.create(agent);
                log.info("Agent has been created with ID {}", agentRepresentation.getId());
                ExternalIDRepresentation externalAgentId = createExternalID(agentRepresentation, AGENT_ID, "c8y_Serial");
                log.info("ExternalId created: {}", externalAgentId.getExternalId());
            }
        });

        try {
            mqttClient.submitInitialize();
            mqttClient.submitConnect();
            mqttClient.runHouskeeping();
        } catch (Exception e) {
            log.error("Error on MQTT Connection: ", e);
            mqttClient.submitConnect();
        }
    }

    @PreDestroy
    private void stop() {
        if (mqttClient != null) {
            mqttClient.disconnect();
        }
    }

    public ExternalIDRepresentation getExternalId(String externalId, String type) {
        if (type == null) {
            type = "c8y_Serial";
        }
        ID id = new ID();
        id.setType(type);
        id.setValue(externalId);
        ExternalIDRepresentation[] extIds = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                extIds[0] = identityApi.getExternalId(id);
            } catch (SDKException e) {
                log.info("External ID {} not found", externalId);
            }
        });
        return extIds[0];
    }

    public ExternalIDRepresentation createExternalID(ManagedObjectRepresentation mor, String externalId,
            String externalIdType) {
        ExternalIDRepresentation externalID = new ExternalIDRepresentation();
        externalID.setType(externalIdType);
        externalID.setExternalId(externalId);
        externalID.setManagedObject(mor);
        try {
            externalID = identityApi.create(externalID);
        } catch (SDKException e) {
            log.error(e.getMessage());
        }
        return externalID;
    }

    public void createEvent(String message, String type, DateTime eventTime, ManagedObjectRepresentation parentMor) {
        EventRepresentation[] ers = { new EventRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            ers[0].setSource(parentMor != null ? parentMor : agentRepresentation);
            ers[0].setText(message);
            ers[0].setDateTime(eventTime);
            ers[0].setType(type);
            this.eventApi.createAsync(ers[0]);
        });
    }

    public ConfigurationConnection loadConnectionConfiguration() {
        ConfigurationConnection[] results = { new ConfigurationConnection() };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = configurationService.loadConnectionConfiguration();
            // COMMENT OUT ONLY DEBUG
            // results[0].active = true;
            log.info("Found connection configuration: {}", results[0]);
        });
        return results[0];
    }

    public MQTTClient.Certificate loadCertificateByName(String fingerprint) {
        TrustedCertificateRepresentation[] results = { new TrustedCertificateRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = configurationService.loadCertificateByName(fingerprint, credentials);
            log.info("Found certificate with fingerprint: {}", results[0].getFingerprint());
        });
        StringBuffer cert = new StringBuffer("-----BEGIN CERTIFICATE-----\n").append(results[0].getCertInPemFormat())
                .append("\n").append("-----END CERTIFICATE-----");

        return new MQTTClient.Certificate(results[0].getFingerprint(), cert.toString());
    }

    public void saveConnectionConfiguration(ConfigurationConnection configuration) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                configurationService.saveConnectionConfiguration(configuration);
                log.debug("Saved connection configuration");
            } catch (JsonProcessingException e) {
                log.error("JsonProcessingException configuration: {}", e);
                throw new RuntimeException(e);
            }
        });
    }

    public ServiceConfiguration loadServiceConfiguration() {
        ServiceConfiguration[] results = { new ServiceConfiguration() };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = configurationService.loadServiceConfiguration();
            log.info("Found service configuration: {}", results[0]);
        });
        return results[0];
    }

    public void saveServiceConfiguration(ServiceConfiguration configuration) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                configurationService.saveServiceConfiguration(configuration);
                log.debug("Saved service configuration");
            } catch (JsonProcessingException e) {
                log.error("JsonProcessingException configuration: {}", e);
                throw new RuntimeException(e);
            }
        });
    }

    public ConfigurationConnection enableConnection(boolean b) {
        ConfigurationConnection[] mcr = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            mcr[0] = configurationService.enableConnection(b);
            log.info("Saved configuration");
        });
        return mcr[0];
    }

    public void sendStatusService(String type, ServiceStatus serviceStatus) {
        log.debug("Sending status configuration: {}", serviceStatus);
        // EventRepresentation[] ers = { new EventRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            Map<String, String> entry = Map.of("service", serviceStatus.getStatus().name());
            Map<String, Object> service = new HashMap<String, Object>();
            service.put("service_status", entry);
            ManagedObjectRepresentation update = new ManagedObjectRepresentation();
            update.setId(agentRepresentation.getId());
            update.setAttrs(service);
            this.inventoryApi.update(update);
        });
    }

}