# Business rules: Online and system routines (CICS, HLASM, PL/I)

> **Analysis-only.** CICS/BMS (COSGN00C, COMEN01C, COACTVWC, COACTUPC), HLASM (COBDATFT, MVSWAIT) and PL/I (CBFEECAL) cannot run in the off-host estate: there is no CICS region, BMS assembler, HLASM assembler or PL/I compiler (see `docs/BASELINE.md` and `docs/LEGACY-LANGUAGES.md`). Nothing in this slice was executed. Every rule was extracted by reading the source, the line ranges were checked against the files, and the expected results come from that reading or, for CBFEECAL, from the six worked examples in `docs/LEGACY-LANGUAGES.md`. This slice has no parity fixtures, so every `fixtures` list is empty.

Slice: `online`. Machine-readable mirror: `online.json` (same ids, one to one).

## Summary

| Area | Rules |
|---|---|
| Sign-on (COSGN00C) | BR-ONL-001 - BR-ONL-010 |
| Main menu (COMEN01C) | BR-ONL-011 - BR-ONL-019 |
| Account view (COACTVWC) | BR-ONL-020 - BR-ONL-027 |
| Account update (COACTUPC, with CSUTLDPY / CSLKPCDY) | BR-ONL-028 - BR-ONL-059 |
| HLASM (COBDATFT, MVSWAIT) | BR-ONL-060 - BR-ONL-064 |
| PL/I late fees (CBFEECAL via CBFEEDRV, FEECALC.jcl) | BR-ONL-065 - BR-ONL-074 |

| Category | Count |
|---|---|
| calculation | 7 |
| control-flow | 11 |
| data-access | 4 |
| edge-case | 6 |
| linkage | 3 |
| return-code | 7 |
| rounding | 3 |
| state-change | 5 |
| sysout | 2 |
| validation | 26 |
| **total** | **74** |

## Sign-on (COSGN00C)

### BR-ONL-001 - Sign-on first display

When the sign-on transaction CC00 starts with no communication area, a blank sign-on screen is sent with the cursor in the User ID field.

- **Source:** `app/cbl/COSGN00C.cbl:80-83`
- **Fields:** EIBCALEN (CICS EIB); USERIDL of COSGN0AI (COSGN00 BMS); COSGN0AO (COSGN00 BMS)
- **Category:** control-flow
- **Test:** *Given* A terminal with no CardDemo session (EIBCALEN = 0); *when* Transaction CC00 is started; *then* Map COSGN0A is sent erased with an empty ERRMSG and the cursor on USERID.

### BR-ONL-002 - Sign-on PF3 ends the session

On the sign-on screen PF3 sends the plain-text message 'Thank you for using CardDemo application...' and ends the task without re-arming CC00.

- **Source:** `app/cbl/COSGN00C.cbl:88-90`, `app/cbl/COSGN00C.cbl:162-172`, `app/cpy/CSMSG01Y.cpy:18-19`
- **Fields:** EIBAID (CICS EIB); CCDA-MSG-THANK-YOU (CSMSG01Y); WS-MESSAGE (COSGN00C)
- **Category:** control-flow
- **Test:** *Given* The sign-on screen is displayed; *when* The user presses PF3; *then* The screen is erased and shows 'Thank you for using CardDemo application...'; no next transaction is set.

### BR-ONL-003 - Sign-on rejects keys other than ENTER and PF3

Any attention key other than ENTER or PF3 on the sign-on screen redisplays it with 'Invalid key pressed. Please see below...'.

- **Source:** `app/cbl/COSGN00C.cbl:91-94`, `app/cpy/CSMSG01Y.cpy:20-21`
- **Fields:** EIBAID (CICS EIB); CCDA-MSG-INVALID-KEY (CSMSG01Y); ERRMSGO of COSGN0AO (COSGN00 BMS)
- **Category:** validation
- **Test:** *Given* The sign-on screen is displayed; *when* The user presses PF7; *then* The sign-on screen is resent with ERRMSG 'Invalid key pressed. Please see below...'.

### BR-ONL-004 - User ID is mandatory

If the User ID field is spaces or low-values the sign-on screen is redisplayed with 'Please enter User ID ...' and the cursor on User ID; the security file is not read.

- **Source:** `app/cbl/COSGN00C.cbl:117-122`, `app/cbl/COSGN00C.cbl:138-140`
- **Fields:** USERIDI of COSGN0AI (COSGN00 BMS, 8 chars); WS-ERR-FLG (COSGN00C)
- **Category:** validation
- **Test:** *Given* User ID blank, Password 'PASSW0RD'; *when* ENTER is pressed; *then* ERRMSG 'Please enter User ID ...', cursor on USERID, no READ of USRSEC.

### BR-ONL-005 - Password is mandatory

If a User ID is entered but the Password field is spaces or low-values the screen is redisplayed with 'Please enter Password ...' and the cursor on Password.

- **Source:** `app/cbl/COSGN00C.cbl:123-127`
- **Fields:** PASSWDI of COSGN0AI (COSGN00 BMS, 8 chars, dark); WS-ERR-FLG (COSGN00C)
- **Category:** validation
- **Test:** *Given* User ID 'USER0001', Password blank; *when* ENTER is pressed; *then* ERRMSG 'Please enter Password ...', cursor on PASSWD.

### BR-ONL-006 - Credentials are case-insensitive

Both the User ID and the Password typed by the user are converted to upper case before the security record is read and compared, so sign-on is case-insensitive.

- **Source:** `app/cbl/COSGN00C.cbl:132-136`, `app/cbl/COSGN00C.cbl:209-223`
- **Fields:** USERIDI / PASSWDI (COSGN00 BMS); WS-USER-ID, WS-USER-PWD (COSGN00C); SEC-USR-ID, SEC-USR-PWD (CSUSR01Y)
- **Category:** edge-case
- **Test:** *Given* USRSEC holds SEC-USR-ID 'USER0001' with SEC-USR-PWD 'PASSW0RD'; *when* The user signs on as 'user0001' / 'passw0rd'; *then* The record is found and the password matches; sign-on succeeds.

### BR-ONL-007 - Unknown user

The USRSEC file is read by User ID; a NOTFND response (RESP 13) redisplays the screen with 'User not found. Try again ...' and the cursor on User ID.

- **Source:** `app/cbl/COSGN00C.cbl:209-221`, `app/cbl/COSGN00C.cbl:247-251`
- **Fields:** WS-USRSEC-FILE = 'USRSEC' (COSGN00C); WS-RESP-CD (COSGN00C); SEC-USER-DATA (CSUSR01Y)
- **Category:** data-access
- **Test:** *Given* USRSEC has no record for 'GHOST001'; *when* The user signs on as 'GHOST001' / 'ANYPASS1'; *then* ERRMSG 'User not found. Try again ...', cursor on USERID.

### BR-ONL-008 - Security file error

Any USRSEC READ response other than NORMAL or NOTFND redisplays the sign-on screen with 'Unable to verify the User ...'.

- **Source:** `app/cbl/COSGN00C.cbl:252-256`
- **Fields:** WS-RESP-CD, WS-REAS-CD (COSGN00C)
- **Category:** return-code
- **Test:** *Given* The USRSEC file is closed (READ returns NOTOPEN, RESP 19); *when* A user signs on; *then* ERRMSG 'Unable to verify the User ...', cursor on USERID.

### BR-ONL-009 - Wrong password

If the user record exists but the stored password differs from the upper-cased entered password, the screen is redisplayed with 'Wrong Password. Try again ...' and the cursor on Password.

- **Source:** `app/cbl/COSGN00C.cbl:223`, `app/cbl/COSGN00C.cbl:241-246`
- **Fields:** SEC-USR-PWD (CSUSR01Y); WS-USER-PWD (COSGN00C); PASSWDL of COSGN0AI (COSGN00 BMS)
- **Category:** validation
- **Test:** *Given* USRSEC 'USER0001' has password 'PASSW0RD'; *when* The user enters 'USER0001' / 'WRONG123'; *then* ERRMSG 'Wrong Password. Try again ...', cursor on PASSWD.

### BR-ONL-010 - Successful sign-on routes by user type

On a correct password the commarea is filled (from-tran CC00, from-program COSGN00C, user id, user type, program context 0) and control is transferred to COADM01C for user type 'A' or COMEN01C for any other type.

