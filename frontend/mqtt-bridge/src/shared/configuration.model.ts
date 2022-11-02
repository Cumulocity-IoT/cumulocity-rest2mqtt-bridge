export enum Status {
  CONNECTED = "CONNECTED",
  ENABLED = "ENABLED",
  CONFIGURED = "CONFIGURED",
  NOT_READY = "NOT_READY"
}

export enum QOS {
  AT_MOST_ONCE = "AT_MOST_ONCE",
  AT_LEAST_ONCE = "AT_LEAST_ONCE",
  EXACTLY_ONCE = "EXACTLY_ONCE",
}

export interface ConnectionConfiguration {
  mqttHost: string;
  mqttPort: number;
  user: string;
  password: string;
  clientId: string;
  useTLS: boolean;
  enabled: boolean;
  qos: QOS;
  useSelfSignedCertificate: boolean;
  fingerprintSelfSignedCertificate: string;
  nameCertificate: string;
}

export interface ServiceConfiguration {
  logPayload: boolean;
}

export interface ServiceStatus {
  status: Status;
}

export enum Operation {
  CONNECT,
  DISCONNECT
}
