
export enum Status {
  CONNECTED = "CONNECTED",
  ACTIVATED = "ACTIVATED",
  CONFIGURED = "CONFIGURED",
  NOT_READY = "NOT_READY"
}

export enum QOS {
  AT_MOST_ONCE = "AT_MOST_ONCE",
  AT_LEAST_ONCE = "AT_LEAST_ONCE", 
  EXACTLY_ONCE = "EXACTLY_ONCE",
}

export interface MQTTAuthentication {
  mqttHost: string;
  mqttPort: number;
  user: string;
  password: string;
  clientId: string;
  useTLS: boolean;
  active: boolean;
  qos: QOS;
}

export interface ServiceStatus {
  status: Status;
}