- **Source:** `app/cbl/COSGN00C.cbl:222-240`, `app/cpy/COCOM01Y.cpy:19-31`
- **Fields:** SEC-USR-TYPE (CSUSR01Y); CDEMO-FROM-TRANID, CDEMO-FROM-PROGRAM, CDEMO-USER-ID, CDEMO-USER-TYPE, CDEMO-PGM-CONTEXT (COCOM01Y); CDEMO-USRTYP-ADMIN (COCOM01Y)
- **Category:** control-flow
- **Test:** *Given* USRSEC 'ADMIN001' type 'A' and 'USER0001' type 'U', both with correct passwords; *when* Each user signs on; *then* ADMIN001 is XCTLed to COADM01C; USER0001 is XCTLed to COMEN01C; both with CDEMO-USER-TYPE copied and CDEMO-PGM-CONTEXT = 0.

## Main menu (COMEN01C)

### BR-ONL-011 - Menu requires a session

If the main menu (CM00/COMEN01C) is started without a commarea it transfers control to the sign-on program COSGN00C.

- **Source:** `app/cbl/COMEN01C.cbl:82-84`, `app/cbl/COMEN01C.cbl:196-203`
- **Fields:** EIBCALEN (CICS EIB); CDEMO-TO-PROGRAM (COCOM01Y)
- **Category:** control-flow
- **Test:** *Given* No CardDemo commarea; *when* Transaction CM00 is entered directly; *then* XCTL to COSGN00C.

### BR-ONL-012 - Menu first display lists eleven options

On first entry (context not re-enter) the menu marks the commarea as re-enter and lists the options from COMEN02Y, each formatted as 'NN. <option name>' (11 options, slots OPTN001-OPTN012).

- **Source:** `app/cbl/COMEN01C.cbl:86-90`, `app/cbl/COMEN01C.cbl:262-303`, `app/cpy/COMEN02Y.cpy:21-98`
- **Fields:** CDEMO-PGM-CONTEXT (COCOM01Y); CDEMO-MENU-OPT-COUNT, CDEMO-MENU-OPT-NUM, CDEMO-MENU-OPT-NAME (COMEN02Y); OPTN001O..OPTN012O (COMEN01 BMS)
- **Category:** control-flow
- **Test:** *Given* A user arrives from COSGN00C with CDEMO-PGM-CONTEXT = 0; *when* COMEN01C runs; *then* OPTN001O reads '01. Account View' ... OPTN011O reads '11. Pending Authorization View'; context becomes 1.

### BR-ONL-013 - Menu option is right-justified and zero-filled

The 2-character option is trimmed of trailing spaces, right-justified and spaces are replaced by zeros before it is used as a number, so '1 ' and ' 1' both mean option 01.

- **Source:** `app/cbl/COMEN01C.cbl:117-125`
- **Fields:** OPTIONI of COMEN1AI (COMEN01 BMS, 2 chars NUM); WS-OPTION-X (JUST RIGHT), WS-OPTION 9(02) (COMEN01C)
- **Category:** edge-case
- **Test:** *Given* The menu is displayed; *when* The user types '2' followed by a space and presses ENTER; *then* WS-OPTION = 02 and OPTIONO is redisplayed as '02'.

### BR-ONL-014 - Menu option range check

An option that is not numeric, is zero, or is greater than the configured option count (11) is rejected with 'Please enter a valid option number...'.

- **Source:** `app/cbl/COMEN01C.cbl:127-134`, `app/cpy/COMEN02Y.cpy:21`
- **Fields:** WS-OPTION (COMEN01C); CDEMO-MENU-OPT-COUNT = 11 (COMEN02Y)
- **Category:** validation
- **Test:** *Given* The menu is displayed; *when* The user enters '12'; *then* ERRMSG 'Please enter a valid option number...'; no transfer.

### BR-ONL-015 - Admin-only menu options

A regular user (type 'U') who selects an option flagged 'A' in COMEN02Y gets 'No access - Admin Only option... '. All eleven shipped options are flagged 'U', so this edit is currently never triggered.

- **Source:** `app/cbl/COMEN01C.cbl:136-143`, `app/cpy/COMEN02Y.cpy:29-90`
- **Fields:** CDEMO-USRTYP-USER (COCOM01Y); CDEMO-MENU-OPT-USRTYPE (COMEN02Y)
- **Category:** validation
- **Test:** *Given* An options table in which option 08 carries user type 'A', signed-on user type 'U'; *when* The user selects 08; *then* ERRMSG 'No access - Admin Only option... '; no transfer.

### BR-ONL-016 - Pending-authorization option checks installation

For option 11 (program COPAUS0C) the menu first asks CICS whether the program is installed; if not, it shows in red 'This option Pending Authorization View is not installed...' instead of transferring.

- **Source:** `app/cbl/COMEN01C.cbl:147-168`, `app/cpy/COMEN02Y.cpy:86-90`
- **Fields:** CDEMO-MENU-OPT-PGMNAME, CDEMO-MENU-OPT-NAME (COMEN02Y); EIBRESP (CICS EIB); ERRMSGC of COMEN1AO (COMEN01 BMS)
- **Category:** edge-case
- **Test:** *Given* COPAUS0C is not defined to the CICS region; *when* The user selects option 11; *then* ERRMSG (red) 'This option Pending Authorization View is not installed...'.

### BR-ONL-017 - Placeholder options show 'coming soon'

An option whose program name starts with 'DUMMY' shows in green 'This option <first word of the name>is coming soon ...'; the name is cut at its first space and no space precedes 'is'. No shipped option uses DUMMY.

- **Source:** `app/cbl/COMEN01C.cbl:169-176`
- **Fields:** CDEMO-MENU-OPT-PGMNAME, CDEMO-MENU-OPT-NAME (COMEN02Y); ERRMSGC of COMEN1AO (COMEN01 BMS)
- **Category:** edge-case
- **Test:** *Given* An options table entry 'Statement Print' with program 'DUMMY001'; *when* The user selects it; *then* ERRMSG (green) 'This option Statementis coming soon ...'.

### BR-ONL-018 - Valid menu option transfers control

A valid, permitted option transfers control (XCTL) to the option's program with from-tran CM00, from-program COMEN01C and program context 0 (first entry).

- **Source:** `app/cbl/COMEN01C.cbl:177-187`
- **Fields:** CDEMO-MENU-OPT-PGMNAME (COMEN02Y); CDEMO-FROM-TRANID, CDEMO-FROM-PROGRAM, CDEMO-PGM-CONTEXT (COCOM01Y)
- **Category:** control-flow
- **Test:** *Given* Signed-on user type 'U' on the menu; *when* The user enters '1'; *then* XCTL to COACTVWC with CDEMO-FROM-PROGRAM 'COMEN01C', CDEMO-FROM-TRANID 'CM00', CDEMO-PGM-CONTEXT 0.

### BR-ONL-019 - Menu PF3 and invalid keys

On the menu PF3 returns to the sign-on program COSGN00C; any key other than ENTER or PF3 redisplays the menu with 'Invalid key pressed. Please see below...'.

- **Source:** `app/cbl/COMEN01C.cbl:93-103`, `app/cbl/COMEN01C.cbl:196-203`
- **Fields:** EIBAID (CICS EIB); CDEMO-TO-PROGRAM (COCOM01Y); CCDA-MSG-INVALID-KEY (CSMSG01Y)
- **Category:** control-flow
- **Test:** *Given* The menu is displayed; *when* The user presses PF3, then (in a second test) PF5; *then* PF3: XCTL to COSGN00C. PF5: ERRMSG 'Invalid key pressed. Please see below...'.

## Account view (COACTVWC)

### BR-ONL-020 - Account view entry and PF-key mapping

Account View (CAVW/COACTVWC) starts fresh when entered without a commarea or freshly from the menu. Only ENTER and PF3 are honoured; every other key is treated as ENTER, and PF13-PF24 are folded onto PF1-PF12 (so PF15 acts as PF3).

- **Source:** `app/cbl/COACTVWC.cbl:282-314`, `app/cpy/CSSTRPFY.cpy:21-78`
- **Fields:** CDEMO-FROM-PROGRAM, CDEMO-PGM-CONTEXT (COCOM01Y); CCARD-AID (CVCRD01Y); EIBAID (CICS EIB)
- **Category:** control-flow
- **Test:** *Given* Account View is displayed after a previous search; *when* The user presses PF8; *then* PF8 is treated as ENTER: the account id on the screen is re-edited and re-read.

### BR-ONL-021 - Account view PF3 return

PF3 returns to the calling transaction/program recorded in the commarea, or to the main menu (CM00/COMEN01C) when none is recorded; the commarea is marked from CAVW/COACTVWC, user type is set to 'U' and context to first entry.

