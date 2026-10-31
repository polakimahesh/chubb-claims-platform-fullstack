import { BreakpointObserver } from '@angular/cdk/layout';
import { Component, computed, inject, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenav, MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { AuthService } from '../core/auth.service';
import { ThemeMode, ThemeService } from '../core/theme.service';
import { MatTooltip } from '@angular/material/tooltip';
import { Role } from '../core/models';

interface NavItem {
  label: string;
  icon: string;
  link: string;
  roles: Role[];
  group: 'Overview' | 'Claims' | 'Insights' | 'Inbox';
}

const NAV: NavItem[] = [
  { label: 'Dashboard', icon: 'space_dashboard', link: '/dashboard', roles: ['MANAGER'], group: 'Overview' },
  { label: 'My claims', icon: 'description', link: '/my-claims', roles: ['CLAIMANT'], group: 'Claims' },
  { label: 'Report an incident', icon: 'add_circle', link: '/my-claims/new', roles: ['CLAIMANT'], group: 'Claims' },
  { label: 'Intake queue', icon: 'inbox', link: '/queue', roles: ['OFFICER', 'MANAGER'], group: 'Claims' },
  { label: 'My workload', icon: 'assignment_ind', link: '/workload', roles: ['OFFICER'], group: 'Claims' },
  { label: 'Officer workload', icon: 'assignment_ind', link: '/workload', roles: ['MANAGER'], group: 'Claims' },
  { label: 'Live agents', icon: 'smart_toy', link: '/agents', roles: ['MANAGER'], group: 'Insights' },
  { label: 'Team performance', icon: 'groups', link: '/team', roles: ['MANAGER'], group: 'Insights' },
  { label: 'SLA breaches', icon: 'timer_off', link: '/sla', roles: ['MANAGER'], group: 'Insights' },
  { label: 'Notifications', icon: 'notifications', link: '/notifications', roles: ['CLAIMANT', 'OFFICER', 'MANAGER'], group: 'Inbox' },
];

const GROUPS: NavItem['group'][] = ['Overview', 'Claims', 'Insights', 'Inbox'];

const ROLE_LABEL: Record<Role, string> = { CLAIMANT: 'Claimant', OFFICER: 'Claims officer', MANAGER: 'Claims manager' };

@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet, RouterLink, RouterLinkActive, MatSidenavModule, MatToolbarModule, MatListModule, MatButtonModule,
    MatIcon, MatMenuModule, MatTooltip,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  protected readonly auth = inject(AuthService);
  protected readonly theme = inject(ThemeService);
  protected readonly themeModes: { mode: ThemeMode; label: string; icon: string }[] = [
    { mode: 'light', label: 'Light', icon: 'light_mode' },
    { mode: 'dark', label: 'Dark', icon: 'dark_mode' },
    { mode: 'system', label: 'System', icon: 'computer' },
  ];
  private readonly sidenav = viewChild.required(MatSidenav);

  protected readonly handset = toSignal(
    inject(BreakpointObserver).observe('(max-width: 959px)').pipe(map((r) => r.matches)),
    { initialValue: false },
  );

  /** Navigation for the user's roles, in groups (empty groups are dropped). */
  protected readonly nav = computed(() => {
    const user = this.auth.user();
    const items = user ? NAV.filter((n) => n.roles.some((r) => user.roles.includes(r))) : [];
    return GROUPS.map((title) => ({ title, items: items.filter((i) => i.group === title) })).filter((g) => g.items.length);
  });

  protected readonly roleLabel = computed(() => {
    const role = this.auth.user()?.roles[0];
    return role ? ROLE_LABEL[role] : '';
  });

  protected readonly initials = computed(() => (this.auth.user()?.username ?? '?').slice(0, 2).toUpperCase());

  protected navigated(): void {
    if (this.handset()) void this.sidenav().close();
  }
}
