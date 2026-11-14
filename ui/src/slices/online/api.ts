// Typed client for the online (CICS) slice REST API (java/online, /api/online/*).

export interface Commarea {
  fromTranid: string; fromProgram: string; toTranid: string; toProgram: string;
  userId: string; userType: string; pgmContext: number | string;
  custId: string | number; acctId: string | number; cardNum: string;
}
export interface SignonResponse {
  screen: string; message: string | null; cursor: 'USERID' | 'PASSWD' | null;
  next: string | null; commarea: Commarea | null;
}
export interface MenuOption { num: number; name: string; program: string; userType: string; text: string }
export interface MenuSelectResponse {
  option: string; message: string | null; messageColor: 'RED' | 'GREEN' | null;
  next: string | null; commarea: Commarea | null;
}
export interface AccountBlock {
  status: string; currBal: string; creditLimit: string; cashCreditLimit: string;
  currCycCredit: string; currCycDebit: string; openDate: string; expiryDate: string;
  reissueDate: string; groupId: string;
}
export interface CustomerBlock {
  custId: string; ssn: string; fico: string; dob: string; firstName: string; middleName: string;
  lastName: string; addrLine1: string; addrLine2: string; city: string; state: string; zip: string;
  country: string; phone1: string; phone2: string; govtId: string; eftAccountId: string; priCardHolder: string;
}
export interface AccountViewResponse {
  acctIdField: string; errorMessage: string | null; infoMessage: string | null;
  account: AccountBlock | null; customer: CustomerBlock | null; custId: string | null; cardNum: string | null;
}
export type UpdateFields = Record<string, string>;
export interface UpdateRequest {
  action: string; state: string; snapshot: unknown; fields: UpdateFields | null; commarea?: Commarea | null;
}
export interface UpdateResponse {
  state: string; infoMessage: string | null; errorMessage: string | null; fields: UpdateFields | null;
  snapshot: unknown; showSaveKeys: boolean; next: string | null;
  abend: { code: string; culprit: string; message: string } | null;
  commarea?: Commarea | null;
}
export interface FeeRequest { acctId: string; groupId: string; cycleBal: string; lateDays: number; cycleDays: number }
export interface FeeResponse { feeAmt: string; avgDaily: string; rc: number; sysprint: string[]; feeLine: string }
export interface DateRequest { inType: string; inDate: string; outType: string }
export interface DateResponse { outDate: string; errorMsg: string; returnCode: number }

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let msg = `${res.status} ${res.statusText}`;
    try { const b = await res.json(); msg = b.error ?? b.message ?? msg; } catch { /* not json */ }
    throw new Error(msg);
  }
  return res.json() as Promise<T>;
}

const post = <T>(url: string, body: unknown, method = 'POST') =>
  fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then(json<T>);

/** Raw 11-char account id as a path segment; blank becomes a single encoded space. */
export const acctPath = (acctId: string) => (acctId.trim() === '' ? '%20' : encodeURIComponent(acctId));

export const onlineApi = {
  signon: (action: string, userId: string, password: string) =>
    post<SignonResponse>('/api/online/signon', { action, userId, password }),
  menu: (userType: string) =>
    fetch(`/api/online/menu?userType=${encodeURIComponent(userType)}`).then(json<{ options: MenuOption[] }>),
  menuSelect: (action: string, option: string, userType: string, commarea: Commarea | null) =>
    post<MenuSelectResponse>('/api/online/menu/select', { action, option, userType, commarea }),
  account: (acctId: string) => fetch(`/api/online/accounts/${acctPath(acctId)}`).then(json<AccountViewResponse>),
  accountChanges: (acctId: string, body: UpdateRequest) =>
    post<UpdateResponse>(`/api/online/accounts/${acctPath(acctId)}/changes`, body),
  accountSave: (acctId: string, body: UpdateRequest) =>
    post<UpdateResponse>(`/api/online/accounts/${acctPath(acctId)}`, body, 'PUT'),
  fees: (req: FeeRequest) => post<FeeResponse>('/api/online/fees', req),
  convertDate: (req: DateRequest) => post<DateResponse>('/api/online/dates/convert', req),
};
