import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AssistantReply, ChatMessage, ClaimDetail, ClaimStatus, ClaimSummary, ClaimType, DocumentInfo, HistoryEntry, InfoRequest, Market, Page, SubmitClaim,
} from '../models';

/** claims-service (system of record): claimant, document and staff endpoints. */
@Injectable({ providedIn: 'root' })
export class ClaimsApi {
  private readonly http = inject(HttpClient);

  // ---------- claimant ----------

  submit(body: SubmitClaim): Observable<ClaimDetail> {
    return this.http.post<ClaimDetail>('/api/claims', body);
  }

  myClaims(page = 0, size = 20): Observable<Page<ClaimSummary>> {
    return this.http.get<Page<ClaimSummary>>('/api/claims', { params: { page, size } });
  }

  get(id: string): Observable<ClaimDetail> {
    return this.http.get<ClaimDetail>(`/api/claims/${id}`);
  }

  history(id: string): Observable<HistoryEntry[]> {
    return this.http.get<HistoryEntry[]>(`/api/claims/${id}/history`);
  }

  infoRequests(id: string): Observable<InfoRequest[]> {
    return this.http.get<InfoRequest[]>(`/api/claims/${id}/info-requests`);
  }

  respond(id: string, requestId: string, response: string): Observable<InfoRequest> {
    return this.http.post<InfoRequest>(`/api/claims/${id}/info-requests/${requestId}/response`, { response });
  }

  // ---------- AI assistant ----------

  /** The assistant runs claim operations as the signed-in user and replies with what it did. */
  chat(messages: ChatMessage[]): Observable<AssistantReply> {
    return this.http.post<AssistantReply>('/api/agent/chat', { messages });
  }

  // ---------- documents ----------

  documents(id: string): Observable<DocumentInfo[]> {
    return this.http.get<DocumentInfo[]>(`/api/claims/${id}/documents`);
  }

  upload(id: string, file: File): Observable<DocumentInfo> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<DocumentInfo>(`/api/claims/${id}/documents`, form);
  }

  download(id: string, documentId: string): Observable<Blob> {
    return this.http.get(`/api/claims/${id}/documents/${documentId}`, { responseType: 'blob' });
  }

  // ---------- staff ----------

  queue(filter: { market?: Market | null; claimType?: ClaimType | null }, page = 0, size = 20): Observable<Page<ClaimSummary>> {
    return this.http.get<Page<ClaimSummary>>('/api/staff/claims/queue', {
      params: params({ ...filter, page, size }),
    });
  }

  workload(filter: { officerId?: string | null; status?: ClaimStatus | null }, page = 0, size = 20): Observable<Page<ClaimSummary>> {
    return this.http.get<Page<ClaimSummary>>('/api/staff/claims', { params: params({ ...filter, page, size }) });
  }

  officers(): Observable<string[]> {
    return this.http.get<string[]>('/api/staff/officers');
  }

  assign(id: string): Observable<ClaimDetail> {
    return this.http.post<ClaimDetail>(`/api/staff/claims/${id}/assign`, null);
  }

  reassign(id: string, toOfficerId: string, reason: string | null): Observable<ClaimDetail> {
    return this.http.post<ClaimDetail>(`/api/staff/claims/${id}/reassign`, { toOfficerId, reason: reason || null });
  }

  requestInfo(id: string, question: string): Observable<InfoRequest> {
    return this.http.post<InfoRequest>(`/api/staff/claims/${id}/info-requests`, { question });
  }

  assess(id: string, assessedAmount: number, note: string | null): Observable<ClaimDetail> {
    return this.http.post<ClaimDetail>(`/api/staff/claims/${id}/assess`, { assessedAmount, note: note || null });
  }

  approve(id: string): Observable<ClaimDetail> {
    return this.http.post<ClaimDetail>(`/api/staff/claims/${id}/approve`, null);
  }

  reject(id: string, reason: string): Observable<ClaimDetail> {
    return this.http.post<ClaimDetail>(`/api/staff/claims/${id}/reject`, { reason });
  }

  settle(id: string): Observable<ClaimDetail> {
    return this.http.post<ClaimDetail>(`/api/staff/claims/${id}/settle`, null);
  }
}

/** Query params without null/empty values. */
export function params(values: Record<string, string | number | null | undefined>): HttpParams {
  let p = new HttpParams();
  for (const [k, v] of Object.entries(values)) {
    if (v !== null && v !== undefined && v !== '') p = p.set(k, v);
  }
  return p;
}
