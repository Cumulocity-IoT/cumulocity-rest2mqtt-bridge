
export enum Status {
  CONNECTED = "CONNECTED",
  ACTIVATED = "ACTIVATED",
  CONFIGURED = "CONFIGURED",
  NOT_READY = "NOT_READY"
}

export enum QOS {
  AT_MOST_ONCE = "At most once",
  AT_LEAST_ONCE = "At least once", 
  EXACTLY_ONCE = "Exactly once",
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
