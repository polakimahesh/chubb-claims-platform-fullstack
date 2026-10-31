import { HttpTestingController } from '@angular/common/http/testing';
import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { signedInAs, testProviders, text } from '../../testing';
import { Shell } from '../layout/shell';
import { MyClaims } from './claimant/my-claims';
import { Dashboard } from './manager/dashboard';
import { Sla } from './manager/sla';
import { Team } from './manager/team';
import { Notifications } from './notifications/notifications';
import { Queue } from './staff/queue';
import { Workload } from './staff/workload';

afterEach(() => sessionStorage.clear());

const page = <T>(items: T[]) => ({ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 });
const summary = {
  id: 'c1', claimNumber: 'CLM-SG-2026-AAAA1111', market: 'SG', claimType: 'MOTOR', status: 'SUBMITTED', currency: 'SGD',
  estimatedAmount: 5000, assessedAmount: null, assignedOfficerId: null, submittedAt: '2026-01-10T01:00:00Z',
};

async function render<T>(component: Type<T>) {
  TestBed.configureTestingModule({ imports: [component], providers: testProviders() });
  const fixture = TestBed.createComponent(component);
  fixture.detectChanges();
  return { fixture, http: TestBed.inject(HttpTestingController), el: fixture.nativeElement as HTMLElement };
}

async function settle(fixture: { whenStable(): Promise<unknown>; detectChanges(): void }) {
  await fixture.whenStable();
  fixture.detectChanges();
}

describe('Shell navigation', () => {
  const nav = async () => {
    const { fixture, el } = await render(Shell);
    await settle(fixture);
    return Array.from(el.querySelectorAll('[matListItemTitle]')).map((t) => t.textContent!.trim());
  };

  it('claimant', async () => {
    signedInAs('tan@example.com', 'CLAIMANT');
    expect(await nav()).toEqual(['My claims', 'Report an incident', 'AI assistant', 'Notifications']);
  });

  it('officer', async () => {
    signedInAs('officer-1', 'OFFICER');
    expect(await nav()).toEqual(['Intake queue', 'My workload', 'AI assistant', 'Notifications']);
  });

  it('manager', async () => {
    signedInAs('manager-1', 'MANAGER');
    expect(await nav()).toEqual(['Dashboard', 'Intake queue', 'Officer workload', 'Live agents', 'Team performance', 'SLA breaches', 'Notifications']);
  });
});

describe('My claims', () => {
  it('lists the claimant\'s claims and offers to report one when empty', async () => {
    signedInAs('tan@example.com', 'CLAIMANT');
    const { fixture, http, el } = await render(MyClaims);
    http.expectOne('/api/claims?page=0&size=20').flush(page([]));
    await settle(fixture);
    expect(el.textContent).toContain('No claims yet');
    TestBed.resetTestingModule();

    const r = await render(MyClaims);
    r.http.expectOne('/api/claims?page=0&size=20').flush(page([summary]));
    await settle(r.fixture);
    expect(r.el.textContent).toContain('CLM-SG-2026-AAAA1111');
    expect(text(r.el)).toContain('SGD 5,000.00');
  });
});

describe('Intake queue', () => {
  it('officers get a pick-up button and filters reload from the first page', async () => {
    signedInAs('officer-1', 'OFFICER');
    const { fixture, http, el } = await render(Queue);
    http.expectOne('/api/staff/claims/queue?page=0&size=20').flush(page([summary]));
    await settle(fixture);
    expect(el.textContent).toContain('Pick up');
    const q = fixture.componentInstance as unknown as { market: { set(v: string): void }; reset(): void };
    q.market.set('SG');
    q.reset();
    http.expectOne('/api/staff/claims/queue?market=SG&page=0&size=20').flush(page([]));
    await settle(fixture);
    expect(el.textContent).toContain('The queue is clear');
  });

  it('managers see the queue without a pick-up button', async () => {
    signedInAs('manager-1', 'MANAGER');
    const { fixture, http, el } = await render(Queue);
    http.expectOne('/api/staff/claims/queue?page=0&size=20').flush(page([summary]));
    await settle(fixture);
    expect(el.textContent).not.toContain('Pick up');
  });

  it('a claim picked up by someone else first (409) disappears after a reload', async () => {
    signedInAs('officer-1', 'OFFICER');
    const { fixture, http } = await render(Queue);
    http.expectOne('/api/staff/claims/queue?page=0&size=20').flush(page([summary]));
    await settle(fixture);
    (fixture.componentInstance as unknown as { pickUp(c: unknown): void }).pickUp(summary);
    http.expectOne('/api/staff/claims/c1/assign').flush({ status: 409, detail: 'Cannot move claim from UNDER_REVIEW to UNDER_REVIEW' }, { status: 409, statusText: 'Conflict' });
    http.expectOne('/api/staff/claims/queue?page=0&size=20').flush(page([]));
  });
});

