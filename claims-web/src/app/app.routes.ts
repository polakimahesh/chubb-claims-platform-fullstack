import { Routes } from '@angular/router';
import { authGuard, guestGuard, homeRedirect, roleGuard } from './core/guards';

/**
 * One application, three roles. Each area is lazy-loaded and guarded by role; the guards only hide screens a role
 * cannot use - the backend's URL rules and record-level checks remain the real enforcement.
 */
export const routes: Routes = [
  {
    path: 'login',
    canActivate: [guestGuard],
    title: 'Sign in · Claims Platform',
    loadComponent: () => import('./features/login/login').then((m) => m.Login),
  },
  {
    path: '',
    loadComponent: () => import('./layout/shell').then((m) => m.Shell),
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', canActivate: [homeRedirect], children: [] },

      // claimant
      {
        path: 'my-claims', canActivate: [roleGuard('CLAIMANT')], title: 'My claims',
        loadComponent: () => import('./features/claimant/my-claims').then((m) => m.MyClaims),
      },
      {
        path: 'my-claims/new', canActivate: [roleGuard('CLAIMANT')], title: 'Report an incident',
        loadComponent: () => import('./features/claimant/new-claim').then((m) => m.NewClaim),
      },

      // claimant and officer: the AI assistant acts as the signed-in user
      {
        path: 'assistant', canActivate: [roleGuard('CLAIMANT', 'OFFICER')], title: 'AI assistant',
        loadComponent: () => import('./features/assistant/assistant').then((m) => m.Assistant),
      },

      // any role (claimants only ever get their own claim back from the API)
      {
        path: 'claims/:id', title: 'Claim',
        loadComponent: () => import('./features/claim/claim-detail').then((m) => m.ClaimDetailPage),
      },
      {
        path: 'notifications', title: 'Notifications',
        loadComponent: () => import('./features/notifications/notifications').then((m) => m.Notifications),
      },

      // staff
      {
        path: 'queue', canActivate: [roleGuard('OFFICER', 'MANAGER')], title: 'Intake queue',
        loadComponent: () => import('./features/staff/queue').then((m) => m.Queue),
      },
      {
        path: 'workload', canActivate: [roleGuard('OFFICER', 'MANAGER')], title: 'Workload',
        loadComponent: () => import('./features/staff/workload').then((m) => m.Workload),
      },

      // manager
      {
        path: 'dashboard', canActivate: [roleGuard('MANAGER')], title: 'Dashboard',
        loadComponent: () => import('./features/manager/dashboard').then((m) => m.Dashboard),
      },
      {
        path: 'team', canActivate: [roleGuard('MANAGER')], title: 'Team performance',
        loadComponent: () => import('./features/manager/team').then((m) => m.Team),
      },
      {
        path: 'agents', canActivate: [roleGuard('MANAGER')], title: 'Live agents',
        loadComponent: () => import('./features/manager/agents').then((m) => m.Agents),
      },
      {
        path: 'sla', canActivate: [roleGuard('MANAGER')], title: 'SLA breaches',
        loadComponent: () => import('./features/manager/sla').then((m) => m.Sla),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
