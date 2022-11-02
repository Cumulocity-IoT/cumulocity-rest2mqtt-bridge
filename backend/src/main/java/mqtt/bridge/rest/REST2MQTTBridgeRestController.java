package mqtt.bridge.rest;

import java.util.Map;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.extern.slf4j.Slf4j;
import mqtt.bridge.configuration.ConfigurationConnection;
import mqtt.bridge.configuration.ServiceConfiguration;
import mqtt.bridge.core.C8yAgent;
import mqtt.bridge.service.MQTTClient;
import mqtt.bridge.service.ServiceOperation;
import mqtt.bridge.service.ServiceStatus;

@Slf4j
@RestController
public class REST2MQTTBridgeRestController {

    @Autowired
    MQTTClient mqttClient;

    @Autowired
    C8yAgent c8yAgent;


    @RequestMapping(value = "/configuration/connection", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConfigurationConnection> getConnectionConfiguration() {
        log.info("Get connection details");
        try {
            final ConfigurationConnection configuration = mqttClient.loadConnectionConfiguration();
            if (configuration == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MQTT connection not available");
            }
            // don't modify original copy
            ConfigurationConnection configurationClone = (ConfigurationConnection) configuration.clone();
            configurationClone.setPassword("");
            return new ResponseEntity<ConfigurationConnection>(configurationClone, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Error on loading configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/configuration/connection", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> configureConnectionToBroker(@Valid @RequestBody ConfigurationConnection configuration) {
        
        // don't modify original copy
        ConfigurationConnection configurationClone = (ConfigurationConnection) configuration.clone();
        configurationClone.setPassword("");
        log.info("Post MQTT broker configuration: {}", configurationClone.toString());
        try {
            mqttClient.saveConnectionConfiguration(configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/configuration/service", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceConfiguration> getServiceConfiguration() {
        log.info("Get connection details");
        try {
            final ServiceConfiguration configuration = mqttClient.loadServiceConfiguration();
            if (configuration == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service connection not available");
            }
            // don't modify original copy
            return new ResponseEntity<ServiceConfiguration>(configuration, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Error on loading configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/configuration/service", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> configureConnectionToBroker(@Valid @RequestBody ServiceConfiguration configuration) {
        
        // don't modify original copy
        log.info("Post service configuration: {}", configuration.toString());
        try {
            mqttClient.saveServiceConfiguration(configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/operation", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HttpStatus> runOperation(@Valid @RequestBody ServiceOperation operation) {
        log.info("Post operation: {}", operation.toString());
        try {
            mqttClient.runOperation(operation);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/payload/{topic}", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Long> forwardPayload(@PathVariable String topic,
            @Valid @RequestBody Map<String, Object> payload) {
        try {
            log.debug("Forward payload {} to topic {}", payload, topic);
            Long result = mqttClient.forwardPayload(topic, payload);
            return ResponseEntity.status(HttpStatus.OK).body(result);
        } catch (Exception ex) {
            if (ex instanceof RuntimeException)
                throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getLocalizedMessage());
            else if (ex instanceof JsonProcessingException)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getLocalizedMessage());
            else
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/status", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceStatus> getStatus() {
        ServiceStatus st = mqttClient.getServiceStatus();
        log.info("Get status: {}", st);
        return new ResponseEntity<>(st, HttpStatus.OK);
    }

}