- **Source:** `app/cbl/COACTVWC.cbl:323-352`
- **Fields:** CDEMO-FROM-TRANID, CDEMO-FROM-PROGRAM, CDEMO-TO-TRANID, CDEMO-TO-PROGRAM, CDEMO-USER-TYPE, CDEMO-LAST-MAP, CDEMO-LAST-MAPSET (COCOM01Y)
- **Category:** state-change
- **Test:** *Given* Account View was entered from COMEN01C/CM00; *when* The user presses PF3; *then* XCTL to COMEN01C with CDEMO-FROM-PROGRAM 'COACTVWC', CDEMO-USER-TYPE 'U', CDEMO-PGM-CONTEXT 0.

### BR-ONL-022 - Account view blank search key

An account id of spaces or '*' is treated as not supplied: the message shown is 'No input received' (it overwrites 'Account number not provided'), and on re-entry the field is shown as a red '*'.

- **Source:** `app/cbl/COACTVWC.cbl:628-642`, `app/cbl/COACTVWC.cbl:653-662`, `app/cbl/COACTVWC.cbl:561-565`
- **Fields:** ACCTSIDI / ACCTSIDO / ACCTSIDC (COACTVW BMS, 11 chars); CC-ACCT-ID (CVCRD01Y); WS-RETURN-MSG (COACTVWC)
- **Category:** validation
- **Test:** *Given* Account View is displayed; *when* ENTER is pressed with the account id blank; *then* ERRMSG 'No input received', ACCTSID shows '*' in red, no file is read.

### BR-ONL-023 - Account view key must be a non-zero 11-digit number

A supplied account id that is not all digits or is all zeros is rejected with 'Account Filter must  be a non-zero 11 digit number' (two spaces after 'must').

- **Source:** `app/cbl/COACTVWC.cbl:666-680`
- **Fields:** CC-ACCT-ID X(11) (CVCRD01Y); CDEMO-ACCT-ID (COCOM01Y)
- **Category:** validation
- **Test:** *Given* Account View is displayed; *when* The user enters '1234' (padded with spaces) or '00000000000'; *then* ERRMSG 'Account Filter must  be a non-zero 11 digit number', field coloured red, CDEMO-ACCT-ID = 0.

### BR-ONL-024 - Account lookup chain: xref, account, customer

A valid account id is looked up in the card cross-reference via its account alternate index (CXACAIX) to obtain the customer id and card number, then the account master (ACCTDAT) by account id, then the customer master (CUSTDAT) by the customer id from the cross-reference.

- **Source:** `app/cbl/COACTVWC.cbl:687-720`, `app/cbl/COACTVWC.cbl:723-740`, `app/cbl/COACTVWC.cbl:774-788`, `app/cbl/COACTVWC.cbl:825-838`
- **Fields:** XREF-CUST-ID, XREF-CARD-NUM (CVACT03Y); ACCOUNT-RECORD (CVACT01Y); CUSTOMER-RECORD (CVCUS01Y); CDEMO-CUST-ID, CDEMO-CARD-NUM (COCOM01Y)
- **Category:** data-access
- **Test:** *Given* Xref: account 00000000099 -> customer 000000099, card 4000000000000099; matching ACCTDAT and CUSTDAT records; *when* The user views account 00000000099; *then* Account and customer details are displayed; CDEMO-CUST-ID = 000000099 and CDEMO-CARD-NUM = 4000000000000099.

### BR-ONL-025 - Account view not-found messages

Not-found responses build a message with the key and CICS RESP/RESP2 as 9-digit zero-padded values, cut to the 75-character message area: 'Account:<id> not found in Cross ref file.  Resp:<resp> Reas:<resp2>', 'Account:<id> not found in Acct Master file.Resp:...', 'CustId:<id> not found in customer master.Resp: ... REAS:...'. Only the first message of a request is kept.

- **Source:** `app/cbl/COACTVWC.cbl:741-758`, `app/cbl/COACTVWC.cbl:789-807`, `app/cbl/COACTVWC.cbl:839-857`
- **Fields:** WS-RETURN-MSG X(75), ERROR-RESP, ERROR-RESP2 (COACTVWC); WS-CARD-RID-ACCT-ID-X, WS-CARD-RID-CUST-ID-X (COACTVWC)
- **Category:** return-code
- **Test:** *Given* No xref record for account 00000000077 (READ returns RESP 13, RESP2 80); *when* The user views account 00000000077; *then* ERRMSG 'Account:00000000077 not found in Cross ref file.  Resp:000000013  Reas:0000' (truncated at 75 characters).

### BR-ONL-026 - Account view file errors

Any other READ response sets the message 'File Error: READ on <file> returned RESP <resp>,RESP2 <resp2>' (file name padded to 9, numbers 9-digit zero-padded).

- **Source:** `app/cbl/COACTVWC.cbl:86-105`, `app/cbl/COACTVWC.cbl:759-766`, `app/cbl/COACTVWC.cbl:809-816`, `app/cbl/COACTVWC.cbl:858-865`
- **Fields:** WS-FILE-ERROR-MESSAGE, ERROR-OPNAME, ERROR-FILE (COACTVWC)
- **Category:** return-code
- **Test:** *Given* ACCTDAT is disabled (READ returns DISABLED, RESP 84, RESP2 0); *when* A valid account id with an xref record is viewed; *then* ERRMSG 'File Error: READ     on ACCTDAT   returned RESP 000000084 ,RESP2 000000000'.

### BR-ONL-027 - Account view display formatting

Account fields are displayed when the account or the customer was found; customer fields only when the customer was found. The SSN is shown as NNN-NN-NNNN; the information line is always 'Enter or update id of account to display'.

- **Source:** `app/cbl/COACTVWC.cbl:471-523`, `app/cbl/COACTVWC.cbl:528-534`, `app/cbl/COACTVWC.cbl:689`
- **Fields:** ACCT-* (CVACT01Y) -> ACSTTUSO..AADDGRPO (COACTVW BMS); CUST-SSN 9(09) (CVCUS01Y) -> ACSTSSNO (COACTVW BMS); WS-INFO-MSG (COACTVWC)
- **Category:** calculation
- **Test:** *Given* Customer 000000099 with CUST-SSN 123456789; *when* Account 00000000099 is viewed; *then* ACSTSSNO shows '123-45-6789'; INFOMSG shows 'Enter or update id of account to display'.

## Account update (COACTUPC, with CSUTLDPY / CSLKPCDY)

### BR-ONL-028 - Account update PF-key validity

In Account Update (CAUP/COACTUPC) ENTER and PF3 are always valid; PF5 only while validated changes await confirmation; PF12 only once account details have been fetched. Any other key is treated as ENTER.

- **Source:** `app/cbl/COACTUPC.cbl:905-916`
- **Fields:** CCARD-AID (CVCRD01Y); ACUP-CHANGE-ACTION (COACTUPC commarea)
- **Category:** control-flow
- **Test:** *Given* Account details shown, changes not yet validated (state 'S'); *when* The user presses PF5; *then* PF5 is treated as ENTER: inputs are edited, nothing is saved.

### BR-ONL-029 - Account update state machine

The update screen keeps its state in ACUP-CHANGE-ACTION: low-values/spaces = details not fetched, 'S' = details shown, 'E' = changes have errors, 'N' = changes valid but not confirmed, 'C' = saved, 'L' = lock error, 'F' = save failed. The information line follows the state: 'Enter or update id of account to update', 'Update account details presented above.', 'Changes validated.Press F5 to save', 'Changes committed to database', 'Changes unsuccessful. Please try again'.

- **Source:** `app/cbl/COACTUPC.cbl:654-668`, `app/cbl/COACTUPC.cbl:463-477`, `app/cbl/COACTUPC.cbl:2955-2981`
- **Fields:** ACUP-CHANGE-ACTION (COACTUPC); WS-INFO-MSG (COACTUPC); INFOMSGO (COACTUP BMS)
- **Category:** state-change
- **Test:** *Given* State 'E' after an edit failure; *when* The user corrects the field and presses ENTER; *then* State becomes 'N' and INFOMSG reads 'Changes validated.Press F5 to save'.

### BR-ONL-030 - Account update PF3 exit commits

PF3 returns to the caller (or the menu if none), forces user type 'U' and first-entry context, and issues a SYNCPOINT before the XCTL.

- **Source:** `app/cbl/COACTUPC.cbl:927-959`
- **Fields:** CDEMO-TO-PROGRAM, CDEMO-TO-TRANID, CDEMO-USER-TYPE, CDEMO-PGM-CONTEXT (COCOM01Y)
- **Category:** state-change
- **Test:** *Given* Account Update entered from the menu; *when* The user presses PF3; *then* SYNCPOINT, then XCTL to COMEN01C with CDEMO-FROM-PROGRAM 'COACTUPC', CDEMO-USER-TYPE 'U'.

### BR-ONL-031 - Account update fresh entry and post-save reset

