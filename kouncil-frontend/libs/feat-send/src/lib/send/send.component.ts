import {Component, ViewChild} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {FormControl, NgForm, Validators} from '@angular/forms';
import {SendService} from './send.service';
import {first, map, switchMap} from 'rxjs/operators';
import {MatDialog} from '@angular/material/dialog';
import {MatSnackBar} from '@angular/material/snack-bar';
import {MessageData, MessageDataHeader, MessageDataService} from '@app/message-data';
import {combineLatest, iif, Observable, of} from 'rxjs';
import {SchemaFacadeService, SchemaStateService} from '@app/schema-registry';
import {ServersService} from '@app/common-servers';

@Component({
  selector: 'app-send',
  template: `
    <mat-dialog-content *ngIf="messageData$ | async as messageData">
      <form #sendForm="ngForm" (ngSubmit)="onSubmit(messageData)">
        <div class="drawer-header">
          <div class="drawer-title">Send event to {{ messageData.topicName }}</div>
          <div class="spacer"></div>
          <mat-icon mat-dialog-close class="close">close</mat-icon>
        </div>

        <div class="drawer-section-subtitle">
          Available placeholders: {{uuid}<!----> }, {{count}<!----> }, {{timestamp}<!----> }
          <br>
          Each placeholder could be formatted (e.g. {{timestamp:YYYY}<!----> }).
          Format should be given after <strong>colon (:)</strong> which precedes placeholder.
          Supported formats: date patterns (e.g. YYYY), decimal integer conversion (e.g. 04d)
        </div>
        <div class="drawer-section-title">Key</div>

        <div>
          <mat-form-field [appearance]="'outline'" class="full-width">
            <input [(ngModel)]="messageData.key" matInput type="text" name="key"/>
          </mat-form-field>
        </div>

        <div class="drawer-section-title">
          Headers
          <button
            type="button"
            class="small-button"
            mat-button
            disableRipple
            (click)="addHeader(messageData.headers)"
          >
            +
          </button>
        </div>
        <div class="header" *ngFor="let header of messageData.headers; let i = index">
          <mat-form-field [appearance]="'outline'" style="width: 48%; padding: 8px">
            <input class="header" [(ngModel)]="header.key" placeholder="Header key" matInput
                   type="text" name="header-key-{{ i }}"/>
          </mat-form-field>
          <mat-form-field [appearance]="'outline'" style="width: 48%; padding: 8px">
            <input class="header" [(ngModel)]="header.value" placeholder="Header value" matInput
                   type="text" name="header-value-{{ i }}"/>
          </mat-form-field>
          <button type="button" class="small-button" mat-button disableRipple
                  (click)="removeHeader(i, messageData.headers)">
            -
          </button>
        </div>

        <div class="drawer-section-title">Value</div>

        <textarea rows="10" [(ngModel)]="messageData.value" name="value"></textarea>

        <div class="drawer-section-title">Count</div>
        <div class="drawer-section-subtitle">
          How many times you want to send this event?
        </div>

        <mat-form-field [appearance]="'outline'" class="count">
          <input matInput type="number" min="1" [formControl]="countControl" name="count"/>
          <div matSuffix>
            <button type="button" class="small-button" mat-button disableRipple
                    (click)="decreaseCount()">
              -
            </button>
            <button type="button" class="small-button" mat-button disableRipple
                    (click)="increaseCount()">
              +
            </button>
          </div>
        </mat-form-field>

        <span class="spacer"></span>

        <div class="actions">
          <button
            type="button"
            mat-dialog-close
            mat-button
            disableRipple
            class="action-button-white"
          >
            Cancel
          </button>
          <button mat-button disableRipple
                  class="action-button-black"
                  type="submit"
                  [disabled]="isSendButtonDisabled">
            Send event
          </button>
        </div>
      </form>
    </mat-dialog-content>
  `,
  styleUrls: ['./send.component.scss']
})
export class SendComponent {

  @ViewChild('sendForm', {read: NgForm}) sendForm: NgForm;

  countControl: FormControl = new FormControl<number>(1, [
    Validators.min(1),
    Validators.required,
  ]);
  isSendButtonDisabled: boolean = false;

  messageData$: Observable<MessageData> = combineLatest([
    this.messageDataService.messageData$,
    this.schemaStateService.isSchemaConfigured$(this.servers.getSelectedServerId())
  ]).pipe(
    switchMap(([messageData, isSchemaConfigured]) =>
      iif(() => isSchemaConfigured,
        this.schemaFacade.getExampleSchemaData$(this.servers.getSelectedServerId(), messageData.topicName).pipe(
          map(exampleData => ({
              ...messageData,
              key: messageData.key ?? JSON.stringify(exampleData.exampleKey),
              value: messageData.value ? JSON.stringify(messageData.value, null, 2) :
                JSON.stringify(exampleData.exampleValue, null, 2)
            })
          )),
        of({
            ...messageData,
            value: messageData.value ? JSON.stringify(messageData.value, null, 2) : messageData.value
          }
        ))
    )
  );

  constructor(
    private http: HttpClient,
    private sendService: SendService,
    private dialog: MatDialog,
    private snackbar: MatSnackBar,
    private servers: ServersService,
    private schemaFacade: SchemaFacadeService,
    private schemaStateService: SchemaStateService,
    private messageDataService: MessageDataService) {
  }

  onSubmit(messageData: MessageData): void {
    this.isSendButtonDisabled = true;
    this.messageDataService.setMessageData(messageData);
    this.sendService.send$(this.servers.getSelectedServerId(), this.countControl.value, messageData)
    .pipe(first())
    .subscribe(() => {
      this.dialog.closeAll();
      this.resetForm();
      this.isSendButtonDisabled = false;
      this.snackbar.open(`Successfully sent to ${messageData.topicName}`, '', {
        duration: 3000,
        panelClass: ['snackbar-success', 'snackbar'],
      });
    }, error => {
      console.error(error);
      this.snackbar.open(`Error occurred while sending events to ${messageData.topicName}`, '', {
        duration: 3000,
        panelClass: ['snackbar-error', 'snackbar']
      });
      this.isSendButtonDisabled = false;
    });
  }

  increaseCount(): void {
    this.countControl.setValue(this.countControl.value + 1);
  }

  decreaseCount(): void {
    if (this.countControl.value > 1) {
      this.countControl.setValue(this.countControl.value - 1);
    }
  }

  resetForm(): void {
    this.sendForm.reset({value: '', key: ''});
    this.countControl.reset(1);
  }

  addHeader(headers: MessageDataHeader[]): void {
    headers.push({key: '', value: ''} as MessageDataHeader);
  }

  removeHeader(i: number, headers: MessageDataHeader[]): void {
    headers.splice(i, 1);
  }

}
