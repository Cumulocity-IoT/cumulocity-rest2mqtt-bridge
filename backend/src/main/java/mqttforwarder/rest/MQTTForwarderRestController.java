package mqttforwarder.rest;

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
import mqttforwarder.configuration.MQTTConfiguration;
import mqttforwarder.core.C8yAgent;
import mqttforwarder.service.MQTTClient;
import mqttforwarder.service.ServiceOperation;
import mqttforwarder.service.ServiceStatus;

@Slf4j
@RestController
public class MQTTForwarderRestController {

    @Autowired
    MQTTClient mqttClient;

    @Autowired
    C8yAgent c8yAgent;

    @RequestMapping(value = "/connection", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity configureConnectionToBroker(@Valid @RequestBody MQTTConfiguration configuration) {
        log.info("Getting mqtt broker configuration: {}", configuration.toString());
        try {
            mqttClient.saveConfiguration(configuration);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/operation", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity runOperation(@Valid @RequestBody ServiceOperation operation) {
        log.info("Getting operation: {}", operation.toString());
        try {
            mqttClient.runOperation(operation);
            return ResponseEntity.status(HttpStatus.CREATED).build();
        } catch (Exception ex) {
            log.error("Error getting mqtt broker configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }


    @RequestMapping(value = "/payload/{topic}", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Long> forwardPayload (@PathVariable String topic, @Valid @RequestBody Map<String, Object> payload) {
        try {
            log.info("Forward payload {}", payload);
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

    @RequestMapping(value = "/connection", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getConnectionDetails() {
        log.info("get connection details");
        try {
            final MQTTConfiguration configuration = mqttClient.getConnectionDetails();
            if (configuration == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MQTT connection not available");
            }
            // don't modify original copy
            final MQTTConfiguration configuration_clone = (MQTTConfiguration) configuration.clone();
            configuration_clone.setPassword("");
            return new ResponseEntity<>(configuration_clone, HttpStatus.OK);
        } catch (Exception ex) {
            log.error("Error on loading configuration {}", ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getLocalizedMessage());
        }
    }

    @RequestMapping(value = "/status", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ServiceStatus> getStatus() {
        log.info("query status: {}", mqttClient.isConnectionConfigured());
         
        if (mqttClient.isConnected()) {
            return new ResponseEntity<>(ServiceStatus.connected(), HttpStatus.OK);
        } else if (mqttClient.isConnectionActicated()) {
            return new ResponseEntity<>(ServiceStatus.activated(), HttpStatus.OK);
        } else if (mqttClient.isConnectionConfigured()) {
            return new ResponseEntity<>(ServiceStatus.configured(), HttpStatus.OK);
        }
        return new ResponseEntity<>(ServiceStatus.notReady(), HttpStatus.OK);
    }

}