A fresh entry (no commarea, or from the menu) shows the empty search screen. After a save attempt ends in 'C', 'L' or 'F', the next interaction clears the screen data and the account id and shows the search screen again.

- **Source:** `app/cbl/COACTUPC.cbl:880-893`, `app/cbl/COACTUPC.cbl:964-973`, `app/cbl/COACTUPC.cbl:979-989`
- **Fields:** WS-THIS-PROGCOMMAREA, ACUP-CHANGE-ACTION (COACTUPC); CDEMO-ACCT-ID (COCOM01Y)
- **Category:** state-change
- **Test:** *Given* State 'C' after a successful save of account 00000000099; *when* The user presses ENTER; *then* Search screen is shown with empty fields, CDEMO-ACCT-ID = 0, INFOMSG 'Enter or update id of account to update'.

### BR-ONL-032 - Account update search key edit

Blank or '*' account id gives 'No input received' (overriding 'Account number not provided'); a non-numeric or all-zero id gives 'Account Number if supplied must be a 11 digit Non-Zero Number'.

- **Source:** `app/cbl/COACTUPC.cbl:1433-1446`, `app/cbl/COACTUPC.cbl:1783-1820`
- **Fields:** ACCTSIDI (COACTUP BMS, 11 chars); CC-ACCT-ID, CC-ACCT-ID-N (CVCRD01Y); ACUP-NEW-ACCT-ID (COACTUPC)
- **Category:** validation
- **Test:** *Given* The update search screen; *when* The user enters 'ABC00000001'; *then* ERRMSG 'Account Number if supplied must be a 11 digit Non-Zero Number'; nothing is read.

### BR-ONL-033 - Account update fetch and snapshot

A valid account id is looked up xref -> account -> customer exactly as in Account View; the fetched values are saved as the 'old' snapshot (dates split into year/month/day, SSN and phones kept as stored) and the account id, customer id/names, status and card number are copied to the commarea.

- **Source:** `app/cbl/COACTUPC.cbl:2568-2580`, `app/cbl/COACTUPC.cbl:3608-3647`, `app/cbl/COACTUPC.cbl:3801-3885`
- **Fields:** ACUP-OLD-DETAILS (COACTUPC); ACCOUNT-RECORD (CVACT01Y); CUSTOMER-RECORD (CVCUS01Y); CDEMO-ACCT-ID, CDEMO-CUST-ID, CDEMO-CUST-FNAME/MNAME/LNAME, CDEMO-ACCT-STATUS, CDEMO-CARD-NUM (COCOM01Y)
- **Category:** data-access
- **Test:** *Given* ACCTDAT 00000000099 with ACCT-OPEN-DATE '2019-03-15'; *when* The user searches 00000000099; *then* ACUP-OLD-OPEN-YEAR '2019', MON '03', DAY '15'; state 'S'; INFOMSG 'Update account details presented above.'.

### BR-ONL-034 - Blank or '*' fields mean 'not supplied'

Every input field on the update screen that is spaces or a single '*' is stored as low-values (not supplied) before editing; money fields that pass a currency-numeric test are converted to a signed 10.2 value.

- **Source:** `app/cbl/COACTUPC.cbl:1051-1058`, `app/cbl/COACTUPC.cbl:1065-1140`, `app/cbl/COACTUPC.cbl:1419-1424`
- **Fields:** ACSTTUSI..ACSPFLGI (COACTUP BMS); ACUP-NEW-DETAILS (COACTUPC)
- **Category:** edge-case
- **Test:** *Given* Account details shown; *when* The user replaces the credit limit with '*'; *then* ACUP-NEW-CREDIT-LIMIT-X = low-values and the edit reports 'Credit Limit must be supplied.'.

### BR-ONL-035 - Money input conversion truncates

Money inputs are converted with NUMVAL-C (currency sign and commas allowed) into PIC S9(10)V99 without ROUNDED, so digits beyond the cents are dropped.

- **Source:** `app/cbl/COACTUPC.cbl:1073-1084`, `app/cbl/COACTUPC.cbl:763-795`
- **Fields:** ACRDLIMI, ACSHLIMI, ACURBALI, ACRCYCRI, ACRCYDBI (COACTUP BMS, 15 chars); ACUP-NEW-CREDIT-LIMIT-N etc. S9(10)V99 (COACTUPC)
- **Category:** rounding
- **Test:** *Given* Account details shown; *when* The user enters credit limit '$2,500.759'; *then* ACUP-NEW-CREDIT-LIMIT-N = 2500.75 (truncated, not 2500.76).

### BR-ONL-036 - Change detection before editing

Before any field edit, new values are compared with the snapshot: account money/date fields exactly, status case-insensitively, group id and customer text fields trimmed and case-insensitive, phones/SSN/DOB/EFT/FICO exactly. If nothing changed the message is 'No change detected with respect to values fetched.' and no edits run.

- **Source:** `app/cbl/COACTUPC.cbl:1460-1468`, `app/cbl/COACTUPC.cbl:1681-1777`, `app/cbl/COACTUPC.cbl:491-492`
- **Fields:** ACUP-OLD-DETAILS, ACUP-NEW-DETAILS (COACTUPC); WS-DATACHANGED-FLAG (COACTUPC)
- **Category:** validation
- **Test:** *Given* Fetched first name 'AVERY'; *when* The user retypes it as 'avery ' and presses ENTER; *then* No change is detected; ERRMSG 'No change detected with respect to values fetched.'.

### BR-ONL-037 - First error wins; all edits run

Once changes are detected all field edits run in screen order (status, open date, credit limit, expiry date, cash limit, reissue date, balance, cycle credit, cycle debit, SSN, DOB, FICO, names, address, state, zip, city, country, phones, EFT id, primary holder, state/zip) but only the first failing edit's message is shown; any failure sets state 'E'.

- **Source:** `app/cbl/COACTUPC.cbl:1470-1675`
- **Fields:** WS-RETURN-MSG, INPUT-ERROR (COACTUPC); ACUP-CHANGES-NOT-OK (COACTUPC)
- **Category:** control-flow
- **Test:** *Given* Account details shown; *when* The user blanks both Account Status and First Name; *then* ERRMSG 'Account Status must be supplied.' (first edit); state 'E'.

### BR-ONL-038 - Account status and primary card holder must be Y or N

Account Status and Primary Card Holder must be upper-case 'Y' or 'N': blank/zero gives '<field> must be supplied.', anything else '<field> must be Y or N.'.

- **Source:** `app/cbl/COACTUPC.cbl:1472-1476`, `app/cbl/COACTUPC.cbl:1657-1662`, `app/cbl/COACTUPC.cbl:1856-1894`
- **Fields:** ACSTTUSI, ACSPFLGI (COACTUP BMS, 1 char); WS-EDIT-YES-NO (COACTUPC)
- **Category:** validation
- **Test:** *Given* Account details shown with status 'Y'; *when* The user changes status to 'X'; *then* ERRMSG 'Account Status must be Y or N.'.

### BR-ONL-039 - Signed money fields are required and numeric

Credit Limit, Cash Credit Limit, Current Balance, cycle credit and cycle debit must be supplied and pass a currency-numeric test; messages are '<name> must be supplied.' or '<name> is not valid'. The cycle fields are labelled 'Current Cycle Credit Limit' and 'Current Cycle Debit Limit'. No range or sign limit and no cross-field check apply.

- **Source:** `app/cbl/COACTUPC.cbl:1484-1527`, `app/cbl/COACTUPC.cbl:2180-2221`
- **Fields:** ACUP-NEW-CREDIT-LIMIT-X ... ACUP-NEW-CURR-CYC-DEBIT-X (COACTUPC); WS-EDIT-SIGNED-NUMBER-9V2-X (COACTUPC)
- **Category:** validation
- **Test:** *Given* Account details shown; *when* The user enters cash credit limit '12O0.00' (letter O); *then* ERRMSG 'Cash Credit Limit is not valid'.

### BR-ONL-040 - Date edit: year and century

For Open, Expiry, Reissue and Birth dates the year must be supplied ('<field> : Year must be supplied.'), be 4 digits ('<field> must be 4 digit number.') and start with 19 or 20 ('<field> : Century is not valid.').

- **Source:** `app/cpy/CSUTLDPY.cpy:18-88`, `app/cbl/COACTUPC.cbl:1478-1482`
- **Fields:** OPNYEARI, EXPYEARI, RISYEARI, DOBYEARI (COACTUP BMS); WS-EDIT-DATE-CCYY, THIS-CENTURY, LAST-CENTURY (CSUTLDWY)
- **Category:** validation
- **Test:** *Given* Account details shown; *when* The user enters open year '2124'; *then* ERRMSG 'Open Date : Century is not valid.'.

