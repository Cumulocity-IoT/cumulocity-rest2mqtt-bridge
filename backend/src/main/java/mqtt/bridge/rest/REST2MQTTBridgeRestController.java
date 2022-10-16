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
import mqtt.bridge.configuration.MQTTConfiguration;
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

    @RequestMapping(value = "/operation", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceStatus> runOperation(@Valid @RequestBody ServiceOperation operation) {
        log.info("Getting operation: {}", operation.toString());
        try {
            mqttClient.runOperation(operation);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/connection", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceStatus> configureConnectionToBroker(
            @Valid @RequestBody MQTTConfiguration configuration) {
        // don't modify original copy
        MQTTConfiguration configurationClone = (MQTTConfiguration) configuration.clone();
        configurationClone.setPassword("");
        log.info("Post MQTT broker configuration: {}", configurationClone.toString());
        try {
            mqttClient.saveConfiguration(configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/connection", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MQTTConfiguration> getConnectionDetails() {
        log.info("Get connection details");
        try {
            final MQTTConfiguration configuration = mqttClient.getConnectionDetails();
            if (configuration == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MQTT connection not available");
            }
            // don't modify original copy
            MQTTConfiguration configurationClone = (MQTTConfiguration) configuration.clone();
            configurationClone.setPassword("");
            return new ResponseEntity<MQTTConfiguration>(configurationClone, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Error on loading configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/payload/{topic}", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Long> forwardPayload(@PathVariable String topic,
            @Valid @RequestBody Map<String, Object> payload) {
        try {
            log.info("Forward payload {} to topic {}", payload, topic);
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
