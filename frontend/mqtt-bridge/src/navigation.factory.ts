import { Injectable } from '@angular/core';
import { ApplicationService } from '@c8y/client';
import { gettext, NavigatorNode, NavigatorNodeFactory } from '@c8y/ngx-components';

@Injectable()
export class BridgeNavigationFactory implements NavigatorNodeFactory {
  private static readonly APPLICATION_NAME = 'rest2mqtt-bridge';

  private readonly NAVIGATION_NODE_MQTT = new NavigatorNode({
    parent: gettext('Settings'),
    label: gettext('MQTT Bridge'),
    icon: 'forward1',
    path: 'rest2mqtt/configuration',
    priority: 99,
    preventDuplicates: true,
  });

  constructor(private applicationService: ApplicationService) {}

  get() {
    return this.applicationService
      .isAvailable(BridgeNavigationFactory.APPLICATION_NAME)
      .then((result) => {
        if (!(result && result.data)) {
          console.error('MQTT Generic Microservice not subscribed!');
          return [];
        }
        //console.log('navigation node: ', this.NAVIGATION_NODE_MQTT);
        return this.NAVIGATION_NODE_MQTT;
      });
  }
}