### BR-ONL-041 - Date edit: month and day ranges

Month must be supplied and be 1-12 ('<field>: Month must be a number between 1 and 12.'); day must be supplied and be 1-31 ('<field>:day must be a number between 1 and 31.').

- **Source:** `app/cpy/CSUTLDPY.cpy:91-205`
- **Fields:** OPNMONI, OPNDAYI etc. (COACTUP BMS); WS-EDIT-DATE-MM, WS-EDIT-DATE-DD, WS-VALID-MONTH, WS-VALID-DAY (CSUTLDWY)
- **Category:** validation
- **Test:** *Given* Account details shown; *when* The user enters expiry month '13'; *then* ERRMSG 'Expiry Date: Month must be a number between 1 and 12.'.

### BR-ONL-042 - Date edit: day/month combinations and leap years

Day 31 is rejected in 30-day months ('<field>:Cannot have 31 days in this month.'), 30 February is rejected, and 29 February is accepted only in leap years (century years must divide by 400, other years by 4), otherwise '<field>:Not a leap year.Cannot have 29 days in this month.'. The date is finally checked by the CSUTLDTC (Language Environment) service.

- **Source:** `app/cpy/CSUTLDPY.cpy:209-323`
- **Fields:** WS-31-DAY-MONTH, WS-FEBRUARY, WS-DAY-29/30/31 (CSUTLDWY); WS-DIV-BY, WS-REMAINDER (COACTUPC)
- **Category:** validation
- **Test:** *Given* Account details shown; *when* The user enters reissue date 1900-02-29, then (second test) 2000-02-29; *then* 1900-02-29: ERRMSG 'Reissue Date:Not a leap year.Cannot have 29 days in this month.'; 2000-02-29 passes this edit.

### BR-ONL-043 - Date of birth cannot be today or later

After a valid date edit the date of birth must be strictly before today's date, else 'Date of Birth:cannot be in the future '.

- **Source:** `app/cbl/COACTUPC.cbl:1533-1543`, `app/cpy/CSUTLDPY.cpy:341-370`
- **Fields:** DOBYEARI, DOBMONI, DOBDAYI (COACTUP BMS); WS-EDIT-DATE-BINARY, WS-CURRENT-DATE-BINARY (CSUTLDWY)
- **Category:** validation
- **Test:** *Given* Today is 2022-07-05; *when* The user enters date of birth 2022-07-05; *then* ERRMSG 'Date of Birth:cannot be in the future '.

### BR-ONL-044 - SSN edit

The SSN's three parts (3, 2, 4 characters) must each be supplied, all numeric and not zero; the first part may not be 000, 666 or 900-999 ('SSN: First 3 chars: should not be 000, 666, or between 900 and 999').

- **Source:** `app/cbl/COACTUPC.cbl:2431-2489`, `app/cbl/COACTUPC.cbl:117-131`, `app/cbl/COACTUPC.cbl:2109-2176`
- **Fields:** ACTSSN1I, ACTSSN2I, ACTSSN3I (COACTUP BMS); ACUP-NEW-CUST-SSN-1/2/3 (COACTUPC); INVALID-SSN-PART1 (COACTUPC)
- **Category:** validation
- **Test:** *Given* Account details shown; *when* The user enters SSN 666-12-3456; *then* ERRMSG 'SSN: First 3 chars: should not be 000, 666, or between 900 and 999'.

### BR-ONL-045 - FICO score range

The FICO score must be a supplied, non-zero 3-digit number between 300 and 850 inclusive ('FICO Score: should be between 300 and 850').

- **Source:** `app/cbl/COACTUPC.cbl:1545-1556`, `app/cbl/COACTUPC.cbl:2514-2531`, `app/cbl/COACTUPC.cbl:848-849`
- **Fields:** ACSTFCOI (COACTUP BMS, 3 chars); ACUP-NEW-CUST-FICO-SCORE, FICO-RANGE-IS-VALID (COACTUPC)
- **Category:** validation
- **Test:** *Given* Account details shown; *when* The user enters FICO '299', then (second test) '850'; *then* '299': ERRMSG 'FICO Score: should be between 300 and 850'; '850' passes.

### BR-ONL-046 - Names must be alphabetic

First Name and Last Name are required and may contain only letters and spaces ('<field> must be supplied.' / '<field> can have alphabets only.'); Middle Name is optional but, if given, letters and spaces only.

- **Source:** `app/cbl/COACTUPC.cbl:1560-1582`, `app/cbl/COACTUPC.cbl:1898-1951`, `app/cbl/COACTUPC.cbl:2012-2057`
- **Fields:** ACSFNAMI, ACSMNAMI, ACSLNAMI (COACTUP BMS, 25 chars); WS-EDIT-ALPHANUM-ONLY, WS-EDIT-ALPHA-ONLY-FLAGS (COACTUPC)
- **Category:** validation
- **Test:** *Given* Account details shown; *when* The user enters last name 'TEST-CASE'; *then* ERRMSG 'Last Name can have alphabets only.'.

### BR-ONL-047 - Address, city and country edits

Address Line 1 is mandatory ('Address Line 1 must be supplied.'); City (address line 3) is required, letters/spaces only; Country is required, letters only (3 chars). Address Line 2, Government ID and Group ID are not edited.

- **Source:** `app/cbl/COACTUPC.cbl:1584-1590`, `app/cbl/COACTUPC.cbl:1615-1630`, `app/cbl/COACTUPC.cbl:1824-1852`
- **Fields:** ACSADL1I, ACSADL2I, ACSCITYI, ACSCTRYI, ACSGOVTI, AADDGRPI (COACTUP BMS)
- **Category:** validation
- **Test:** *Given* Account details shown; *when* The user enters city 'Springfield 2'; *then* ERRMSG 'City can have alphabets only.'.

### BR-ONL-048 - State code must be a US state

State is required, alphabetic, and must be in the US state/territory list (CSLKPCDY), else 'State: is not a valid state code'.

- **Source:** `app/cbl/COACTUPC.cbl:1592-1602`, `app/cbl/COACTUPC.cbl:2493-2511`, `app/cpy/CSLKPCDY.cpy:1012-1069`
- **Fields:** ACSSTTEI (COACTUP BMS, 2 chars); US-STATE-CODE-TO-EDIT, VALID-US-STATE-CODE (CSLKPCDY)
- **Category:** validation
- **Test:** *Given* Account details shown; *when* The user enters state 'XX'; *then* ERRMSG 'State: is not a valid state code'.

### BR-ONL-049 - Zip code and state/zip consistency

The first 5 characters of Zip must be numeric and non-zero ('Zip must be all numeric.' / 'Zip must not be zero.'). When state and zip are each valid, the state plus first two zip digits must be a known combination, else 'Invalid zip code for state'.

- **Source:** `app/cbl/COACTUPC.cbl:1605-1611`, `app/cbl/COACTUPC.cbl:1665-1669`, `app/cbl/COACTUPC.cbl:2536-2558`, `app/cpy/CSLKPCDY.cpy:1071-1313`
- **Fields:** ACSZIPCI (COACTUP BMS, 5 chars); US-STATE-AND-FIRST-ZIP2, VALID-US-STATE-ZIP-CD2-COMBO (CSLKPCDY)
- **Category:** validation
- **Test:** *Given* Account details shown; *when* The user enters state 'NY' and zip '75001'; *then* ERRMSG 'Invalid zip code for state' ('NY75' is not a valid combination; 'NY10' would be).

### BR-ONL-050 - Phone number edit

A phone number left entirely blank is accepted. Otherwise area code, prefix and line number must be supplied, numeric and non-zero, and the area code must be a North American general-purpose code; messages are e.g. 'Phone Number 1: Area code must be supplied.', 'Phone Number 1: Not valid North America general purpose area code'.

- **Source:** `app/cbl/COACTUPC.cbl:2225-2427`, `app/cbl/COACTUPC.cbl:1632-1646`, `app/cpy/CSLKPCDY.cpy:521-930`
- **Fields:** ACSPH1AI/BI/CI, ACSPH2AI/BI/CI (COACTUP BMS); WS-EDIT-US-PHONE-NUM (COACTUPC); VALID-GENERAL-PURP-CODE (CSLKPCDY)
- **Category:** validation
- **Test:** *Given* Account details shown; *when* The user enters phone 1 as 555 / 010 / 0123; *then* ERRMSG 'Phone Number 1: Not valid North America general purpose area code'.

### BR-ONL-051 - EFT account id edit

The EFT Account Id is required and must be 10 numeric digits, not all zero ('EFT Account Id must be all numeric.' / 'EFT Account Id must not be zero.').

