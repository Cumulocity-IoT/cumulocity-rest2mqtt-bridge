import { Injectable } from '@angular/core';
import { TabFactory, Tab } from '@c8y/ngx-components';
import { Router } from '@angular/router';

@Injectable()
export class ForwarderConfigurationTabFactory implements TabFactory {
  constructor(public router: Router) {}

  get() {
    const tabs: Tab[] = [];
    if (this.router.url.match(/forwarder/g)) {
      tabs.push({
        path: 'forwarder/configuration',
        priority: 1000,
        label: 'Configuration',
        icon: 'cog',
        orientation: 'horizontal',
      } as Tab);

    }
    return tabs;
  }
}
