import { Component, computed, effect, ElementRef, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIcon } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';
import { ApiError } from '../../core/api-error';
import { ClaimsApi } from '../../core/api/claims.api';
import { AuthService } from '../../core/auth.service';
import { AssistantAction, ChatMessage } from '../../core/models';
import { PageHeader } from '../../shared/page-header';

interface Bubble extends ChatMessage {
  actions?: AssistantAction[];
  error?: boolean;
}

/**
 * Chat with the AI assistant. It runs claim operations (POST /api/agent/chat) as the signed-in user, so it can only
 * do what this user could do in the app. Earlier turns are sent back with each request; only text is kept.
 */
@Component({
  selector: 'app-assistant',
  imports: [FormsModule, PageHeader, MatCardModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIcon],
  template: `
    <app-page-header title="AI assistant" [subtitle]="hint" />
    <mat-card appearance="outlined">
      <mat-card-content>
        <div class="log" #log role="log" aria-live="polite" aria-label="Conversation with the assistant">
          @for (m of messages(); track $index) {
            <div class="bubble" [class.user]="m.role === 'user'" [class.error]="m.error">
              <div class="text">{{ m.content }}</div>
              @for (a of m.actions ?? []; track $index) {
                <div class="action" [class.failed]="!a.ok">
                  <mat-icon aria-hidden="true">{{ a.ok ? 'check_circle' : 'error' }}</mat-icon>
                  <span class="sr-only">{{ a.ok ? 'Done:' : 'Failed:' }}</span>{{ a.summary }}
                </div>
              }
            </div>
          } @empty {
            <p class="empty">Try: {{ example }}</p>
          }
          @if (busy()) {
            <div class="bubble"><div class="text muted">Working on it…</div></div>
          }
        </div>
        <form class="send" (ngSubmit)="send()">
          <mat-form-field appearance="outline" class="grow">
            <mat-label>Ask the assistant</mat-label>
            <input matInput name="text" [ngModel]="draft()" (ngModelChange)="draft.set($event)" maxlength="4000"
                   autocomplete="off" [readonly]="busy()" />
          </mat-form-field>
          <button mat-flat-button type="submit" [disabled]="!canSend()" aria-label="Send message">
            <mat-icon aria-hidden="true">send</mat-icon> Send
          </button>
        </form>
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .log { display: flex; flex-direction: column; gap: 12px; min-height: 240px; max-height: 55vh; overflow-y: auto; padding-bottom: 12px; }
    .bubble { max-width: 80%; padding: 10px 14px; border-radius: 14px; background: var(--mat-sys-surface-container-high); align-self: flex-start; }
    .bubble.user { align-self: flex-end; background: var(--mat-sys-primary-container); }
    .bubble.error { background: var(--mat-sys-error-container); }
    .text { white-space: pre-wrap; }
    .muted, .empty { color: var(--mat-sys-outline); }
    .action { display: flex; gap: 6px; align-items: center; margin-top: 6px; font-size: 13px; color: var(--mat-sys-primary); }
    .action.failed { color: var(--mat-sys-error); }
    .action mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .send { display: flex; gap: 12px; align-items: flex-start; }
    .grow { flex: 1; }
    .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
  `,
})
export class Assistant {
  private readonly api = inject(ClaimsApi);
  private readonly officer = inject(AuthService).user()?.roles?.includes('OFFICER') ?? false;

  protected readonly messages = signal<Bubble[]>([]);
  protected readonly busy = signal(false);
  protected readonly draft = signal('');
  protected readonly canSend = computed(() => !this.busy() && this.draft().trim().length > 0);
  private readonly log = viewChild<ElementRef<HTMLElement>>('log');

  constructor() {
    // keep the newest message in view
    effect(() => {
      this.messages();
      this.busy();
      queueMicrotask(() => {
        const el = this.log()?.nativeElement;
        if (el) el.scrollTop = el.scrollHeight;
      });
    });
  }

  protected readonly hint = this.officer
    ? 'Tell the assistant what to do with claims. It acts as you, so the normal rules apply.'
    : 'Describe what happened and the assistant raises the claim for you. It acts as you.';
  protected readonly example = this.officer
    ? '"Show the unassigned queue" or "Pick up the oldest motor claim".'
    : '"Raise a motor claim in Singapore: I was rear-ended yesterday, about 8000".';

  protected async send(): Promise<void> {
    if (!this.canSend()) return;
    const text = this.draft().trim();
    this.draft.set('');
    this.messages.update((m) => [...m, { role: 'user', content: text }]);
    this.busy.set(true);
    try {
      const history: ChatMessage[] = this.messages().filter((m) => !m.error).map(({ role, content }) => ({ role, content }));
      const res = await firstValueFrom(this.api.chat(history));
      this.messages.update((m) => [...m, { role: 'assistant', content: res.reply, actions: res.actions }]);
    } catch (e) {
      const err = ApiError.from(e);
      const detail = err.fieldErrors.length ? `${err.message} (${err.fieldErrors.join('; ')})` : err.message;
      this.messages.update((m) => [...m, { role: 'assistant', content: detail, error: true }]);
      this.draft.set(text);   // let the user retry without retyping
    } finally {
      this.busy.set(false);
    }
  }
}
