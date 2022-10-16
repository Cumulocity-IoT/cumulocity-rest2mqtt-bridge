package mqtt.bridge.core;

import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import javax.annotation.PreDestroy;

import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.Agent;
import com.cumulocity.model.ID;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.identity.IdentityApi;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.fasterxml.jackson.core.JsonProcessingException;

import c8y.IsDevice;
import lombok.extern.slf4j.Slf4j;
import mqtt.bridge.configuration.ConfigurationService;
import mqtt.bridge.configuration.MQTTConfiguration;
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

    private ManagedObjectRepresentation agentMOR;

    private final String AGENT_ID = "MQTT_BRIDGE_SERVICE";
    private final String AGENT_NAME = "REST 2 MQTT Bridge Service";

    public String tenant;

    @EventListener
    public void initialize(MicroserviceSubscriptionAddedEvent event) {
        tenant = event.getCredentials().getTenant();
        log.info("Event received for Tenant {}", tenant);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

        /* Connecting to Cumulocity */
        subscriptionsService.runForTenant(tenant, () -> {
            // register agent
            ExternalIDRepresentation agentIdRep = null;
            try {
                agentIdRep = getExternalId(AGENT_ID, null);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            if (agentIdRep != null) {
                log.info("Agent with id {} already exists {}", AGENT_ID, agentIdRep);
                this.agentMOR = agentIdRep.getManagedObject();
            } else {
                ManagedObjectRepresentation agent = new ManagedObjectRepresentation();
                agent.setName(AGENT_NAME);
                agent.set(new Agent());
                agent.set(new IsDevice());
                this.agentMOR = inventoryApi.create(agent);
                log.info("Agent has been created with ID {}", agentMOR.getId());
                ExternalIDRepresentation externalAgentId = createExternalID(this.agentMOR, AGENT_ID, "c8y_Serial");
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
            ers[0].setSource(parentMor != null ? parentMor : agentMOR);
            ers[0].setText(message);
            ers[0].setDateTime(eventTime);
            ers[0].setType(type);
            this.eventApi.createAsync(ers[0]);
        });
    }

    public MQTTConfiguration loadConfiguration() {
        MQTTConfiguration[] results = { new MQTTConfiguration() };
        subscriptionsService.runForTenant(tenant, () -> {
            results[0] = configurationService.loadConfiguration();
            log.info("Found configuration {}", results[0]);
        });
        return results[0];
    }

    public void saveConfiguration(MQTTConfiguration configuration) {
        subscriptionsService.runForTenant(tenant, () -> {
            try {
                configurationService.saveConfiguration(configuration);
                log.info("Saved configuration");
            } catch (JsonProcessingException e) {
                log.error("JsonProcessingException configuration {}", e);
                throw new RuntimeException(e);
            }
        });
    }

    public MQTTConfiguration setConfigurationActive(boolean b) {
        MQTTConfiguration[] mcr = { null };
        subscriptionsService.runForTenant(tenant, () -> {
            mcr[0] = configurationService.setConfigurationActive(b);
            log.info("Saved configuration");
        });
        return mcr[0];
    }


    public void sendStatusService(String type, ServiceStatus serviceStatus) {
        log.debug("Sending status configuration: {}", serviceStatus);
        // EventRepresentation[] ers = { new EventRepresentation() };
        subscriptionsService.runForTenant(tenant, () -> {
            Map <String, String> entry = Map.of("service", serviceStatus.getStatus().name() );
            Map<String, Object> service = new HashMap<String,Object>();
            service.put("service_status", entry);
            ManagedObjectRepresentation update = new ManagedObjectRepresentation();
            update.setId(agentMOR.getId());
            update.setAttrs(service);
            this.inventoryApi.update(update);
        });
    }

}