/**
 * API models, mirroring the backend DTOs:
 * claims-service ClaimRequests / ClaimResponses, reporting-service ReportResponses, notification-service NotificationResponse.
 * Instants are ISO-8601 UTC strings; LocalDate is 'yyyy-MM-dd'; BigDecimal arrives as a JSON number.
 */

export type Role = 'CLAIMANT' | 'OFFICER' | 'MANAGER';
export type Market = 'SG' | 'HK' | 'MY' | 'TH' | 'ID' | 'AU';
export type ClaimType = 'MOTOR' | 'PROPERTY';
export type ClaimStatus = 'SUBMITTED' | 'UNDER_REVIEW' | 'INFO_REQUESTED' | 'APPROVED' | 'REJECTED' | 'SETTLED';
export type ClaimEventType =
  | 'CLAIM_SUBMITTED' | 'CLAIM_ASSIGNED' | 'CLAIM_REASSIGNED' | 'INFO_REQUESTED' | 'INFO_PROVIDED'
  | 'CLAIM_ASSESSED' | 'CLAIM_APPROVED' | 'CLAIM_REJECTED' | 'CLAIM_SETTLED';

export interface CurrentUser {
  username: string;
  roles: Role[];
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

// ---------- claims-service ----------

export interface ClaimDetail {
  id: string;
  claimNumber: string;
  market: Market;
  claimType: ClaimType;
  status: ClaimStatus;
  claimantName: string;
  claimantEmail: string;
  description: string;
  incidentDate: string;
  currency: string;
  estimatedAmount: number;
  assessedAmount: number | null;
  assignedOfficerId: string | null;
  rejectionReason: string | null;
  submittedAt: string;
  closedAt: string | null;
}

export interface ClaimSummary {
  id: string;
  claimNumber: string;
  market: Market;
  claimType: ClaimType;
  status: ClaimStatus;
  currency: string;
  estimatedAmount: number;
  assessedAmount: number | null;
  assignedOfficerId: string | null;
  submittedAt: string;
}

export interface InfoRequest {
  id: string;
  question: string;
  requestedBy: string;
  requestedAt: string;
  response: string | null;
  respondedAt: string | null;
  open: boolean;
}

export interface HistoryEntry {
  fromStatus: ClaimStatus | null;
  toStatus: ClaimStatus;
  actor: string;
  note: string | null;
  occurredAt: string;
}

export interface DocumentInfo {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  uploadedBy: string;
  uploadedAt: string;
}

export interface SubmitClaim {
  claimantName: string;
  market: Market;
  claimType: ClaimType;
  description: string;
  incidentDate: string;
  currency: string;
  estimatedAmount: number;
}

// ---------- reporting-service ----------

export interface Exposure {
  market: Market;
  claimType: ClaimType;
  currency: string;
  openClaims: number;
  totalExposure: number;
}

export interface ConvertedExposure {
  currency: string;
  openClaims: number;
  originalAmount: number;
  convertedAmount: number;
}

export interface ExposureTotal {
  baseCurrency: string;
  totalExposure: number;
  openClaims: number;
  breakdown: ConvertedExposure[];
  rateSource: string;
}

export interface OfficerWorkload {
  officerId: string;
  openClaims: number;
  byStatus: Partial<Record<ClaimStatus, number>>;
}

export interface OfficerPerformance {
  officerId: string;
  closedClaims: number;
  settled: number;
  rejected: number;
  averageResolutionHours: number;
}

export type BreachType = 'UNASSIGNED_TOO_LONG' | 'OPEN_TOO_LONG';

export interface SlaBreach {
  claimId: string;
  claimNumber: string;
  market: Market;
  claimType: ClaimType;
  status: ClaimStatus;
  assignedOfficerId: string | null;
  breachType: BreachType;
  ageHours: number;
  thresholdHours: number;
}

export interface ReportSummary {
  claimsByStatus: Partial<Record<ClaimStatus, number>>;
  openClaims: number;
  unassignedBacklog: number;
  exposure: Exposure[];
}

// ---------- notification-service ----------

export interface Notification {
  id: string;
  claimId: string;
  claimNumber: string;
  recipientEmail: string;
  eventType: ClaimEventType;
  channel: string;
  subject: string;
  body: string;
  createdAt: string;
}

// ---------- AI assistant (POST /api/agent/chat) ----------

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

/** One claim operation the assistant carried out for the user. */
export interface AssistantAction {
  tool: string;
  summary: string;
  ok: boolean;
}

export interface AssistantReply {
  reply: string;
  actions: AssistantAction[];
}