describe('Workload', () => {
  it('an officer sees their own claims, filtered by status', async () => {
    signedInAs('officer-1', 'OFFICER');
    const { fixture, http } = await render(Workload);
    http.expectOne('/api/staff/claims?page=0&size=20').flush(page([]));
    const w = fixture.componentInstance as unknown as { status: { set(v: string): void }; reset(): void };
    w.status.set('SETTLED');
    w.reset();
    http.expectOne('/api/staff/claims?status=SETTLED&page=0&size=20').flush(page([]));
  });

  it('a manager chooses the officer first', async () => {
    signedInAs('manager-1', 'MANAGER');
    const { fixture, http, el } = await render(Workload);
    http.expectOne('/api/staff/officers').flush(['officer-1', 'officer-2']);
    await settle(fixture);
    expect(el.textContent).toContain('Choose an officer');
    http.expectNone((r) => r.url === '/api/staff/claims');
    (fixture.componentInstance as unknown as { selectOfficer(o: string): void }).selectOfficer('officer-2');
    http.expectOne('/api/staff/claims?officerId=officer-2&page=0&size=20').flush(page([]));
  });
});

describe('Manager reports', () => {
  it('dashboard shows the summary, exposure and FX total with its rate note', async () => {
    signedInAs('manager-1', 'MANAGER');
    const { fixture, http, el } = await render(Dashboard);
    http.expectOne('/api/reports/summary').flush({ claimsByStatus: { SUBMITTED: 2, SETTLED: 1 }, openClaims: 2, unassignedBacklog: 2, exposure: [] });
    http.expectOne('/api/reports/exposure').flush([{ market: 'AU', claimType: 'PROPERTY', currency: 'AUD', openClaims: 2, totalExposure: 16000 }]);
    http.expectOne('/api/reports/exposure/total?baseCurrency=USD').flush({
      baseCurrency: 'USD', totalExposure: 10400, openClaims: 2, rateSource: 'Static indicative rates; not live',
      breakdown: [{ currency: 'AUD', openClaims: 2, originalAmount: 16000, convertedAmount: 10400 }],
    });
    await settle(fixture);
    expect(text(el)).toContain('AUD 16,000.00');
    expect(text(el)).toContain('USD 10,400.00');
    expect(el.textContent).toContain('not live');
    expect(el.textContent).toContain('3 in total');
  });

  it('team page shows workload and performance', async () => {
    signedInAs('manager-1', 'MANAGER');
    const { fixture, http, el } = await render(Team);
    http.expectOne('/api/reports/workload').flush([{ officerId: 'officer-1', openClaims: 3, byStatus: { UNDER_REVIEW: 2, APPROVED: 1 } }]);
    http.expectOne('/api/reports/performance').flush([{ officerId: 'officer-2', closedClaims: 4, settled: 3, rejected: 1, averageResolutionHours: 50 }]);
    await settle(fixture);
    expect(el.textContent).toContain('officer-1');
    expect(el.textContent).toContain('2d 2h');
  });

  it('SLA page counts both kinds of breach', async () => {
    signedInAs('manager-1', 'MANAGER');
    const { fixture, http, el } = await render(Sla);
    http.expectOne('/api/reports/sla-breaches').flush([
      { claimId: 'c1', claimNumber: 'CLM-1', market: 'SG', claimType: 'MOTOR', status: 'SUBMITTED', assignedOfficerId: null, breachType: 'UNASSIGNED_TOO_LONG', ageHours: 30, thresholdHours: 24 },
      { claimId: 'c2', claimNumber: 'CLM-2', market: 'HK', claimType: 'PROPERTY', status: 'UNDER_REVIEW', assignedOfficerId: 'officer-1', breachType: 'OPEN_TOO_LONG', ageHours: 200, thresholdHours: 168 },
    ]);
    await settle(fixture);
    expect(el.querySelectorAll('.tile-value')[0].textContent).toBe('1');
    expect(el.querySelectorAll('.tile-value')[1].textContent).toBe('1');
    expect(el.textContent).toContain('Not picked up');
  });

  it('a report failure is reported, not swallowed', async () => {
    signedInAs('manager-1', 'MANAGER');
    const { fixture, http } = await render(Sla);
    http.expectOne('/api/reports/sla-breaches').flush({ status: 500, detail: 'An unexpected error occurred.' }, { status: 500, statusText: 'Error' });
    await settle(fixture);
    expect(document.body.textContent).toContain('An unexpected error occurred.');
  });
});

describe('Notifications', () => {
  it('claimants get their own without a filter', async () => {
    signedInAs('tan@example.com', 'CLAIMANT');
    const { fixture, http, el } = await render(Notifications);
    http.expectOne('/api/notifications?page=0&size=20').flush(page([]));
    await settle(fixture);
    expect(el.querySelector('input[name=email]')).toBeNull();
    expect(el.textContent).toContain('No notifications');
  });

  it('staff can filter by claimant e-mail', async () => {
    signedInAs('officer-1', 'OFFICER');
    const { fixture, http } = await render(Notifications);
    http.expectOne('/api/notifications?page=0&size=20').flush(page([]));
    const n = fixture.componentInstance as unknown as { email: string; search(): void };
    n.email = ' lee@example.com ';
    n.search();
    http.expectOne('/api/notifications?claimantEmail=lee@example.com&page=0&size=20').flush(page([]));
  });
});
