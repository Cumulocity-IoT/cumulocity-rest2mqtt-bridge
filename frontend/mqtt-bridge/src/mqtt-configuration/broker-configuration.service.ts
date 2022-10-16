import { Injectable } from '@angular/core';
import { FetchClient, IdentityService, IExternalIdentity, IFetchResponse, Realtime } from '@c8y/client';
import { AGENT_ID, BASE_URL, PATH_CONNECT_ENDPOINT, PATH_OPERATION_ENDPOINT, PATH_STATUS_ENDPOINT, STATUS_SERVICE_EVENT_TYPE } from '../shared/helper';
import { MQTTAuthentication, ServiceStatus, Status } from '../shared/configuration.model';
import { BehaviorSubject, Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class BrokerConfigurationService {
  constructor(private client: FetchClient,
    private identity: IdentityService) {
    this.realtime = new Realtime(this.client);
  }

  private agentId: string;
  private serviceStatus = new BehaviorSubject<ServiceStatus>({ status: Status.NOT_READY });
  private _currentServiceStatus = this.serviceStatus.asObservable();
  private realtime: Realtime

  async initializeMQTTBridgeAgent(): Promise<string> {
    if (!this.agentId) {
      const identity: IExternalIdentity = {
        type: 'c8y_Serial',
        externalId: AGENT_ID
      };

      const { data, res } = await this.identity.detail(identity);
      if (res.status < 300) {
        this.agentId = data.managedObject.id.toString();
        console.log("BrokerConfigurationService: Found MQTT Bridge Service", this.agentId);
      }
    }
    return this.agentId;
  }

  updateConnectionDetails(mqttConfiguration: MQTTAuthentication): Promise<IFetchResponse> {
    return this.client.fetch(`${BASE_URL}/${PATH_CONNECT_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify(mqttConfiguration),
      method: 'POST',
    });
  }

  connectToMQTTBroker(): Promise<IFetchResponse> {
    return this.client.fetch(`${BASE_URL}/${PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({ "operation": "CONNECT" }),
      method: 'POST',
    });
  }

  disconnectFromMQTTBroker(): Promise<IFetchResponse> {
    return this.client.fetch(`${BASE_URL}/${PATH_OPERATION_ENDPOINT}`, {
      headers: {
        'content-type': 'application/json',
      },
      body: JSON.stringify({ "operation": "DISCONNECT" }),
      method: 'POST',
    });
  }

  async getConnectionDetails(): Promise<MQTTAuthentication> {
    const response = await this.client.fetch(`${BASE_URL}/${PATH_CONNECT_ENDPOINT}`, {
      headers: {
        accept: 'application/json',
      },
      method: 'GET',
    });

    if (response.status != 200) {
      return undefined;
    }

    return (await response.json()) as MQTTAuthentication;
  }

  async getConnectionStatus(): Promise<ServiceStatus> {
    const response = await this.client.fetch(`${BASE_URL}/${PATH_STATUS_ENDPOINT}`, {
      method: 'GET',
    });
    const result = await response.json();
    return result;
  }

  public getCurrentServiceStatus(): Observable<ServiceStatus> {
    return this._currentServiceStatus;
  }

  async subscribeMonitoringChannel(): Promise<object> {
    this.agentId = await this.initializeMQTTBridgeAgent();
    console.log("Start subscription for monitoring:", this.agentId);
    this.getConnectionStatus().then( status => {
      this.serviceStatus.next(status);
    })
    return this.realtime.subscribe(`/managedobjects/${this.agentId}`, this.updateStatus.bind(this));
  }

  unsubscribeFromMonitoringChannel(subscription: object): object {
    return this.realtime.unsubscribe(subscription);
  }

  private updateStatus(p: object): void {
    let payload = p['data']['data'];
    let status: ServiceStatus = payload['service_status'];
    this.serviceStatus.next(status);
    //console.log("New monitoring event", status);
  }
}