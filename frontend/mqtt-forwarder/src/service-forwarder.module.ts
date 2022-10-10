import { CommonModule } from '@angular/common';
import { NgModule } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import {
  CoreModule,
  HOOK_NAVIGATOR_NODES,
  HOOK_ROUTE,
  HOOK_TABS,
  Route
} from '@c8y/ngx-components';
import { PopoverModule } from 'ngx-bootstrap/popover';
import { BokerConfigurationComponent } from './mqtt-configuration/broker-configuration.component';
import { BrokerConfigurationService } from './mqtt-configuration/broker-configuration.service';
import { TerminateBrokerConnectionModalComponent } from './mqtt-configuration/terminate/terminate-connection-modal.component';
import { ConfigurationNavigationFactory } from './navigation.factory';
import { OverviewGuard } from './shared/overview.guard';
import { ForwarderConfigurationTabFactory } from './tab.factory';

@NgModule({
  imports: [
    CoreModule,
    CommonModule,
    FormsModule,
    PopoverModule,
    ReactiveFormsModule,
    RouterModule.forChild([
      {
        path: 'forwarder/configuration',
        pathMatch: 'full',
        component: BokerConfigurationComponent,
      },
    ]),
  ],
  exports: [
    BokerConfigurationComponent,
    TerminateBrokerConnectionModalComponent,
  ],
  entryComponents: [
    BokerConfigurationComponent,
    TerminateBrokerConnectionModalComponent,
  ],
  declarations: [
    BokerConfigurationComponent,
    TerminateBrokerConnectionModalComponent,
  ],
  providers: [
    OverviewGuard,
    BrokerConfigurationService,
    { provide: HOOK_NAVIGATOR_NODES, useClass: ConfigurationNavigationFactory, multi: true },
    { provide: HOOK_TABS, useClass: ForwarderConfigurationTabFactory, multi: true },
    {
      provide: HOOK_ROUTE,
      useValue: [
        {
          path: 'forwarder/configuration',
          component: BokerConfigurationComponent,
        },
      ] as Route[],
      multi: true,
    },
  ],
})
export class MQTTForwarderModule {
  constructor() {}
}