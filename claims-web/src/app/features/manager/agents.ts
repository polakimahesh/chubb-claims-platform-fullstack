import { DatePipe } from '@angular/common';
import { Component, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatIcon } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { EmptyState } from '../../shared/empty-state';
import { PageHeader } from '../../shared/page-header';

interface Insight {
  id: string;
  agent: string;
  severity: 'INFO' | 'WARNING' | 'ALERT';
  claimId: string;
  claimNumber: string;
  message: string;
  at: string;
}

const ICON = { INFO: 'smart_toy', WARNING: 'warning', ALERT: 'priority_high' } as const;

/**
 * Live agent activity. The demo agents run in reporting-service on every claim event and push each insight over
 * server-sent events. EventSource cannot send an Authorization header, so the stream is read with fetch.
 */
@Component({
  selector: 'app-agents',
  imports: [DatePipe, RouterLink, PageHeader, EmptyState, MatCardModule, MatIcon],
  template: `
    <app-page-header title="Live agents" subtitle="Agents react the moment a claim changes - try submitting or assigning a claim.">
      <span class="status" [class.on]="live()"><mat-icon>{{ live() ? 'wifi_tethering' : 'wifi_tethering_off' }}</mat-icon>{{ live() ? 'Live' : 'Reconnecting…' }}</span>
    </app-page-header>
    <mat-card appearance="outlined">
      <mat-card-content>
        @for (i of insights(); track i.id) {
          <div class="row" [class]="i.severity.toLowerCase()">
            <mat-icon>{{ icon(i.severity) }}</mat-icon>
            <div class="body">
              <div class="head"><strong>{{ i.agent }}</strong> · <a [routerLink]="['/claims', i.claimId]">{{ i.claimNumber }}</a>
                <span class="time">{{ i.at | date: 'HH:mm:ss' }}</span></div>
              <div>{{ i.message }}</div>
            </div>
          </div>
        } @empty {
          <app-empty-state icon="smart_toy" title="Waiting for claim activity" message="Agent insights appear here in real time." />
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .status { display: inline-flex; gap: 4px; align-items: center; color: var(--mat-sys-outline); }
    .status.on { color: var(--mat-sys-primary); }
    .row { display: flex; gap: 12px; padding: 12px 4px; border-bottom: 1px solid var(--mat-sys-outline-variant); animation: in .3s ease-out; }
    .row.warning mat-icon { color: var(--mat-sys-tertiary); }
    .row.alert mat-icon { color: var(--mat-sys-error); }
    .head { display: flex; gap: 6px; align-items: baseline; }
    .time { margin-left: auto; color: var(--mat-sys-outline); font-size: 12px; }
    a { color: var(--mat-sys-primary); text-decoration: none; font-weight: 600; }
    @keyframes in { from { opacity: 0; transform: translateY(-6px); } }
  `,
})
export class Agents implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private abort: AbortController | null = null;
  private stopped = false;

  protected readonly insights = signal<Insight[]>([]);
  protected readonly live = signal(false);
  protected icon = (s: Insight['severity']) => ICON[s];

  async ngOnInit(): Promise<void> {
    try {
      this.insights.set(await firstValueFrom(this.http.get<Insight[]>('/api/reports/agents/recent')));
    } catch {
      /* the stream below still works; an empty feed is fine */
    }
    void this.listen();
  }

  ngOnDestroy(): void {
    this.stopped = true;
    this.abort?.abort();
  }

  private add(i: Insight): void {
    if (!this.insights().some((x) => x.id === i.id)) this.insights.update((list) => [i, ...list].slice(0, 100));
  }

  /** Reads the SSE stream, reconnecting after a drop until the page is left. */
  private async listen(): Promise<void> {
    while (!this.stopped) {
      this.abort = new AbortController();
      try {
        const res = await fetch('/api/reports/agents/stream', {
          headers: { Authorization: `Basic ${this.auth.token}`, Accept: 'text/event-stream' },
          signal: this.abort.signal,
        });
        if (res.status === 401 || res.status === 403) return;
        if (!res.ok || !res.body) throw new Error(String(res.status));
        this.live.set(true);
        const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
        let buffer = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += value;
          let end: number;
          while ((end = buffer.indexOf('\n\n')) >= 0) {
            this.handle(buffer.slice(0, end));
            buffer = buffer.slice(end + 2);
          }
        }
      } catch {
        /* aborted or dropped: fall through to reconnect */
      }
      this.live.set(false);
      if (!this.stopped) await new Promise((r) => setTimeout(r, 2000));
    }
  }

  private handle(block: string): void {
    const event = /^event:\s*(.*)$/m.exec(block)?.[1];
    const data = block.split('\n').filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trimStart()).join('\n');
    if (event === 'insight' && data) this.add(JSON.parse(data) as Insight);
  }
}
