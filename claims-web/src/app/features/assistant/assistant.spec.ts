import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { signedInAs, testProviders } from '../../../testing';
import { Assistant } from './assistant';

describe('Assistant', () => {
  let fixture: ComponentFixture<Assistant>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;
  const input = () => el().querySelector('input') as HTMLInputElement;
  const send = () => el().querySelector('button[type=submit]') as HTMLButtonElement;

  async function open(role: 'CLAIMANT' | 'OFFICER' = 'CLAIMANT') {
    signedInAs(role === 'OFFICER' ? 'officer-1' : 'tan@example.com', role);
    TestBed.configureTestingModule({ imports: [Assistant], providers: testProviders() });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Assistant);
    fixture.detectChanges();
    await fixture.whenStable();
  }

  /** Lets the awaited request continue, then renders; twice, because the reply is applied one tick after the flush. */
  async function settle() {
    for (let i = 0; i < 2; i++) {
      await fixture.whenStable();
      fixture.detectChanges();
    }
  }

  async function type(text: string) {
    input().value = text;
    input().dispatchEvent(new Event('input'));
    await settle();
  }

  async function submit() {
    send().click();
    await settle();
  }

  afterEach(() => sessionStorage.clear());

  it('cannot send an empty message', async () => {
    await open();
    expect(send().disabled).toBe(true);
    await type('   ');
    expect(send().disabled).toBe(true);
    await type('Raise a claim');
    expect(send().disabled).toBe(false);
  });

  it('shows the example for the signed-in role', async () => {
    await open('OFFICER');
    expect(el().textContent).toContain('unassigned queue');
  });

  it('sends the conversation and shows the reply with the actions it took', async () => {
    await open();
    await type('Raise a motor claim in Singapore');
    await submit();

    const req = http.expectOne('/api/agent/chat');
    expect(req.request.body).toEqual({ messages: [{ role: 'user', content: 'Raise a motor claim in Singapore' }] });
    req.flush({ reply: 'Raised it.', actions: [{ tool: 'submit_claim', summary: 'Raised claim CLM-SG-1', ok: true }] });
    await settle();

    expect(el().textContent).toContain('Raised it.');
    expect(el().textContent).toContain('Raised claim CLM-SG-1');
    expect(input().value).toBe('');
  });

  it('keeps error bubbles and action details out of the history sent next time', async () => {
    await open();
    await type('first');
    await submit();
    http.expectOne('/api/agent/chat').flush({ detail: 'The assistant is busy.' }, { status: 503, statusText: 'Unavailable' });
    await settle();

    expect(el().textContent).toContain('The assistant is busy.');
    expect(input().value).toBe('first');   // restored so the user can retry

    await submit();
    const req = http.expectOne('/api/agent/chat');
    expect(req.request.body.messages).toEqual([
      { role: 'user', content: 'first' },
      { role: 'user', content: 'first' },
    ]);
    req.flush({ reply: 'ok', actions: [] });
    await settle();
  });
});
