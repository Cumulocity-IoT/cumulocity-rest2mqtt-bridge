package mqtt.bridge.configuration;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@Data
@ToString ()
@AllArgsConstructor
public class ServiceConfiguration implements Cloneable {
    public ServiceConfiguration () {
        this.logPayload = false;
    }

    @NotNull
    @JsonSetter(nulls = Nulls.SKIP)
    public boolean logPayload;

}