- **Source:** `app/cbl/COACTUPC.cbl:1648-1655`, `app/cbl/COACTUPC.cbl:2109-2176`
- **Fields:** ACSEFTCI (COACTUP BMS, 10 chars); ACUP-NEW-CUST-EFT-ACCOUNT-ID (COACTUPC)
- **Category:** validation
- **Test:** *Given* Account details shown; *when* The user enters EFT id '0000000000'; *then* ERRMSG 'EFT Account Id must not be zero.'.

### BR-ONL-052 - Validated changes ask for confirmation

When all edits pass, the state becomes 'N', the information line reads 'Changes validated.Press F5 to save' and the F5=Save and F12=Cancel labels are shown bright.

- **Source:** `app/cbl/COACTUPC.cbl:1671-1675`, `app/cbl/COACTUPC.cbl:2585-2591`, `app/cbl/COACTUPC.cbl:3566-3584`
- **Fields:** ACUP-CHANGES-OK-NOT-CONFIRMED (COACTUPC); FKEY05A, FKEY12A (COACTUP BMS)
- **Category:** state-change
- **Test:** *Given* Account details shown; *when* The user changes credit limit 2000.00 to 2500.00 and presses ENTER; *then* INFOMSG 'Changes validated.Press F5 to save'; F5 and F12 visible.

### BR-ONL-053 - PF12 cancels changes

Once details are fetched, PF12 re-reads the account and customer and redisplays the stored values, discarding unsaved changes.

- **Source:** `app/cbl/COACTUPC.cbl:2572-2580`
- **Fields:** CCARD-AID-PFK12 (CVCRD01Y); ACUP-SHOW-DETAILS (COACTUPC)
- **Category:** control-flow
- **Test:** *Given* State 'N' with credit limit changed to 2500.00 (stored 2000.00); *when* The user presses PF12; *then* Screen shows credit limit '+      2,000.00'; state 'S'.

### BR-ONL-054 - Save locks both records

PF5 in state 'N' reads the account (ACCTDAT) and then the customer (CUSTDAT) for update; failure gives 'Could not lock account record for update' or 'Could not lock customer record for update', state 'L' and 'Changes unsuccessful. Please try again'.

- **Source:** `app/cbl/COACTUPC.cbl:3888-3942`, `app/cbl/COACTUPC.cbl:2602-2610`
- **Fields:** WS-RESP-CD (COACTUPC); COULD-NOT-LOCK-ACCT-FOR-UPDATE, COULD-NOT-LOCK-CUST-FOR-UPDATE (COACTUPC)
- **Category:** data-access
- **Test:** *Given* State 'N'; another task holds account 00000000099 for update and the read fails; *when* The user presses PF5; *then* ERRMSG 'Could not lock account record for update'; INFOMSG 'Changes unsuccessful. Please try again'.

### BR-ONL-055 - Optimistic concurrency check

After locking, the current records are compared with the snapshot taken when they were displayed (case-insensitive for text fields); any difference aborts the save with 'Record changed by some one else. Please review' and returns to state 'S'.

- **Source:** `app/cbl/COACTUPC.cbl:4109-4193`, `app/cbl/COACTUPC.cbl:2611-2612`, `app/cbl/COACTUPC.cbl:521-522`
- **Fields:** ACUP-OLD-DETAILS (COACTUPC); ACCOUNT-RECORD (CVACT01Y); CUSTOMER-RECORD (CVCUS01Y)
- **Category:** validation
- **Test:** *Given* Snapshot balance 150.00; another user changes ACCT-CURR-BAL to 175.00 before PF5; *when* The user presses PF5; *then* Nothing is rewritten; ERRMSG 'Record changed by some one else. Please review'.

### BR-ONL-056 - Saved record formats

On save, dates are written as YYYY-MM-DD, phone numbers as '(AAA)BBB-CCCC' and the SSN as one 9-digit number; all other fields are copied as entered.

- **Source:** `app/cbl/COACTUPC.cbl:3956-4059`
- **Fields:** ACCT-UPDATE-RECORD, CUST-UPDATE-RECORD (COACTUPC); ACUP-NEW-* (COACTUPC)
- **Category:** calculation
- **Test:** *Given* Validated changes: open date 2019/03/15, phone 1 201/555/0142; *when* PF5 is pressed and the save succeeds; *then* ACCT-OPEN-DATE '2019-03-15'; CUST-PHONE-NUM-1 '(201)555-0142  '.

### BR-ONL-057 - Rewrite outcome and rollback

The account is rewritten, then the customer. If the account rewrite fails the state is 'F' ('Update of record failed'); if the customer rewrite fails the unit of work is rolled back and the state is 'F'. Otherwise the state is 'C' with 'Changes committed to database'.

- **Source:** `app/cbl/COACTUPC.cbl:4065-4103`, `app/cbl/COACTUPC.cbl:2613-2614`, `app/cbl/COACTUPC.cbl:523-524`
- **Fields:** LOCKED-BUT-UPDATE-FAILED (COACTUPC); ACUP-CHANGES-OKAYED-AND-DONE, ACUP-CHANGES-OKAYED-BUT-FAILED (COACTUPC)
- **Category:** return-code
- **Test:** *Given* State 'N'; the CUSTDAT REWRITE returns an I/O error; *when* The user presses PF5; *then* SYNCPOINT ROLLBACK (account change undone); INFOMSG 'Changes unsuccessful. Please try again'.

### BR-ONL-058 - Currency display picture

Stored money values are displayed with the picture +ZZZ,ZZZ,ZZZ.99 (leading sign, thousands separators, two decimals).

- **Source:** `app/cbl/COACTUPC.cbl:371`, `app/cbl/COACTUPC.cbl:2797-2811`, `app/cbl/COACTUPC.cbl:2874-2909`
- **Fields:** WS-EDIT-CURRENCY-9-2-F (COACTUPC); ACURBALO, ACRDLIMO, ACSHLIMO, ACRCYCRO, ACRCYDBO (COACTUP BMS)
- **Category:** calculation
- **Test:** *Given* ACCT-CURR-BAL = -1234.50; *when* The account is displayed for update; *then* ACURBALO shows '-      1,234.50'.

### BR-ONL-059 - Unexpected state abends

If the update program reaches an unexpected combination of state and key it abends: the abend data (code '0001', culprit COACTUPC, 'UNEXPECTED DATA SCENARIO') is sent to the terminal and the task ends with abend code 9999.

- **Source:** `app/cbl/COACTUPC.cbl:2633-2640`, `app/cbl/COACTUPC.cbl:4203-4226`
- **Fields:** ABEND-CODE, ABEND-CULPRIT, ABEND-MSG (CSMSG02Y)
- **Category:** return-code
- **Test:** *Given* A commarea whose ACUP-CHANGE-ACTION is 'X' (not a defined state); *when* ENTER is pressed with no field changed; *then* The screen shows the abend data and the task abends with code 9999.

## HLASM (COBDATFT, MVSWAIT)

### BR-ONL-060 - COBDATFT linkage

COBDATFT uses standard OS linkage: saves R14-R12 in the caller's area, chains its own 18-word save area, uses R12 as base, R1 points to a one-entry parameter list whose address (R2) is the CODATECN-REC record. It always returns R15 = 0; errors are reported only in the message field.

- **Source:** `app/asm/COBDATFT.asm:17-29`, `app/asm/COBDATFT.asm:57-64`, `app/maclib/COCDATFT.mac:17-23`, `app/cpy/CODATECN.cpy:17-52`
- **Fields:** COINTYPE CL1, COINPDT CL20, COOUTYPE CL1, COOUTDT CL20, COERMSG CL38 (COCDATFT); CODATECN-TYPE, CODATECN-INP-DATE, CODATECN-OUTTYPE, CODATECN-0UT-DATE, CODATECN-ERROR-MSG (CODATECN)
- **Category:** linkage
- **Test:** *Given* A COBOL caller with CODATECN-REC; *when* It executes CALL 'COBDATFT' USING CODATECN-REC; *then* The record is updated in place and RETURN-CODE is 0. Java equivalent: a static method taking and returning the record (or a DateTimeFormatter conversion) with no exception on bad input.

### BR-ONL-061 - COBDATFT YYYYMMDD to YYYY-MM-DD

Input type '1' (YYYYMMDD) with output type '1' is reformatted to YYYY-MM-DD in the output field.

- **Source:** `app/asm/COBDATFT.asm:30-31`, `app/asm/COBDATFT.asm:35-45`
- **Fields:** COINTYPE, COINPDT, COOUTYPE, COOUTDT (COCDATFT); YYYYMMDD-IN, YYYY-MM-DD-OP (CODATECN)
- **Category:** calculation
- **Test:** *Given* CODATECN-TYPE '1', input '20250315', CODATECN-OUTTYPE '1'; *when* COBDATFT is called; *then* CODATECN-0UT-DATE starts '2025-03-15', error message unchanged.

