package mqttforwarder.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;

enum Operation {
    CONNECT,
    DISCONNECT
}

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceOperation {
    @NotNull
    private Operation operation;

    public static ServiceOperation connect() {
        return new ServiceOperation(Operation.CONNECT);
    }

    public static ServiceOperation disConnect() {
        return new ServiceOperation(Operation.DISCONNECT);
    }
}
