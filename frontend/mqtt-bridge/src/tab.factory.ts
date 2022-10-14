import { Injectable } from '@angular/core';
import { TabFactory, Tab } from '@c8y/ngx-components';
import { Router } from '@angular/router';

@Injectable()
export class BridgeConfigurationTabFactory implements TabFactory {
  constructor(public router: Router) {}

  get() {
    const tabs: Tab[] = [];
    if (this.router.url.match(/rest2mqtt/g)) {
      tabs.push({
        path: 'rest2mqtt/configuration',
        priority: 1000,
        label: 'Configuration',
        icon: 'cog',
        orientation: 'horizontal',
      } as Tab);

    }
    return tabs;
  }
}