### BR-ONL-062 - COBDATFT YYYY-MM-DD to YYYYMMDD

Input type '2' (YYYY-MM-DD) with output type '2' is reformatted to YYYYMMDD by taking characters 1-4, 6-7 and 9-10; the separator characters are not checked. CBACT01C uses this for the account reissue date.

- **Source:** `app/asm/COBDATFT.asm:32-33`, `app/asm/COBDATFT.asm:46-54`, `app/cbl/CBACT01C.cbl:223-233`
- **Fields:** COINPDT, COOUTDT (COCDATFT); ACCT-REISSUE-DATE (CVACT01Y); OUT-ACCT-REISSUE-DATE (CBACT01C)
- **Category:** calculation
- **Test:** *Given* CODATECN-TYPE '2', input '2025/03/15', CODATECN-OUTTYPE '2'; *when* COBDATFT is called; *then* CODATECN-0UT-DATE starts '20250315' (the '/' separators are accepted).

### BR-ONL-063 - COBDATFT invalid input

An input type other than '1' or '2', or a mismatched direction (type 1 with output type 2, type 2 with output type 1), moves 'INVALID INPUT' to the message field and leaves the output date unchanged; R15 is still 0.

- **Source:** `app/asm/COBDATFT.asm:30-34`, `app/asm/COBDATFT.asm:36-39`, `app/asm/COBDATFT.asm:49-50`, `app/asm/COBDATFT.asm:55-62`
- **Fields:** COERMSG (COCDATFT); CODATECN-ERROR-MSG (CODATECN)
- **Category:** return-code
- **Test:** *Given* CODATECN-TYPE '2', CODATECN-OUTTYPE '1', output field spaces; *when* COBDATFT is called; *then* CODATECN-ERROR-MSG starts 'INVALID INPUT', output date still spaces, RETURN-CODE 0.

### BR-ONL-064 - MVSWAIT timed wait

COBSWAIT reads an 8-digit wait time in hundredths of a second from SYSIN and passes it as a binary fullword to MVSWAIT, which saves the caller's registers in the caller's save area (no own save area), issues the ASMWAIT (STIMER WAIT) macro, restores registers, flags the save area with X'FF' and returns R15 = 0. Java equivalent: Thread.sleep(n * 10 ms), a no-op in batch tests.

- **Source:** `app/asm/MVSWAIT.asm:17-30`, `app/cbl/COBSWAIT.cbl:30-40`, `app/jcl/WAITSTEP.jcl:22-26`
- **Fields:** MVSWAIT-TIME 9(8) COMP, PARM-VALUE X(8) (COBSWAIT); BINLBL F (MVSWAIT)
- **Category:** linkage
- **Test:** *Given* SYSIN card '00003600'; *when* Job step WAIT runs COBSWAIT; *then* MVSWAIT receives 3600 and the step waits 36 seconds, then ends with RC 0.

## PL/I late fees (CBFEECAL via CBFEEDRV, FEECALC.jcl)

### BR-ONL-065 - CBFEECAL request area and driver

CBFEEDRV reads every account sequentially and, per account, builds the FEE_REQ area (account id, group id, cycle balance = cycle credit - cycle debit, late days 10 if that balance is positive else 0, cycle days 30) and calls the PL/I routine CBFEECAL, which overlays it as a BASED structure: DECIMAL FIXED fields map to COMP-3, FIXED BINARY(15) to COMP halfwords.

- **Source:** `app/pli/CBFEECAL.pli:21-34`, `app/cbl/CBFEEDRV.cbl:54-62`, `app/cbl/CBFEEDRV.cbl:75-101`, `app/jcl/FEECALC.jcl:24-31`
- **Fields:** FEE_REQ (CBFEECAL) / FEE-REQ (CBFEEDRV); ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT, ACCT-GROUP-ID (CVACT01Y)
- **Category:** linkage
- **Test:** *Given* Account 00000000099, group 'STANDARD', cycle credit 1500.00, cycle debit 265.44; *when* CBFEEDRV processes it; *then* FEE-CYCLE-BAL 1234.56, FEE-LATE-DAYS 10, FEE-CYCLE-DAYS 30 are passed to CBFEECAL.

### BR-ONL-066 - Average daily balance truncates

The average daily balance is cycle balance / cycle days assigned to DECIMAL FIXED(12,2), i.e. truncated to cents; it is computed before any other rule, for every account.

- **Source:** `app/pli/CBFEECAL.pli:68-69`
- **Fields:** FEE_CYCLE_BAL, FEE_CYCLE_DAYS, FEE_AVG_DAILY (CBFEECAL)
- **Category:** rounding
- **Test:** *Given* Worked example 2: STANDARD, cycle balance 100.00, late 10, cycle days 30; *when* CBFEECAL runs; *then* FEE_AVG_DAILY = 3.33 (100/30 = 3.333...), FEE_AMT 5.00, FEE_RC 0.

### BR-ONL-067 - Nothing overdue: no fee

If late days are zero or negative the routine returns fee 0.00 and RC 0 (after computing the average daily balance).

- **Source:** `app/pli/CBFEECAL.pli:65-66`, `app/pli/CBFEECAL.pli:71-72`
- **Fields:** FEE_LATE_DAYS, FEE_AMT, FEE_RC (CBFEECAL)
- **Category:** edge-case
- **Test:** *Given* Worked example 6: GOLD, cycle balance 0.00, late 0, cycle days 30; *when* CBFEECAL runs; *then* FEE_AMT 0.00, FEE_AVG_DAILY 0.00, FEE_RC 0.

### BR-ONL-068 - GOLD small-balance waiver

A GOLD account with a cycle balance below 100.00 is waived: fee 0.00, RC 4.

- **Source:** `app/pli/CBFEECAL.pli:74-77`, `app/pli/CBFEECAL.pli:39`
- **Fields:** FEE_GROUP_ID, FEE_CYCLE_BAL, WAIVE_BELOW (CBFEECAL)
- **Category:** validation
- **Test:** *Given* Worked example 4: GOLD, cycle balance 80.00, late 10, cycle days 30; *when* CBFEECAL runs; *then* FEE_AMT 0.00, FEE_AVG_DAILY 2.66, FEE_RC 4.

### BR-ONL-069 - Late fee is 2.5% truncated to cents

The fee is cycle balance x 2.500 / 100, assigned to DECIMAL FIXED(9,2), which truncates (no rounding).

- **Source:** `app/pli/CBFEECAL.pli:36`, `app/pli/CBFEECAL.pli:79-80`
- **Fields:** FEE_PCT, WORK_FEE, FEE_AMT (CBFEECAL)
- **Category:** rounding
- **Test:** *Given* Worked example 1: STANDARD, cycle balance 1234.56, late 10, cycle days 30; *when* CBFEECAL runs; *then* FEE_AMT 30.86 (30.864 truncated, not 30.87), FEE_AVG_DAILY 41.15, FEE_RC 0.

### BR-ONL-070 - Late fee floor

A computed fee below 5.00 is raised to 5.00.

- **Source:** `app/pli/CBFEECAL.pli:37`, `app/pli/CBFEECAL.pli:82`
- **Fields:** FEE_FLOOR, FEE_AMT (CBFEECAL)
- **Category:** calculation
- **Test:** *Given* Worked example 2: STANDARD, cycle balance 100.00, late 10, cycle days 30; *when* CBFEECAL runs; *then* Computed 2.50 is lifted: FEE_AMT 5.00, FEE_AVG_DAILY 3.33, FEE_RC 0.

### BR-ONL-071 - Late fee cap

A computed fee above 35.00 is capped at 35.00.

- **Source:** `app/pli/CBFEECAL.pli:38`, `app/pli/CBFEECAL.pli:83`
- **Fields:** FEE_CAP, FEE_AMT (CBFEECAL)
- **Category:** calculation
- **Test:** *Given* Worked example 3: STANDARD, cycle balance 5000.00, late 10, cycle days 30; *when* CBFEECAL runs; *then* Computed 125.00 is capped: FEE_AMT 35.00, FEE_AVG_DAILY 166.66, FEE_RC 0.

### BR-ONL-072 - Zero-day cycle raises ZERODIVIDE

A cycle of 0 days raises ZERODIVIDE in the average-daily division; the handler prints 'CBFEECAL: ZERODIVIDE FOR ACCOUNT <id>' and returns fee 0.00, average 0.00, RC 8.

- **Source:** `app/pli/CBFEECAL.pli:42-48`, `app/pli/CBFEECAL.pli:69`
- **Fields:** FEE_CYCLE_DAYS, FEE_AMT, FEE_AVG_DAILY, FEE_RC (CBFEECAL)
- **Category:** return-code
- **Test:** *Given* Worked example 5: STANDARD, cycle balance 1000.00, late 10, cycle days 0; *when* CBFEECAL runs; *then* FEE_AMT 0.00, FEE_AVG_DAILY 0.00, FEE_RC 8; SYSPRINT line 'CBFEECAL: ZERODIVIDE FOR ACCOUNT 00000000099'.

### BR-ONL-073 - SIZE and ERROR conditions

A SIZE overflow inside the calculation block sets fee 0 and RC 8 with 'CBFEECAL: SIZE CONDITION FOR ACCOUNT <id>'; any other error sets RC 8 with 'CBFEECAL: UNEXPECTED ERROR, ACCOUNT <id>' (fee left as it was).

- **Source:** `app/pli/CBFEECAL.pli:50-61`, `app/pli/CBFEECAL.pli:63-64`
- **Fields:** FEE_AMT, FEE_RC (CBFEECAL); SYSPRINT (FEECALC.jcl)
- **Category:** sysout
- **Test:** *Given* A cycle balance whose fee would not fit DECIMAL FIXED(9,2) (cycle balance 9999999999.99: 2.5% = 249999999.99); *when* CBFEECAL runs; *then* SYSPRINT 'CBFEECAL: SIZE CONDITION FOR ACCOUNT <id>', FEE_AMT 0.00, FEE_RC 8.

### BR-ONL-074 - Late-fee output line

For every account CBFEEDRV writes an 80-byte FEEOUT line: account id, group id, cycle balance (-(10)9.99), fee (-(7)9.99) and 2-digit return code separated by single spaces, to a new generation of the LATEFEES GDG.

- **Source:** `app/cbl/CBFEEDRV.cbl:63-73`, `app/cbl/CBFEEDRV.cbl:102-107`, `app/jcl/FEECALC.jcl:28-31`
- **Fields:** FEE-LINE: FL-ACCT-ID, FL-GROUP-ID, FL-CYCLE-BAL, FL-FEE-AMT, FL-RC (CBFEEDRV)
- **Category:** sysout
- **Test:** *Given* Worked example 1 result for account 00000000099; *when* The line is written; *then* '00000000099 STANDARD          1234.56       30.86 00' followed by 28 spaces (80 bytes).

## Findings (legacy behaviour to reproduce, not fix)

These look like defects or risks in the legacy code. The migration must reproduce them as written; they are listed so the business can decide later.

| # | Program | Where | Finding |
|---|---|---|---|
| F-01 | COACTVWC / COACTUPC | `app/cbl/COACTVWC.cbl:704-715, app/cbl/COACTUPC.cbl:3627-3638` | The guards after the account and customer reads test DID-NOT-FIND-ACCT-IN-ACCTDAT / DID-NOT-FIND-CUST-IN-CUSTDAT, but the not-found branches build a STRING message instead of setting those conditions, so the guards never fire. An account missing from ACCTDAT still triggers the customer read, and in COACTUPC 9500-STORE-FETCHED-DATA snapshots whatever is in the record areas even when the customer was not found. |
| F-02 | COACTVWC / COACTUPC | `app/cbl/COACTVWC.cbl:344, app/cbl/COACTUPC.cbl:947` | PF3 sets CDEMO-USER-TYPE to 'U' unconditionally, so an administrator who returns through these screens is downgraded to a regular user in the commarea. |
| F-03 | COACTVWC | `app/cbl/COACTVWC.cbl:640-642, app/cbl/COACTVWC.cbl:672` | A blank account id shows 'No input received' (the 'Account number not provided' text is overwritten), and the numeric-key message has a double space ('must  be'). The info message is always the prompt; WS-INFORM-OUTPUT is never set. |
| F-04 | COACTUPC | `app/cbl/COACTUPC.cbl:2234-2239` | The 'phone is blank' test checks the area code twice (WS-EDIT-US-PHONE-NUMA = SPACES instead of NUMC). It is masked in practice because blank screen fields are normalised to low-values, but it should be reproduced literally. |
| F-05 | COACTUPC | `app/cbl/COACTUPC.cbl:1472-1476, app/cbl/COACTUPC.cbl:1685-1688` | Change detection compares Account Status case-insensitively, but the Y/N edit is case-sensitive: changing 'Y' to 'y' reports 'No change detected...', while 'N' to 'y' is rejected. |
| F-06 | COACTUPC | `app/cbl/COACTUPC.cbl:2442-2453` | An SSN first part of '000' fails the generic 'must not be zero' edit first, so the specific '000, 666, or between 900 and 999' message is only seen for 666 and 900-999. |
| F-07 | COACTUPC | `app/cbl/COACTUPC.cbl:1515-1527, app/cbl/COACTUPC.cbl:1605-1611` | Labels 'Current Cycle Credit Limit' / 'Current Cycle Debit Limit' describe cycle amounts, not limits; only 5 of the 10 zip characters are edited; Address Line 2, Government ID and Group ID are not edited; there is no cross-check between credit limit and cash credit limit; a date of birth equal to today is rejected as 'in the future'. |
| F-08 | COACTUPC | `app/cbl/COACTUPC.cbl:371, app/cbl/COACTUPC.cbl:2797-2811` | The display picture +ZZZ,ZZZ,ZZZ.99 has 9 integer digits while the amounts are S9(10)V99, so a value of 1,000,000,000.00 or more loses its leading digit on screen. |
| F-09 | COACTUPC | `app/cbl/COACTUPC.cbl:4143-4144, app/cbl/COACTUPC.cbl:4189-4190` | 9700-CHECK-CHANGE-IN-REC leaves its PERFORM range with GO TO 9600-WRITE-PROCESSING-EXIT. It works on IBM COBOL but is non-structured control flow that a converter must reproduce as an early return from the save. |
| F-10 | COMEN01C | `app/cbl/COMEN01C.cbl:127-143` | After an out-of-range option (0 or 12-99) is rejected, the admin-only check still subscripts CDEMO-MENU-OPT-USRTYPE with that option, reading outside the 11 defined entries. The 'DUMMY' message drops the space before 'is coming soon'. |
| F-11 | COSGN00C | `app/cbl/COSGN00C.cbl:241-246` | The wrong-password branch does not set WS-ERR-FLG (the other errors do). There is no visible effect because nothing runs after it. |
| F-12 | COBDATFT (HLASM) | `app/asm/COBDATFT.asm:36` | CLC COINPDT+4,=C'-' takes its length from COINPDT (20 bytes), not 1, so it compares 20 bytes against a 1-byte literal and the following literal-pool bytes. The hyphen guard for type-1 input is effectively never true, so '2025-03-15' sent as type 1 is reformatted to '2025--0-3-' instead of being rejected. The type-2 separator check is commented out (lines 47-48). docs/LEGACY-LANGUAGES.md gives COINPDT/COOUTDT as X(10); the DSECT and copybook declare 20 bytes. |
| F-13 | MVSWAIT (HLASM) | `app/asm/MVSWAIT.asm:20-28; app/cbl/COBSWAIT.cbl:36-38` | MVSWAIT stores the delay in BINLBL inside its own CSECT, so it is not reentrant. COBSWAIT moves an unchecked X(8) SYSIN value into a 9(8) COMP field; a non-numeric card produces an undefined wait. docs/LEGACY-LANGUAGES.md names CBSTM03A/B as the callers; in this estate COBSWAIT runs from app/jcl/WAITSTEP.jcl. |
| F-14 | CBFEECAL / CBFEEDRV (PL/I) | `app/pli/CBFEECAL.pli:21-23; app/cbl/CBFEEDRV.cbl:101` | CBFEECAL is declared OPTIONS(MAIN) with a POINTER parameter, while CBFEEDRV passes FEE-REQ BY REFERENCE. Under default PL/I linkage the parameter would receive the address of FEE-REQ as the address of a pointer, so REQ_PTR would be built from the first bytes of FEE-ACCT-ID. Making it work needs BYVALUE/NOEXECOPS or a non-MAIN entry. A converter should implement the intended contract (operate on FEE-REQ) and record this as a linkage risk. |
| F-15 | CBFEECAL / CBFEEDRV (PL/I) | `app/pli/CBFEECAL.pli:69-72; app/cbl/CBFEEDRV.cbl:89-99` | The average-daily division runs before the late-days test, so an account that is not late but has 0 cycle days still gets RC 8. CBFEEDRV always passes 30 days, so worked example 5 cannot occur through the driver. The driver treats cycle credit minus cycle debit as the amount due, a sign convention the business should confirm. The GOLD test is case-sensitive. |
