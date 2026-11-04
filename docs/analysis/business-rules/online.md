# Business rules: online slice (BR-ONL)

**Scope.** CICS online programs COSGN00C (sign-on, transaction CC00), COMEN01C (regular-user menu, CM00), COACTVWC (account view, CAVW) and COACTUPC (account update, CAUP), their BMS maps (`app/bms/COSGN00.bms`, `COMEN01.bms`, `COACTVW.bms`, `COACTUP.bms`) and symbolic maps (`app/cpy-bms/*`), and the copybooks COCOM01Y, COMEN02Y, CSUSR01Y, CVACT01Y, CVACT02Y, CVACT03Y, CVCUS01Y, CSMSG01Y, CSDAT01Y, CVCRD01Y, CSSTRPFY, CSUTLDPY, CSUTLDWY and CSLKPCDY. Non-COBOL routines: HLASM `app/asm/COBDATFT.asm` (caller CBACT01C, DSECT `app/maclib/COCDATFT.mac`) and `app/asm/MVSWAIT.asm` (caller COBSWAIT, job WAITSTEP); PL/I `app/pli/CBFEECAL.pli` with its COBOL driver `app/cbl/CBFEEDRV.cbl` and job `app/jcl/FEECALC.jcl`.

**How the rules were extracted.** Each paragraph, EVALUATE branch, 88-level message and edit routine was read on the integration branch, and every rule cites exact line ranges (checked with `nl -ba`). Message texts are copied character for character, including double spaces and missing full stops. The six CBFEECAL worked examples in `docs/LEGACY-LANGUAGES.md` are used as the PL/I test cases. Where the source and the documentation disagree, the source wins and the difference is recorded as a finding.

**Run status: analysis-only.** CICS, HLASM and PL/I cannot be executed off-host: the Docker estate has no CICS region or BMS assembler (the online programs do not compile there, see `docs/BASELINE.md`), no HLASM assembler and no PL/I compiler. These rules are therefore verified by reading the source and by unit tests on the converted code only. None of them was executed, and no golden fixture covers this slice, so every rule is `Proven by: source-only`.

**Rule count by category.** calculation 2, control-flow 8, data-mapping 7, edge-case 2, return-code 8, rounding 2, state-change 6, sysout 2, validation 18 (total 55).

**Screen-to-REST mapping notes (target design guidance, not legacy behaviour).**

| Legacy screen / key | Suggested REST shape | Notes |
|---|---|---|
| COSGN00 ENTER | `POST /online/signon` `{userId, password}` | Upper-case both values before lookup (BR-ONL-004); return `userType` and the next program (COADM01C / COMEN01C). Error texts exactly as BR-ONL-003/005/006. PF3 = logout message (BR-ONL-002). |
| COMEN01 display / ENTER | `GET /online/menu`, `POST /online/menu/selection` `{option}` | Option parsing and errors as BR-ONL-010..013; the response names the target program instead of XCTL. |
| COACTVW ENTER | `GET /online/accounts/{acctId}` | 11-digit, non-zero id; NOTFND texts (including truncation) as BR-ONL-017..021. PF3 returns to the caller (BR-ONL-016). |
| COACTUP ENTER (search) | `GET /online/accounts/{acctId}/edit` | Returns the snapshot the optimistic check needs (BR-ONL-040). |
| COACTUP ENTER (changes) | `POST /online/accounts/{acctId}/validate` | Edit ladder; only the first message is returned, every failing field is flagged (BR-ONL-028). |
| COACTUP PF5 | `PUT /online/accounts/{acctId}` with the snapshot | Lock, concurrency check, two rewrites, rollback (BR-ONL-039..041). |
| COACTUP PF12 | `GET` again | Discards edits (BR-ONL-042). |
| COMMAREA | session / request state | CARDDEMO-COMMAREA fields map to session state; ACUP-CHANGE-ACTION becomes an explicit workflow state (BR-ONL-023, 043). |

## Rules

### BR-ONL-001 Sign-on first display and screen header

**Rule**: When COSGN00C is started with no COMMAREA (EIBCALEN = 0) it clears the output map, puts the cursor on the User ID field and sends the sign-on screen. Every screen send fills the header: titles, transaction CC00, program COSGN00C, date as MM/DD/YY and time as HH:MM:SS from FUNCTION CURRENT-DATE, plus APPLID/SYSID. The task always ends with RETURN TRANSID('CC00') passing CARDDEMO-COMMAREA.

**Source**: `app/cbl/COSGN00C.cbl:80-83`; `app/cbl/COSGN00C.cbl:98-102`; `app/cbl/COSGN00C.cbl:145-157`; `app/cbl/COSGN00C.cbl:177-204`; `app/cpy/CSDAT01Y.cpy:31-41`

**Fields**: `EIBCALEN`, `COSGN0AO`, `USERIDL`, `CURDATEO`, `CURTIMEO`, `TRNNAMEO`, `PGMNAMEO`, `WS-CURDATE-MM-DD-YY`, `WS-CURTIME-HH-MM-SS`

**Category**: control-flow

**Test**: Given Clock frozen at 2022-07-05 10:00:00 and no COMMAREA (EIBCALEN = 0). When Transaction CC00 starts. Then The sign-on screen is sent with ERRMSG blank, CURDATE '07/05/22', CURTIME '10:00:00', TRNNAME 'CC00', PGMNAME 'COSGN00C', cursor on USERID; the task returns with TRANSID CC00.

**Proven by**: source-only

### BR-ONL-002 Sign-on PF3 and invalid keys

**Rule**: On the sign-on screen PF3 sends the plain text 'Thank you for using CardDemo application...' (CCDA-MSG-THANK-YOU) with ERASE/FREEKB and ends the task without a next TRANSID. ENTER processes the sign-on. Any other AID re-sends the sign-on screen with 'Invalid key pressed. Please see below...' (CCDA-MSG-INVALID-KEY).

**Source**: `app/cbl/COSGN00C.cbl:85-95`; `app/cbl/COSGN00C.cbl:162-172`; `app/cpy/CSMSG01Y.cpy:17-21`

**Fields**: `EIBAID`, `CCDA-MSG-THANK-YOU`, `CCDA-MSG-INVALID-KEY`, `WS-MESSAGE`

**Category**: control-flow

**Test**: Given The sign-on screen is displayed (EIBCALEN > 0). When The user presses PF3; in a second case the user presses PF7. Then PF3: the screen is erased and shows 'Thank you for using CardDemo application...' and the session ends (plain RETURN). PF7: the sign-on screen is shown again with ERRMSG 'Invalid key pressed. Please see below...'.

**Proven by**: source-only

### BR-ONL-003 Sign-on blank User ID / Password validation

**Rule**: On ENTER the map is received and checked in order: User ID equal to SPACES or LOW-VALUES gives 'Please enter User ID ...' with the cursor on USERID; otherwise a blank Password gives 'Please enter Password ...' with the cursor on PASSWD. Either error sets WS-ERR-FLG = 'Y' and USRSEC is not read. Both input fields are 8 characters (BMS LENGTH=8, password is DRK/non-display).

**Source**: `app/cbl/COSGN00C.cbl:108-140`; `app/bms/COSGN00.bms:156-159`; `app/bms/COSGN00.bms:175-178`; `app/cpy-bms/COSGN00.CPY:72-78`

**Fields**: `USERIDI`, `PASSWDI`, `WS-ERR-FLG`, `ERRMSGO`

**Category**: validation

**Test**: Given Sign-on screen with USERID = spaces and PASSWD = 'PASS0001'; second case USERID = 'USER0001' and PASSWD = spaces. When The user presses ENTER. Then Case 1: ERRMSG 'Please enter User ID ...', cursor on USERID, no file read. Case 2: ERRMSG 'Please enter Password ...', cursor on PASSWD, no file read.

**Proven by**: source-only

### BR-ONL-004 Sign-on upper-cases User ID and Password

**Rule**: Before the lookup, both the entered User ID and the Password are converted with FUNCTION UPPER-CASE (User ID also into CDEMO-USER-ID). The USRSEC key and the password comparison therefore are case-insensitive for the input.

**Source**: `app/cbl/COSGN00C.cbl:132-136`; `app/cbl/COSGN00C.cbl:223-223`

**Fields**: `USERIDI`, `PASSWDI`, `WS-USER-ID`, `WS-USER-PWD`, `CDEMO-USER-ID`, `SEC-USR-PWD`

**Category**: data-mapping

**Test**: Given USRSEC holds SEC-USR-ID 'USER0001' with SEC-USR-PWD 'PASSWORD'. When The user signs on with User ID 'user0001' and Password 'password'. Then Lookup key is 'USER0001', the compared password is 'PASSWORD', sign-on succeeds.

**Proven by**: source-only

**Finding**: Passwords are compared after upper-casing the input only: a password stored in lower case in USRSEC can never match, and passwords are effectively case-insensitive. Looks wrong (weak credential check) but must be reproduced.

### BR-ONL-005 Sign-on USRSEC lookup return codes

**Rule**: USRSEC is read by the 8-byte upper-cased User ID (EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)). RESP 0 proceeds to the password check; RESP 13 (NOTFND) gives 'User not found. Try again ...'; any other RESP gives 'Unable to verify the User ...'. Both errors put the cursor on USERID and re-send the screen.

**Source**: `app/cbl/COSGN00C.cbl:209-221`; `app/cbl/COSGN00C.cbl:247-257`; `app/cpy/CSUSR01Y.cpy:17-23`

**Fields**: `WS-USRSEC-FILE`, `WS-USER-ID`, `WS-RESP-CD`, `SEC-USER-DATA`

**Category**: return-code

**Test**: Given USRSEC does not contain 'NOUSER01'. When The user signs on with User ID 'NOUSER01' and any password. Then READ returns RESP 13 and the screen shows 'User not found. Try again ...' with cursor on USERID; an I/O error (e.g. RESP 12 file not open) shows 'Unable to verify the User ...'.

**Proven by**: source-only

### BR-ONL-006 Sign-on wrong password

**Rule**: When the USRSEC record exists but SEC-USR-PWD differs from the upper-cased input password, the screen is re-sent with 'Wrong Password. Try again ...' and the cursor on PASSWD. No COMMAREA routing fields are set.

**Source**: `app/cbl/COSGN00C.cbl:223-223`; `app/cbl/COSGN00C.cbl:241-246`

**Fields**: `SEC-USR-PWD`, `WS-USER-PWD`, `PASSWDL`, `WS-MESSAGE`

**Category**: validation

**Test**: Given USRSEC record 'USER0001' with password 'PASSWORD'. When The user signs on with 'USER0001' / 'WRONGPW1'. Then ERRMSG 'Wrong Password. Try again ...', cursor on PASSWD, no XCTL.

**Proven by**: source-only

**Finding**: This branch does not set WS-ERR-FLG (the blank-field and not-found branches do). It has no visible effect because nothing runs after it, but a port that relies on the flag must keep the same outcome.

### BR-ONL-007 Sign-on success: COMMAREA and admin vs regular routing

**Rule**: On a password match COSGN00C fills CARDDEMO-COMMAREA: CDEMO-FROM-TRANID = 'CC00', CDEMO-FROM-PROGRAM = 'COSGN00C', CDEMO-USER-ID = upper-cased id, CDEMO-USER-TYPE = SEC-USR-TYPE, CDEMO-PGM-CONTEXT = 0 (enter). If the user type is 'A' (CDEMO-USRTYP-ADMIN) it XCTLs to COADM01C, otherwise (any other value, including 'U' or blank) to COMEN01C, passing the COMMAREA.

**Source**: `app/cbl/COSGN00C.cbl:222-240`; `app/cpy/COCOM01Y.cpy:19-44`

**Fields**: `CDEMO-FROM-TRANID`, `CDEMO-FROM-PROGRAM`, `CDEMO-USER-ID`, `CDEMO-USER-TYPE`, `CDEMO-PGM-CONTEXT`, `SEC-USR-TYPE`

**Category**: state-change

**Test**: Given USRSEC records ADMIN001/PASSWORD type 'A' and USER0001/PASSWORD type 'U'. When Each user signs on with the correct password. Then ADMIN001: XCTL COADM01C with CDEMO-USER-TYPE 'A', CDEMO-PGM-CONTEXT 0. USER0001: XCTL COMEN01C with CDEMO-USER-TYPE 'U', CDEMO-FROM-PROGRAM 'COSGN00C', CDEMO-FROM-TRANID 'CC00'.

**Proven by**: source-only

**Finding**: Any SEC-USR-TYPE other than 'A' (including blank or an unexpected code) is routed to the regular-user menu instead of being rejected. Must be reproduced.

### BR-ONL-008 Menu entry, PF3 and invalid keys

**Rule**: COMEN01C with no COMMAREA XCTLs to the sign-on program (CDEMO-TO-PROGRAM defaults to 'COSGN00C' when LOW-VALUES/SPACES). With a COMMAREA it is copied into CARDDEMO-COMMAREA; on first entry (CDEMO-PGM-CONTEXT 0) the context is set to 1 (re-enter) and the menu is sent. On re-entry ENTER processes the option, PF3 XCTLs to COSGN00C, any other key re-sends the menu with 'Invalid key pressed. Please see below...'. The task ends with RETURN TRANSID('CM00') COMMAREA(CARDDEMO-COMMAREA).

**Source**: `app/cbl/COMEN01C.cbl:82-110`; `app/cbl/COMEN01C.cbl:196-203`

**Fields**: `EIBCALEN`, `EIBAID`, `CDEMO-PGM-CONTEXT`, `CDEMO-TO-PROGRAM`, `CDEMO-FROM-PROGRAM`

**Category**: control-flow

**Test**: Given A regular user arrives from COSGN00C with CDEMO-PGM-CONTEXT 0. When The menu is shown, then the user presses PF3. Then First pass: menu sent and context becomes 1 (CDEMO-PGM-REENTER). PF3: XCTL to COSGN00C. With EIBCALEN = 0 the program XCTLs straight to COSGN00C.

**Proven by**: source-only

### BR-ONL-009 Menu options table and display

**Rule**: The regular-user menu is driven by CARDDEMO-MAIN-MENU-OPTIONS: CDEMO-MENU-OPT-COUNT = 11 and entries (number, 35-char name, program, user type): 1 Account View COACTVWC, 2 Account Update COACTUPC, 3 Credit Card List COCRDLIC, 4 Credit Card View COCRDSLC, 5 Credit Card Update COCRDUPC, 6 Transaction List COTRN00C, 7 Transaction View COTRN01C, 8 Transaction Add COTRN02C, 9 Transaction Reports CORPT00C, 10 Bill Payment COBIL00C, 11 Pending Authorization View COPAUS0C; all with user type 'U'. Each screen line is STRING number '. ' name into a 40-char field, OPTN001..OPTN012.

**Source**: `app/cpy/COMEN02Y.cpy:19-98`; `app/cbl/COMEN01C.cbl:208-220`; `app/cbl/COMEN01C.cbl:262-303`

**Fields**: `CDEMO-MENU-OPT-COUNT`, `CDEMO-MENU-OPT-NUM`, `CDEMO-MENU-OPT-NAME`, `CDEMO-MENU-OPT-PGMNAME`, `CDEMO-MENU-OPT-USRTYPE`, `OPTN001O`

**Category**: data-mapping

**Test**: Given The menu is sent. When BUILD-MENU-OPTIONS runs. Then OPTN001O = '01. Account View' (padded to 40), OPTN011O = '11. Pending Authorization View', OPTN012O stays LOW-VALUES.

**Proven by**: source-only

**Finding**: Item 8 is labelled 'Transaction Add' with user type 'U'; the original '(Admin Only)' label is commented out, so regular users can reach COTRN02C. Must be reproduced.

### BR-ONL-010 Menu option parsing

**Rule**: The 2-character OPTION input is scanned right-to-left for the last non-space character, that prefix is moved into a right-justified PIC X(2) (WS-OPTION-X JUST RIGHT), spaces are replaced by '0', and the result moved to PIC 9(2) WS-OPTION, which is echoed back to the OPTION field.

**Source**: `app/cbl/COMEN01C.cbl:45-46`; `app/cbl/COMEN01C.cbl:117-125`; `app/bms/COMEN01.bms:145-148`; `app/cpy-bms/COMEN01.CPY:132-132`

**Fields**: `OPTIONI`, `WS-OPTION-X`, `WS-OPTION`, `OPTIONO`

**Category**: data-mapping

**Test**: Given Menu displayed. When The user types '1 ' (1 then space), ' 1', or '10' and presses ENTER. Then '1 ' and ' 1' both become WS-OPTION 01; '10' stays 10.

**Proven by**: source-only

### BR-ONL-011 Menu invalid option number

**Rule**: If WS-OPTION is not numeric, is zero, or is greater than CDEMO-MENU-OPT-COUNT (11), the menu is re-sent with 'Please enter a valid option number...' and no XCTL happens.

**Source**: `app/cbl/COMEN01C.cbl:127-134`; `app/cpy/COMEN02Y.cpy:21-21`

**Fields**: `WS-OPTION`, `CDEMO-MENU-OPT-COUNT`, `WS-ERR-FLG`

**Category**: validation

**Test**: Given Menu displayed for a regular user. When The user enters '00', '12' or blank and presses ENTER. Then ERRMSG 'Please enter a valid option number...', no program transfer.

**Proven by**: source-only

**Finding**: After this error the code does not stop: it goes on to the admin check, which reads CDEMO-MENU-OPT-USRTYPE(WS-OPTION) with an out-of-range subscript (0 or 12-99), outside the 11 loaded entries. No transfer happens because the error flag is set, but a port must not raise an index error and must still show only this message. Looks wrong; reproduce the visible outcome.

### BR-ONL-012 Menu admin-only option authorization

**Rule**: If the signed-on user is a regular user (CDEMO-USRTYP-USER, 'U') and the selected entry has CDEMO-MENU-OPT-USRTYPE = 'A', the menu is re-sent with 'No access - Admin Only option... ' and no XCTL. In the shipped table every entry is 'U', so this check never fires today.

**Source**: `app/cbl/COMEN01C.cbl:136-143`; `app/cpy/COMEN02Y.cpy:25-90`

**Fields**: `CDEMO-USER-TYPE`, `CDEMO-MENU-OPT-USRTYPE`, `WS-MESSAGE`

**Category**: validation

**Test**: Given A table entry 3 with user type 'A' (configuration variant) and a regular user 'U'. When The user selects option 3. Then ERRMSG 'No access - Admin Only option... ', no XCTL; with the shipped table (all 'U') the same selection XCTLs to COCRDLIC.

**Proven by**: source-only

### BR-ONL-013 Menu not-installed and coming-soon options

**Rule**: For program COPAUS0C the menu first issues EXEC CICS INQUIRE PROGRAM NOHANDLE; if EIBRESP is not NORMAL it shows in red 'This option ' + the option name up to the first double space + ' is not installed...'. For any program whose name starts with 'DUMMY' it shows in green 'This option ' + the name up to the first single space + 'is coming soon ...'. In both cases the menu is re-sent and no XCTL occurs.

**Source**: `app/cbl/COMEN01C.cbl:145-176`; `app/cbl/COMEN01C.cbl:190-191`

**Fields**: `CDEMO-MENU-OPT-PGMNAME`, `CDEMO-MENU-OPT-NAME`, `EIBRESP`, `ERRMSGC`, `WS-MESSAGE`

**Category**: control-flow

**Test**: Given COPAUS0C is not defined in the CICS region; a hypothetical entry 'Card Rewards' points to 'DUMMY001'. When The user selects option 11, then the DUMMY option. Then Option 11: red ERRMSG 'This option Pending Authorization View is not installed...'. DUMMY: green ERRMSG 'This option Cardis coming soon ...'.

**Proven by**: source-only

**Finding**: The coming-soon text uses DELIMITED BY SPACE, so only the first word of the option name is kept and there is no space before 'is'. Looks wrong but must be reproduced.

### BR-ONL-014 Menu transfer to the selected program

**Rule**: For any other valid option the menu sets CDEMO-FROM-TRANID = 'CM00', CDEMO-FROM-PROGRAM = 'COMEN01C', CDEMO-PGM-CONTEXT = 0 and XCTLs to CDEMO-MENU-OPT-PGMNAME(option) passing CARDDEMO-COMMAREA. User id and user type are passed through unchanged.

**Source**: `app/cbl/COMEN01C.cbl:145-146`; `app/cbl/COMEN01C.cbl:177-191`

**Fields**: `CDEMO-FROM-TRANID`, `CDEMO-FROM-PROGRAM`, `CDEMO-PGM-CONTEXT`, `CDEMO-MENU-OPT-PGMNAME`

**Category**: state-change

**Test**: Given Regular user USER0001 on the menu. When The user enters option 1 and presses ENTER. Then XCTL PROGRAM('COACTVWC') with CDEMO-FROM-PROGRAM 'COMEN01C', CDEMO-FROM-TRANID 'CM00', CDEMO-PGM-CONTEXT 0, CDEMO-USER-ID 'USER0001'.

**Proven by**: source-only

### BR-ONL-015 PF-key normalisation and valid keys on Account View

**Rule**: YYYY-STORE-PFKEY maps EIBAID to CCARD-AID-*: ENTER, CLEAR, PA1, PA2, PF1-PF12, and PF13-PF24 fold onto PF1-PF12. In COACTVWC only ENTER and PF3 are valid; any other key is silently treated as ENTER.

**Source**: `app/cpy/CSSTRPFY.cpy:17-80`; `app/cbl/COACTVWC.cbl:299-314`

**Fields**: `EIBAID`, `CCARD-AID`, `PFK-VALID`, `PFK-INVALID`

**Category**: control-flow

**Test**: Given Account View screen displayed with account 00000000001 entered. When The user presses PF15; in another case PF8. Then PF15 acts as PF3 (exit). PF8 is invalid and is processed exactly like ENTER (the account is looked up and shown again).

**Proven by**: source-only

### BR-ONL-016 PF3 exit from Account View / Account Update

**Rule**: PF3 transfers control back: CDEMO-TO-TRANID = CDEMO-FROM-TRANID, or 'CM00' if blank/LOW-VALUES; CDEMO-TO-PROGRAM = CDEMO-FROM-PROGRAM, or 'COMEN01C' if blank. CDEMO-FROM-* are set to this program (COACTVWC/CAVW or COACTUPC/CAUP), CDEMO-USER-TYPE is forced to 'U', CDEMO-PGM-CONTEXT to 0 and CDEMO-LAST-MAP/MAPSET to this screen, then XCTL with the COMMAREA. COACTUPC issues SYNCPOINT before the XCTL.

**Source**: `app/cbl/COACTVWC.cbl:324-352`; `app/cbl/COACTUPC.cbl:927-959`

**Fields**: `CDEMO-FROM-TRANID`, `CDEMO-FROM-PROGRAM`, `CDEMO-TO-TRANID`, `CDEMO-TO-PROGRAM`, `CDEMO-USER-TYPE`, `CDEMO-PGM-CONTEXT`, `CDEMO-LAST-MAP`, `CDEMO-LAST-MAPSET`

**Category**: state-change

**Test**: Given COMMAREA with CDEMO-FROM-PROGRAM 'COMEN01C', CDEMO-FROM-TRANID 'CM00', CDEMO-USER-TYPE 'A'. When The user presses PF3 on Account View. Then XCTL to COMEN01C with CDEMO-TO-TRANID 'CM00', CDEMO-FROM-PROGRAM 'COACTVWC', CDEMO-FROM-TRANID 'CAVW', CDEMO-USER-TYPE 'U', CDEMO-PGM-CONTEXT 0, CDEMO-LAST-MAPSET 'COACTVW', CDEMO-LAST-MAP 'CACTVWA'.

**Proven by**: source-only

**Finding**: PF3 always sets CDEMO-USRTYP-USER, so an administrator who returns through these screens loses the 'A' user type in the COMMAREA. Looks wrong; must be reproduced.

### BR-ONL-017 Account View blank account id

**Rule**: On re-entry the account id input '*' or SPACES is treated as not supplied (CC-ACCT-ID = LOW-VALUES). The edit sets INPUT-ERROR and FLG-ACCTFILTER-BLANK and CDEMO-ACCT-ID = 0. The message 'Account number not provided' is then overwritten by 'No input received', which is what the user sees. The screen is re-sent with '*' in red in the account field and the cursor there.

**Source**: `app/cbl/COACTVWC.cbl:121-124`; `app/cbl/COACTVWC.cbl:361-367`; `app/cbl/COACTVWC.cbl:557-565`; `app/cbl/COACTVWC.cbl:622-662`

**Fields**: `ACCTSIDI`, `CC-ACCT-ID`, `CDEMO-ACCT-ID`, `WS-RETURN-MSG`, `FLG-ACCTFILTER-BLANK`

**Category**: validation

**Test**: Given Account View re-entered (CDEMO-PGM-REENTER). When The user leaves the account field blank and presses ENTER. Then ERRMSG 'No input received', account field shows '*' in red, no file is read.

**Proven by**: source-only

### BR-ONL-018 Account View non-numeric or zero account id

**Rule**: A supplied account id that is not numeric or equals zero sets INPUT-ERROR and FLG-ACCTFILTER-NOT-OK, CDEMO-ACCT-ID = 0, and the message 'Account Filter must  be a non-zero 11 digit number' (two spaces after 'must'); the field is shown in red. A valid id is moved to CDEMO-ACCT-ID.

**Source**: `app/cbl/COACTVWC.cbl:557-559`; `app/cbl/COACTVWC.cbl:664-680`; `app/bms/COACTVW.bms:84-87`; `app/cpy-bms/COACTVW.CPY:60-60`

**Fields**: `ACCTSIDI`, `CC-ACCT-ID`, `CDEMO-ACCT-ID`, `ACCTSIDC`

**Category**: validation

**Test**: Given Account View re-entered. When The user enters '00000000000' (or 'ABC'). Then ERRMSG 'Account Filter must  be a non-zero 11 digit number', account field red, no file read.

**Proven by**: source-only

**Finding**: The literal has a double space ('must  be') and differs from the unused 88-level text 'Account number must be a non zero 11 digit number'. Must be reproduced character for character.

### BR-ONL-019 Account lookup chain: card xref -> account -> customer

**Rule**: 9000-READ-ACCT reads, in order: the card cross-reference by account through the alternate index path CXACAIX (key = 11-char account id) giving XREF-CUST-ID -> CDEMO-CUST-ID and XREF-CARD-NUM -> CDEMO-CARD-NUM; the account master ACCTDAT by the same account id; and the customer master CUSTDAT by the 9-digit customer id taken from the xref. Only an xref failure stops the chain. COACTUPC uses the same chain and then stores the fetched values (9500-STORE-FETCHED-DATA).

**Source**: `app/cbl/COACTVWC.cbl:687-740`; `app/cbl/COACTVWC.cbl:774-788`; `app/cbl/COACTVWC.cbl:825-838`; `app/cbl/COACTUPC.cbl:3608-3645`; `app/cbl/COACTUPC.cbl:3801-3885`; `app/cpy/CVACT03Y.cpy:4-8`

**Fields**: `WS-CARD-RID-ACCT-ID-X`, `WS-CARD-RID-CUST-ID-X`, `XREF-CUST-ID`, `XREF-CARD-NUM`, `CDEMO-CUST-ID`, `CDEMO-CARD-NUM`, `ACCOUNT-RECORD`, `CUSTOMER-RECORD`

**Category**: control-flow

**Test**: Given CXACAIX maps account 00000000001 to customer 000000001 and card 4111111111111111; ACCTDAT and CUSTDAT hold both records. When The user views account 00000000001. Then Three READs in order CXACAIX, ACCTDAT, CUSTDAT; CDEMO-CUST-ID = 000000001, CDEMO-CARD-NUM = 4111111111111111; screen shows account and customer details.

**Proven by**: source-only

**Finding**: The 'not found' checks after the account and customer reads test 88-levels (DID-NOT-FIND-ACCT-IN-ACCTDAT / -CUST-IN-CUSTDAT) whose SET statements are commented out, so they are never true. A missing account still triggers the customer read (and in COACTUPC the store of fetched data). Must be reproduced.

### BR-ONL-020 Account lookup NOTFND messages (truncated to 75 chars)

**Rule**: RESP NOTFND on a read sets INPUT-ERROR and, only if no message is set yet, STRINGs (DELIMITED BY SIZE) into the 75-byte WS-RETURN-MSG: xref 'Account:' + id + ' not found in' + ' Cross ref file.  Resp:' + ERROR-RESP + ' Reas:' + ERROR-RESP2; account 'Account:' + id + ' not found in' + ' Acct Master file.Resp:' + ...; customer 'CustId:' + cust id + ' not found' + ' in customer master.Resp: ' + ERROR-RESP + ' REAS:' + ERROR-RESP2. ERROR-RESP/-RESP2 are X(10) copies of the binary RESP codes (9 digits plus a space). The text is cut at 75 characters.

**Source**: `app/cbl/COACTVWC.cbl:86-105`; `app/cbl/COACTVWC.cbl:117-117`; `app/cbl/COACTVWC.cbl:741-758`; `app/cbl/COACTVWC.cbl:789-807`; `app/cbl/COACTVWC.cbl:839-857`; `app/cbl/COACTUPC.cbl:3668-3685`; `app/cbl/COACTUPC.cbl:3716-3734`; `app/cbl/COACTUPC.cbl:3766-3784`

**Fields**: `WS-RETURN-MSG`, `ERROR-RESP`, `ERROR-RESP2`, `WS-CARD-RID-ACCT-ID-X`, `WS-CARD-RID-CUST-ID-X`

**Category**: sysout

**Test**: Given Account 00000000099 is not in CXACAIX (RESP 13, RESP2 below 100). When The user views account 00000000099. Then ERRMSG 'Account:00000000099 not found in Cross ref file.  Resp:000000013  Reas:0000' (exactly 75 characters); for a missing customer 000000009: 'CustId:000000009 not found in customer master.Resp: 000000013  REAS:0000000'.

**Proven by**: source-only

**Finding**: The STRING overflows WS-RETURN-MSG X(75), so the RESP2 value is cut to its first 4 (xref/account) or 7 (customer) characters. Looks wrong; must be reproduced.

### BR-ONL-021 Account lookup unexpected file errors

**Rule**: Any RESP other than NORMAL or NOTFND sets INPUT-ERROR and overwrites WS-RETURN-MSG (no 'first message wins' guard) with WS-FILE-ERROR-MESSAGE: 'File Error: ' + 'READ' (X(8)) + ' on ' + file name (X(9): CXACAIX / ACCTDAT / CUSTDAT) + ' returned RESP ' + RESP (X(10)) + ',RESP2 ' + RESP2 (X(10)).

**Source**: `app/cbl/COACTVWC.cbl:86-105`; `app/cbl/COACTVWC.cbl:759-767`; `app/cbl/COACTVWC.cbl:809-817`; `app/cbl/COACTVWC.cbl:858-866`; `app/cbl/COACTUPC.cbl:3686-3694`; `app/cbl/COACTUPC.cbl:3736-3744`; `app/cbl/COACTUPC.cbl:3785-3793`

**Fields**: `WS-FILE-ERROR-MESSAGE`, `ERROR-OPNAME`, `ERROR-FILE`, `ERROR-RESP`, `ERROR-RESP2`

**Category**: return-code

**Test**: Given ACCTDAT is closed (READ returns RESP 19 NOTOPEN, RESP2 below 100). When The user views account 00000000001. Then ERRMSG 'File Error: READ     on ACCTDAT   returned RESP 000000019 ,RESP2 ' followed by the 9-digit RESP2 and a space (75 characters; the 5-byte trailing filler is cut).

**Proven by**: source-only

### BR-ONL-022 Account View display mapping

**Rule**: When the account or the customer was found, the account fields are moved to the screen (status, current balance, credit limit, cash credit limit, cycle credit, cycle debit, open/expiry/reissue dates, group id). When the customer was found, customer fields are moved, with the SSN formatted as 999-99-9999 (CUST-SSN(1:3) '-' (4:2) '-' (6:4)). The info line always shows 'Enter or update id of account to display'.

**Source**: `app/cbl/COACTVWC.cbl:460-534`; `app/cpy/CVACT01Y.cpy:4-17`; `app/cpy/CVCUS01Y.cpy:4-23`

**Fields**: `ACCT-ACTIVE-STATUS`, `ACCT-CURR-BAL`, `ACCT-CREDIT-LIMIT`, `ACCT-CASH-CREDIT-LIMIT`, `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT`, `ACCT-OPEN-DATE`, `ACCT-GROUP-ID`, `CUST-SSN`, `CUST-FICO-CREDIT-SCORE`, `ACSTSSNO`

**Category**: data-mapping

**Test**: Given Account 00000000001 with customer CUST-SSN 123456789. When The account is displayed. Then ACSTSSNO = '123-45-6789'; account and customer fields filled; INFOMSG 'Enter or update id of account to display'.

**Proven by**: source-only

**Finding**: If the account is missing but xref and customer exist, FOUND-CUST-IN-MASTER is still true, so the account fields are filled from an ACCOUNT-RECORD that was never read, next to the 'not found in Acct Master file' error. Looks wrong; must be reproduced.

### BR-ONL-023 Account Update state machine and info messages

**Rule**: COACTUPC keeps its state in ACUP-CHANGE-ACTION inside its part of the COMMAREA: LOW-VALUES/SPACES = details not fetched, 'S' = show details, 'E' = changes not OK, 'N' = changes OK not confirmed, 'C' = changes done, 'L' = lock error, 'F' = update failed. The info line follows the state: search keys 'Enter or update id of account to update', S/E 'Update account details presented above.', N 'Changes validated.Press F5 to save', C 'Changes committed to database', L/F 'Changes unsuccessful. Please try again'.

**Source**: `app/cbl/COACTUPC.cbl:464-477`; `app/cbl/COACTUPC.cbl:655-668`; `app/cbl/COACTUPC.cbl:2955-2982`

**Fields**: `ACUP-CHANGE-ACTION`, `WS-INFO-MSG`, `INFOMSGO`

**Category**: state-change

**Test**: Given Account Update with ACUP-CHANGE-ACTION = 'N'. When The screen is sent. Then INFOMSG 'Changes validated.Press F5 to save'; with 'C' it reads 'Changes committed to database'.

**Proven by**: source-only

### BR-ONL-024 Account Update valid keys

**Rule**: Valid AIDs are ENTER, PF3, PF5 only when ACUP-CHANGES-OK-NOT-CONFIRMED ('N'), and PF12 only when details have been fetched. Any other key, including PF5 before validation, is treated as ENTER.

**Source**: `app/cbl/COACTUPC.cbl:898-916`; `app/cpy/CSSTRPFY.cpy:17-80`

**Fields**: `CCARD-AID`, `ACUP-CHANGE-ACTION`, `PFK-VALID`

**Category**: control-flow

**Test**: Given Account details shown (state 'S') with an edited credit limit. When The user presses PF5 before pressing ENTER. Then PF5 is not valid in state 'S'; it is processed as ENTER, the edits run and the state becomes 'N' (no save happens).

**Proven by**: source-only

### BR-ONL-025 Account Update search and fetch

**Rule**: When no details are fetched, only the account id is edited: blank/'*' gives INPUT-ERROR and finally 'No input received' (overwriting 'Account number not provided'); not numeric or zero gives 'Account Number if supplied must be a 11 digit Non-Zero Number'. A valid id runs 9000-READ-ACCT; if the customer was found, the state becomes 'S' and original values are shown. First entry from the menu (or EIBCALEN = 0) only sends the empty search screen.

**Source**: `app/cbl/COACTUPC.cbl:880-893`; `app/cbl/COACTUPC.cbl:964-973`; `app/cbl/COACTUPC.cbl:1429-1449`; `app/cbl/COACTUPC.cbl:1783-1817`; `app/cbl/COACTUPC.cbl:2568-2580`

**Fields**: `CC-ACCT-ID`, `CC-ACCT-ID-N`, `ACUP-NEW-ACCT-ID`, `CDEMO-ACCT-ID`, `WS-RETURN-MSG`, `ACUP-CHANGE-ACTION`

**Category**: validation

**Test**: Given Account Update search screen (details not fetched). When The user enters 'ABCDEFGHIJK'; then '00000000001'. Then First: ERRMSG 'Account Number if supplied must be a 11 digit Non-Zero Number', no read. Second: xref/account/customer read, state 'S', INFOMSG 'Update account details presented above.'.

**Proven by**: source-only

### BR-ONL-026 Account Update input mapping and amount conversion

**Rule**: On receive, any field equal to '*' or SPACES becomes LOW-VALUES (not supplied). Amount fields (credit limit, cash credit limit, current balance, cycle credit, cycle debit) are copied as text (X(15)) and, when FUNCTION TEST-NUMVAL-C returns 0, converted with FUNCTION NUMVAL-C into PIC S9(10)V99 by COMPUTE without ROUNDED, so extra decimals are truncated. Currency sign, commas and a trailing CR/DB or '-' are accepted.

**Source**: `app/cbl/COACTUPC.cbl:411-416`; `app/cbl/COACTUPC.cbl:757-770`; `app/cbl/COACTUPC.cbl:1047-1140`; `app/cbl/COACTUPC.cbl:1401-1424`

**Fields**: `ACRDLIMI`, `ACUP-NEW-CREDIT-LIMIT-X`, `ACUP-NEW-CREDIT-LIMIT-N`, `ACUP-NEW-CASH-CREDIT-LIMIT-N`, `ACUP-NEW-CURR-BAL-N`, `ACUP-NEW-CURR-CYC-CREDIT-N`, `ACUP-NEW-CURR-CYC-DEBIT-N`

**Category**: rounding

**Test**: Given Account details shown. When The user types credit limit '$1,234.567' and current balance '250.00CR'. Then ACUP-NEW-CREDIT-LIMIT-N = 1234.56 (third decimal truncated, not rounded); ACUP-NEW-CURR-BAL-N = -250.00; an input of '*' in any field counts as blank.

**Proven by**: source-only

### BR-ONL-027 Account Update change detection

**Rule**: 1205-COMPARE-OLD-NEW compares new and fetched values: account fields exactly (numbers/dates), status and group id case-insensitively (group id trimmed); customer text fields trimmed and case-insensitive, phone parts, SSN, DOB, EFT id and FICO exactly. If nothing differs, WS-RETURN-MSG = 'No change detected with respect to values fetched.', the field edits are skipped and the state stays 'S'.

**Source**: `app/cbl/COACTUPC.cbl:491-492`; `app/cbl/COACTUPC.cbl:1460-1468`; `app/cbl/COACTUPC.cbl:1681-1776`; `app/cbl/COACTUPC.cbl:2585-2591`

**Fields**: `ACUP-NEW-DETAILS`, `ACUP-OLD-DETAILS`, `NO-CHANGES-FOUND`, `CHANGE-HAS-OCCURRED`, `WS-NON-KEY-FLAGS`

**Category**: validation

**Test**: Given Account details shown with first name 'JOHN'. When The user changes the first name to 'john ' (lower case, trailing space) and presses ENTER. Then No change is detected: ERRMSG 'No change detected with respect to values fetched.', no field edits, state stays 'S'.

**Proven by**: source-only

### BR-ONL-028 Account Update edit ladder (order and first-error-wins)

**Rule**: When a change is detected the state is set to 'E' and all fields are edited in this fixed order: Account Status, Open Date, Credit Limit, Expiry Date, Cash Credit Limit, Reissue Date, Current Balance, Current Cycle Credit, Current Cycle Debit, SSN, Date of Birth, FICO Score, First Name, Middle Name, Last Name, Address Line 1, State, Zip, City, Country, Phone Number 1, Phone Number 2, EFT Account Id, Primary Card Holder, then the state/zip combination. Every failing field is flagged (and highlighted), but only the first error message is kept because each edit writes WS-RETURN-MSG only while it is still empty (WS-RETURN-MSG-OFF).

**Source**: `app/cbl/COACTUPC.cbl:1470-1675`

**Fields**: `WS-EDIT-VARIABLE-NAME`, `WS-RETURN-MSG`, `INPUT-ERROR`, `ACUP-CHANGE-ACTION`

**Category**: validation

**Test**: Given Account details shown. When The user blanks the Credit Limit and also types 'X' as Account Status. Then Both fields are flagged; ERRMSG is 'Account Status must be Y or N.' (the status edit runs first); state 'E'.

**Proven by**: source-only

### BR-ONL-029 Account Update Y/N fields (Account Status, Primary Card Holder)

**Rule**: Account Status and Primary Card Holder go through 1220-EDIT-YESNO: LOW-VALUES, SPACES or ZEROS give '<name> must be supplied.'; a value other than 'Y' or 'N' gives '<name> must be Y or N.'. The check is case-sensitive.

**Source**: `app/cbl/COACTUPC.cbl:76-80`; `app/cbl/COACTUPC.cbl:1472-1476`; `app/cbl/COACTUPC.cbl:1657-1662`; `app/cbl/COACTUPC.cbl:1856-1893`

**Fields**: `ACUP-NEW-ACTIVE-STATUS`, `ACUP-NEW-CUST-PRI-HOLDER-IND`, `WS-EDIT-YES-NO`

**Category**: validation

**Test**: Given Account details shown with status 'Y'. When The user changes Account Status to 'y' and the credit limit from 5000.00 to 6000.00. Then ERRMSG 'Account Status must be Y or N.'; changing only 'Y' to 'y' is not even edited, because the change check upper-cases status and reports 'No change detected with respect to values fetched.'.

**Proven by**: source-only

### BR-ONL-030 Account Update signed amount fields

**Rule**: Credit Limit, Cash Credit Limit, Current Balance, Current Cycle Credit and Current Cycle Debit go through 1250-EDIT-SIGNED-9V2: blank gives '<name> must be supplied.'; TEST-NUMVAL-C not 0 gives '<name> is not valid' (no full stop). Negative values are allowed.

**Source**: `app/cbl/COACTUPC.cbl:1484-1527`; `app/cbl/COACTUPC.cbl:2180-2223`

**Fields**: `ACUP-NEW-CREDIT-LIMIT-X`, `ACUP-NEW-CURR-CYC-CREDIT-X`, `ACUP-NEW-CURR-CYC-DEBIT-X`, `WS-EDIT-SIGNED-NUMBER-9V2-X`

**Category**: validation

**Test**: Given Account details shown. When The user enters Current Cycle Debit '12A.00'. Then ERRMSG 'Current Cycle Debit Limit is not valid'.

**Proven by**: source-only

**Finding**: The cycle credit/debit amounts are labelled 'Current Cycle Credit Limit' / 'Current Cycle Debit Limit' in messages although they are not limits. Must be reproduced.

### BR-ONL-031 Account Update date fields (Open, Expiry, Reissue, DOB)

**Rule**: Dates are edited by EDIT-DATE-CCYYMMDD (CSUTLDPY): year required and 4-digit numeric (' : Year must be supplied.', ' must be 4 digit number.'), century only 19 or 20 (' : Century is not valid.'), month required 1-12 (': Month must be a number between 1 and 12.'), day required 1-31 (':day must be a number between 1 and 31.'), day 31 in a 30-day month (':Cannot have 31 days in this month.'), 30 February (':Cannot have 30 days in this month.'), 29 February in a non-leap year (divide by 400 when YY = 00, else by 4: ':Not a leap year.Cannot have 29 days in this month.'), then a final check through CSUTLDTC (LE CEEDAYS) (' validation error Sev code: ... Message code: ...'). Messages are prefixed with the field name.

**Source**: `app/cbl/COACTUPC.cbl:1478-1507`; `app/cbl/COACTUPC.cbl:1533-1538`; `app/cpy/CSUTLDPY.cpy:18-329`; `app/cpy/CSUTLDWY.cpy:9-24`

**Fields**: `ACUP-NEW-OPEN-DATE`, `ACUP-NEW-EXPIRAION-DATE`, `ACUP-NEW-REISSUE-DATE`, `ACUP-NEW-CUST-DOB-YYYY-MM-DD`, `WS-EDIT-DATE-CCYYMMDD`

**Category**: validation

**Test**: Given Account details shown. When The user sets Expiry Date to 2023-02-29; in another case Open Date year 2100. Then ERRMSG 'Expiry Date:Not a leap year.Cannot have 29 days in this month.'; year 2100 gives 'Open Date : Century is not valid.'; 1900-02-29 is rejected (YY = 00, 1900 / 400 has a remainder), 2000-02-29 is accepted.

**Proven by**: source-only

**Finding**: Only centuries 19 and 20 are accepted (the copybook says so explicitly), so any date from 2100 on is rejected. Must be reproduced.

### BR-ONL-032 Account Update date of birth must be in the past

**Rule**: If the DOB passes the date edit, EDIT-DATE-OF-BIRTH compares FUNCTION INTEGER-OF-DATE of the DOB with today; unless today is strictly later, it gives 'Date of Birth:cannot be in the future ' (with a trailing space).

**Source**: `app/cbl/COACTUPC.cbl:1533-1543`; `app/cpy/CSUTLDPY.cpy:341-370`

**Fields**: `ACUP-NEW-CUST-DOB-YYYY-MM-DD`, `WS-EDIT-DATE-BINARY`, `WS-CURRENT-DATE-BINARY`

**Category**: edge-case

**Test**: Given Clock 2022-07-05. When The user enters DOB 2022-07-05 (today); in a second case 2022-07-04. Then Today: ERRMSG 'Date of Birth:cannot be in the future '; yesterday: accepted.

**Proven by**: source-only

### BR-ONL-033 Account Update numeric-required fields (FICO, Zip, EFT)

**Rule**: FICO Score (3), Zip (5) and EFT Account Id (10) go through 1245-EDIT-NUM-REQD: blank '<name> must be supplied.', not all digits '<name> must be all numeric.', value 0 '<name> must not be zero.'. FICO must then be 300-850 ('FICO Score: should be between 300 and 850').

**Source**: `app/cbl/COACTUPC.cbl:846-849`; `app/cbl/COACTUPC.cbl:1545-1556`; `app/cbl/COACTUPC.cbl:1605-1611`; `app/cbl/COACTUPC.cbl:1648-1655`; `app/cbl/COACTUPC.cbl:2109-2174`; `app/cbl/COACTUPC.cbl:2514-2523`

**Fields**: `ACUP-NEW-CUST-FICO-SCORE-X`, `ACUP-NEW-CUST-ADDR-ZIP`, `ACUP-NEW-CUST-EFT-ACCOUNT-ID`, `FICO-RANGE-IS-VALID`

**Category**: validation

**Test**: Given Account details shown. When The user sets FICO to 299; in a second case Zip to '1234A'. Then FICO: ERRMSG 'FICO Score: should be between 300 and 850'; Zip: 'Zip must be all numeric.'.

**Proven by**: source-only

### BR-ONL-034 Account Update name, address, city and country text edits

**Rule**: First Name, Last Name (25), State (2), City (50) and Country (3) are required alphabetic: blank '<name> must be supplied.', anything other than A-Z, a-z and space '<name> can have alphabets only.'. Middle Name is optional but alphabetic if given. Address Line 1 (50) is mandatory ('Address Line 1 must be supplied.') with any characters. Address Line 2 and Government Issued Id are not edited.

**Source**: `app/cbl/COACTUPC.cbl:1560-1597`; `app/cbl/COACTUPC.cbl:1615-1630`; `app/cbl/COACTUPC.cbl:1824-1850`; `app/cbl/COACTUPC.cbl:1898-1949`; `app/cbl/COACTUPC.cbl:2012-2055`

**Fields**: `ACUP-NEW-CUST-FIRST-NAME`, `ACUP-NEW-CUST-MIDDLE-NAME`, `ACUP-NEW-CUST-LAST-NAME`, `ACUP-NEW-CUST-ADDR-LINE-1`, `ACUP-NEW-CUST-ADDR-LINE-3`, `ACUP-NEW-CUST-ADDR-COUNTRY-CD`, `ACUP-NEW-CUST-GOVT-ISSUED-ID`

**Category**: validation

**Test**: Given Account details shown. When The user sets Last Name to 'O'Brien' or City to 'St. Louis'. Then ERRMSG 'Last Name can have alphabets only.' / 'City can have alphabets only.'; a blank middle name is accepted.

**Proven by**: source-only

**Finding**: Apostrophes, hyphens and full stops are rejected in names and city, and the City edit reads ADDR-LINE-3. Address Line 2 and Government Id get no validation at all. Must be reproduced.

### BR-ONL-035 Account Update state code and state/zip combination

**Rule**: A valid alphabetic State must be in the US state table VALID-US-STATE-CODE ('State: is not a valid state code'). When State and Zip are both valid, state + first two zip digits must be in VALID-US-STATE-ZIP-CD2-COMBO, else 'Invalid zip code for state' (both fields flagged).

**Source**: `app/cbl/COACTUPC.cbl:1592-1602`; `app/cbl/COACTUPC.cbl:1665-1669`; `app/cbl/COACTUPC.cbl:2493-2513`; `app/cbl/COACTUPC.cbl:2536-2560`; `app/cpy/CSLKPCDY.cpy:1013-1013`; `app/cpy/CSLKPCDY.cpy:1073-1073`

**Fields**: `ACUP-NEW-CUST-ADDR-STATE-CD`, `ACUP-NEW-CUST-ADDR-ZIP`, `US-STATE-AND-FIRST-ZIP2`

**Category**: validation

**Test**: Given Account details shown. When The user sets State 'ZZ'; in a second case State 'NY' with Zip '90210'. Then 'ZZ': ERRMSG 'State: is not a valid state code'; NY + 90: 'Invalid zip code for state'.

**Proven by**: source-only

### BR-ONL-036 Account Update US phone number edit

**Rule**: Phone Number 1 and 2 are optional: when area code, prefix and line number are all blank the number is accepted. Otherwise area code must be supplied, 3 digits, non-zero and in the North American general-purpose area code list; prefix 3 digits non-zero; line number 4 digits non-zero. Messages are '<name>: Area code must be supplied.', ': Area code must be A 3 digit number.', ': Area code cannot be zero', ': Not valid North America general purpose area code', and the same for 'Prefix code' and 'Line number code'.

**Source**: `app/cbl/COACTUPC.cbl:1632-1646`; `app/cbl/COACTUPC.cbl:2225-2430`; `app/cpy/CSLKPCDY.cpy:521-521`

**Fields**: `ACUP-NEW-CUST-PHONE-NUM-1A`, `ACUP-NEW-CUST-PHONE-NUM-1B`, `ACUP-NEW-CUST-PHONE-NUM-1C`, `WS-EDIT-US-PHONE-NUM`, `VALID-GENERAL-PURP-CODE`

**Category**: validation

**Test**: Given Account details shown. When The user enters Phone Number 1 as area '000', prefix '555', line '0100'. Then ERRMSG 'Phone Number 1: Area code cannot be zero'.

**Proven by**: source-only

**Finding**: The 'all blank' test checks area code twice and never checks that the line number is blank (line 2238 tests NUMA instead of NUMC). Area and prefix blank with line '1234' is accepted as 'no phone' and saved as '(   )   -1234'. Must be reproduced.

### BR-ONL-037 Account Update SSN edit

**Rule**: The SSN is entered in three parts: part 1 (3 digits) must be numeric, non-zero and not 000, 666 or 900-999 ('SSN: First 3 chars: should not be 000, 666, or between 900 and 999'); part 2 (2 digits) and part 3 (4 digits) must be numeric and non-zero ('SSN 4th & 5th chars must not be zero.', 'SSN Last 4 chars must be all numeric.').

**Source**: `app/cbl/COACTUPC.cbl:119-123`; `app/cbl/COACTUPC.cbl:1529-1530`; `app/cbl/COACTUPC.cbl:2431-2492`

**Fields**: `ACUP-NEW-CUST-SSN-1`, `ACUP-NEW-CUST-SSN-2`, `ACUP-NEW-CUST-SSN-3`, `INVALID-SSN-PART1`

**Category**: validation

**Test**: Given Account details shown. When The user sets the SSN to 666-12-3456. Then ERRMSG 'SSN: First 3 chars: should not be 000, 666, or between 900 and 999'.

**Proven by**: source-only

**Finding**: Part 1 value 000 is caught first by the non-zero check ('SSN: First 3 chars must not be zero.'), so the 000 case of the range message is unreachable. Must be reproduced.

### BR-ONL-038 Account Update validated changes await PF5 confirmation

**Rule**: If no edit failed, the state becomes 'N' (changes OK, not confirmed), the screen shows the new values with the fields protected and the info 'Changes validated.Press F5 to save'. ENTER in state 'N' skips the edits and keeps waiting; only PF5 saves.

**Source**: `app/cbl/COACTUPC.cbl:1463-1468`; `app/cbl/COACTUPC.cbl:1671-1675`; `app/cbl/COACTUPC.cbl:2585-2591`; `app/cbl/COACTUPC.cbl:2620-2621`; `app/cbl/COACTUPC.cbl:2966-2967`

**Fields**: `ACUP-CHANGE-ACTION`, `INPUT-ERROR`, `WS-INFO-MSG`

**Category**: state-change

**Test**: Given Account details shown; the user changed the credit limit from 5000.00 to 6000.00. When The user presses ENTER, then ENTER again. Then State 'N', INFOMSG 'Changes validated.Press F5 to save'; the second ENTER changes nothing.

**Proven by**: source-only

### BR-ONL-039 PF5 save: READ UPDATE locks on account and customer

**Rule**: PF5 in state 'N' runs 9600-WRITE-PROCESSING: EXEC CICS READ UPDATE on ACCTDAT by account id; on failure INPUT-ERROR and 'Could not lock account record for update', state 'L' (info 'Changes unsuccessful. Please try again'). Then READ UPDATE on CUSTDAT by CDEMO-CUST-ID; on failure 'Could not lock customer record for update'.

**Source**: `app/cbl/COACTUPC.cbl:517-520`; `app/cbl/COACTUPC.cbl:2602-2615`; `app/cbl/COACTUPC.cbl:3888-3942`

**Fields**: `WS-CARD-RID-ACCT-ID-X`, `WS-CARD-RID-CUST-ID-X`, `WS-RESP-CD`, `ACUP-CHANGE-ACTION`

**Category**: return-code

**Test**: Given State 'N' and ACCTDAT record 00000000001 held by another task. When The user presses PF5. Then ERRMSG 'Could not lock account record for update', state 'L', INFOMSG 'Changes unsuccessful. Please try again'.

**Proven by**: source-only

**Finding**: A customer lock failure is not in the 2000-DECIDE-ACTION list, so it falls to WHEN OTHER and sets 'C': the screen shows 'Changes committed to database' together with 'Could not lock customer record for update', although nothing was written. Looks wrong; must be reproduced.

### BR-ONL-040 PF5 optimistic concurrency check

**Rule**: After both locks, 9700-CHECK-CHANGE-IN-REC compares the records just read for update with the snapshot taken when they were displayed: account status, balances, limits, cycle amounts, open/expiry/reissue date parts, group id (lower-cased) and customer names/addresses/state/country/gov id (upper-cased), zip, phones, SSN, DOB parts, EFT id, primary holder, FICO. Any difference gives 'Record changed by some one else. Please review', no rewrite, and the state goes back to 'S'.

**Source**: `app/cbl/COACTUPC.cbl:521-522`; `app/cbl/COACTUPC.cbl:2611-2612`; `app/cbl/COACTUPC.cbl:3943-3952`; `app/cbl/COACTUPC.cbl:4109-4195`

**Fields**: `ACUP-OLD-DETAILS`, `ACCOUNT-RECORD`, `CUSTOMER-RECORD`, `DATA-WAS-CHANGED-BEFORE-UPDATE`

**Category**: validation

**Test**: Given User A fetched account 00000000001 with balance 100.00; user B then saved balance 150.00. When User A presses PF5 on their own validated changes. Then ERRMSG 'Record changed by some one else. Please review', nothing rewritten, state 'S'.

**Proven by**: source-only

### BR-ONL-041 PF5 record build, REWRITE and rollback

**Rule**: The account update record is built from the new values (amounts from the NUMVAL-C fields, dates rebuilt as YYYY-MM-DD, group id) and the customer record from the new customer values (phones rebuilt as '(aaa)bbb-cccc', DOB as YYYY-MM-DD, SSN, FICO). ACCTDAT is rewritten first; a failure gives 'Update of record failed' (state 'F'). CUSTDAT is rewritten next; a failure gives 'Update of record failed' and SYNCPOINT ROLLBACK, which also undoes the account rewrite. Success sets state 'C' (info 'Changes committed to database').

**Source**: `app/cbl/COACTUPC.cbl:523-524`; `app/cbl/COACTUPC.cbl:2606-2615`; `app/cbl/COACTUPC.cbl:3956-4106`

**Fields**: `ACCT-UPDATE-RECORD`, `CUST-UPDATE-RECORD`, `ACCT-UPDATE-CREDIT-LIMIT`, `ACCT-UPDATE-OPEN-DATE`, `CUST-UPDATE-PHONE-NUM-1`, `CUST-UPDATE-DOB-YYYY-MM-DD`

**Category**: state-change

**Test**: Given State 'N' with new credit limit 6000.00 and phone parts 212/555/0100. When The user presses PF5 and both rewrites succeed. Then ACCTDAT ACCT-CREDIT-LIMIT = 6000.00; CUSTDAT CUST-PHONE-NUM-1 = '(212)555-0100'; INFOMSG 'Changes committed to database'; if the CUSTDAT rewrite fails both records keep their old values.

**Proven by**: source-only

### BR-ONL-042 After save / PF12 cancel

**Rule**: In state 'C', 'L' or 'F' the next interaction resets the program area and CDEMO-ACCT-ID and shows the empty search screen. When the save succeeded and the program was not entered from another transaction (CDEMO-FROM-TRANID blank), CDEMO-ACCT-ID and CDEMO-CARD-NUM are zeroed and CDEMO-ACCT-STATUS set to LOW-VALUES. PF12 (valid once details were fetched) re-reads the account and shows the stored values, discarding the edits.

**Source**: `app/cbl/COACTUPC.cbl:979-989`; `app/cbl/COACTUPC.cbl:2572-2580`; `app/cbl/COACTUPC.cbl:2625-2632`

**Fields**: `ACUP-CHANGE-ACTION`, `CDEMO-ACCT-ID`, `CDEMO-CARD-NUM`, `CDEMO-ACCT-STATUS`

**Category**: control-flow

**Test**: Given State 'E' after the user typed an invalid FICO 200. When The user presses PF12. Then Account 00000000001 is read again, original values are shown, state 'S', the FICO error is gone.

**Proven by**: source-only

### BR-ONL-043 COMMAREA layout and pseudo-conversational return

**Rule**: Account View and Update keep the shared CARDDEMO-COMMAREA (general info, customer, account, card, last map) followed by their own WS-THIS-PROGCOMMAREA, packed into a 2000-byte WS-COMMAREA and returned with EXEC CICS RETURN TRANSID('CAVW' / 'CAUP'). On entry with EIBCALEN = 0, or from the menu with context 0, both parts are initialised; otherwise they are restored from DFHCOMMAREA.

**Source**: `app/cpy/COCOM01Y.cpy:19-44`; `app/cbl/COACTVWC.cbl:282-293`; `app/cbl/COACTVWC.cbl:394-407`; `app/cbl/COACTUPC.cbl:850-850`; `app/cbl/COACTUPC.cbl:880-893`; `app/cbl/COACTUPC.cbl:1007-1020`

**Fields**: `CARDDEMO-COMMAREA`, `WS-THIS-PROGCOMMAREA`, `WS-COMMAREA`, `CDEMO-PGM-CONTEXT`, `EIBCALEN`

**Category**: data-mapping

**Test**: Given Account Update shows account 00000000001 (state 'S'). When The task ends. Then RETURN TRANSID('CAUP') with a 2000-byte COMMAREA: bytes 1-160 = CARDDEMO-COMMAREA (CDEMO-ACCT-ID 00000000001), then the COACTUPC area with ACUP-CHANGE-ACTION 'S' and the old/new values.

**Proven by**: source-only

### BR-ONL-044 COBDATFT linkage, registers and return code

**Rule**: COBDATFT uses standard OS linkage: STM R14,R12,12(R13); its own 18-word SAVE area chained both ways (SAVE+4 / 8(,R13)); R13 -> SAVE; R12 base; R1 -> parameter list; R2 -> the single parameter (COREC: COINTYPE CL1, COINPDT CL20, COOUTYPE CL1, COOUTDT CL20, COERMSG CL38, matching CODATECN-REC). On exit it restores R13 and R14-R12 and does SR R15,R15, so the return code is always 0; errors are only signalled in COERMSG. Caller: CBACT01C (CALL 'COBDATFT' USING CODATECN-REC).

**Source**: `app/asm/COBDATFT.asm:17-29`; `app/asm/COBDATFT.asm:57-64`; `app/maclib/COCDATFT.mac:17-23`; `app/cpy/CODATECN.cpy:17-52`; `app/cbl/CBACT01C.cbl:223-233`

**Fields**: `CODATECN-REC`, `COINTYPE`, `COINPDT`, `COOUTYPE`, `COOUTDT`, `COERMSG`, `R15`

**Category**: return-code

**Test**: Given CODATECN-REC with type '2', date '2022-07-05', out type '2'. When CBACT01C calls COBDATFT. Then RETURN-CODE (R15) = 0 and the caller's registers R2-R12 are restored.

**Proven by**: source-only

**Finding**: docs/LEGACY-LANGUAGES.md says R15 is not set, but the source zeroes R15 (SR R15,R15) on every path. The source wins: the converted routine must return 0 even on INVALID INPUT.

### BR-ONL-045 COBDATFT type 1: YYYYMMDD to YYYY-MM-DD

**Rule**: When COINTYPE = '1' and COOUTYPE is not '2', COBDATFT builds COOUTDT as COINPDT(1:4) '-' COINPDT(5:2) '-' COINPDT(7:2) (10 bytes). The remaining 10 bytes of COOUTDT and COERMSG are not touched. There is no check that the digits are numeric or a real date.

**Source**: `app/asm/COBDATFT.asm:30-31`; `app/asm/COBDATFT.asm:35-45`

**Fields**: `COINTYPE`, `COINPDT`, `COOUTYPE`, `COOUTDT`

**Category**: data-mapping

**Test**: Given COINTYPE '1', COINPDT '20220705', COOUTYPE '1'. When COBDATFT is called. Then COOUTDT(1:10) = '2022-07-05', COERMSG unchanged, R15 = 0.

**Proven by**: source-only

**Finding**: The type-1 hyphen guard CLC COINPDT+4,=C'-' takes its implicit length from COINPDT (20 bytes), so it compares 20 bytes against a 1-byte literal plus whatever follows in the literal pool and in practice never matches; a type-1 input already in YYYY-MM-DD form is reformatted instead of rejected. Must be reproduced (from reading the code; HLASM cannot be run off-host).

### BR-ONL-046 COBDATFT type 2: YYYY-MM-DD to YYYYMMDD (separator not checked)

**Rule**: When COINTYPE = '2' and COOUTYPE is not '1', COOUTDT(1:8) = COINPDT(1:4) + COINPDT(6:2) + COINPDT(9:2). The separator check is commented out, so any character in positions 5 and 8 is accepted. CBACT01C uses this path for ACCT-REISSUE-DATE with type '2' and out type '2'.

**Source**: `app/asm/COBDATFT.asm:32-33`; `app/asm/COBDATFT.asm:46-54`; `app/cbl/CBACT01C.cbl:223-233`

**Fields**: `COINTYPE`, `COINPDT`, `COOUTYPE`, `COOUTDT`, `ACCT-REISSUE-DATE`

**Category**: data-mapping

**Test**: Given COINTYPE '2', COINPDT '2022/07/05', COOUTYPE '2'. When COBDATFT is called. Then COOUTDT(1:8) = '20220705' (the '/' separators are accepted), bytes 9-20 of COOUTDT unchanged, R15 = 0.

**Proven by**: source-only

**Finding**: Because the hyphen check (CLC/BNE at lines 47-48) is commented out, malformed dates are reformatted instead of rejected. Must be reproduced.

### BR-ONL-047 COBDATFT INVALID INPUT

**Rule**: COBDATFT moves 'INVALID INPUT' to COERMSG and leaves COOUTDT unchanged when COINTYPE is neither '1' nor '2', when type '1' is asked for output type '2', or when type '2' is asked for output type '1'. R15 is still 0. COERMSG is never cleared on success, so the caller must clear it.

**Source**: `app/asm/COBDATFT.asm:30-34`; `app/asm/COBDATFT.asm:38-39`; `app/asm/COBDATFT.asm:49-50`; `app/asm/COBDATFT.asm:55-57`

**Fields**: `COINTYPE`, `COOUTYPE`, `COERMSG`, `CODATECN-ERROR-MSG`

**Category**: return-code

**Test**: Given COINTYPE '3', COINPDT '20220705'. When COBDATFT is called. Then COERMSG starts with 'INVALID INPUT', COOUTDT unchanged, R15 = 0.

**Proven by**: source-only

**Finding**: MVC COERMSG,=C'INVALID INPUT' copies L'COERMSG = 38 bytes from a 13-byte literal, so bytes 14-38 come from whatever follows in the literal pool. On-host content is undefined; a port should keep 'INVALID INPUT' in bytes 1-13 and record bytes 14-38 as unspecified (proposed: spaces). Looks wrong; flagged for review.

### BR-ONL-048 MVSWAIT timed wait and COBSWAIT driver

**Rule**: COBSWAIT reads an 8-character SYSIN value, moves it to a PIC 9(8) COMP fullword (MVSWAIT-TIME) and calls MVSWAIT. MVSWAIT (USING *,15) saves registers in the caller's save area (no save area of its own), loads the address of the fullword (L 5,0(1)) and its value (L 1,0(5)), stores it in BINLBL, waits that many hundredths of a second via the ASMWAIT (STIMER WAIT) macro, restores registers, sets the caller save-area byte 12(13) to X'FF', zeroes R15 (RC 0) and returns. There is no functional output.

**Source**: `app/asm/MVSWAIT.asm:17-30`; `app/cbl/COBSWAIT.cbl:30-40`; `app/jcl/WAITSTEP.jcl:22-26`

**Fields**: `MVSWAIT-TIME`, `PARM-VALUE`, `BINLBL`, `R15`

**Category**: return-code

**Test**: Given WAITSTEP.jcl SYSIN card '00003600      VALUE IN CENTISECONDS'. When Job WAITSTEP runs COBSWAIT. Then PARM-VALUE = '00003600', MVSWAIT-TIME = 3600, the task waits 36.00 seconds, RC 0; the off-host port treats the wait as a no-op with RC 0.

**Proven by**: source-only

**Finding**: COBSWAIT moves the SYSIN text straight into a binary field with no NUMERIC check; a non-numeric card gives an unpredictable delay. Also MVI 12(13),X'FF' writes into the caller's save area (the source asks whether it can be removed). Must be reproduced, not fixed.

### BR-ONL-049 CBFEECAL late fee is truncated to cents

**Rule**: WORK_FEE = FEE_CYCLE_BAL * FEE_PCT (2.500) / 100 in DECIMAL FIXED(15,5) and is assigned to FEE_AMT DECIMAL FIXED(9,2); PL/I assignment truncates the extra decimals (no rounding). RC 0.

**Source**: `app/pli/CBFEECAL.pli:36-40`; `app/pli/CBFEECAL.pli:79-80`; `docs/LEGACY-LANGUAGES.md:71-73`

**Fields**: `FEE_CYCLE_BAL`, `FEE_PCT`, `WORK_FEE`, `FEE_AMT`, `FEE-AMT`

**Category**: rounding

**Test**: Given Worked example 1: GROUP_ID 'STANDARD', CYCLE_BAL 1234.56, LATE_DAYS 10, CYCLE_DAYS 30. When CBFEECAL is called. Then FEE_AMT = 30.86 (30.864 truncated, not 30.87), FEE_AVG_DAILY = 41.15, FEE_RC = 0.

**Proven by**: source-only

### BR-ONL-050 CBFEECAL floor 5.00 and cap 35.00

**Rule**: After truncation, a fee below FEE_FLOOR (5.00) is raised to 5.00 and a fee above FEE_CAP (35.00) is lowered to 35.00. RC 0.

**Source**: `app/pli/CBFEECAL.pli:37-38`; `app/pli/CBFEECAL.pli:82-83`; `docs/LEGACY-LANGUAGES.md:74-75`

**Fields**: `FEE_AMT`, `FEE_FLOOR`, `FEE_CAP`

**Category**: calculation

**Test**: Given Worked examples 2 and 3: 'STANDARD' with CYCLE_BAL 100.00, and with 5000.00 (LATE_DAYS 10, CYCLE_DAYS 30). When CBFEECAL is called. Then 100.00: 2.50 raised to FEE_AMT 5.00, FEE_AVG_DAILY 3.33, RC 0. 5000.00: 125.00 capped to 35.00, FEE_AVG_DAILY 166.66, RC 0.

**Proven by**: source-only

### BR-ONL-051 CBFEECAL GOLD waiver (RC 4)

**Rule**: If FEE_GROUP_ID = 'GOLD' (CHAR(10), compared blank-padded) and FEE_CYCLE_BAL < 100.00 (WAIVE_BELOW), the fee is waived: FEE_AMT stays 0, FEE_RC = 4. The average daily balance has already been computed.

**Source**: `app/pli/CBFEECAL.pli:39-39`; `app/pli/CBFEECAL.pli:63-77`; `docs/LEGACY-LANGUAGES.md:76-76`

**Fields**: `FEE_GROUP_ID`, `FEE_CYCLE_BAL`, `WAIVE_BELOW`, `FEE_RC`, `FEE_AVG_DAILY`

**Category**: return-code

**Test**: Given Worked example 4: GROUP_ID 'GOLD', CYCLE_BAL 80.00, LATE_DAYS 10, CYCLE_DAYS 30. When CBFEECAL is called. Then FEE_AMT = 0.00, FEE_AVG_DAILY = 2.66 (80/30 truncated), FEE_RC = 4.

**Proven by**: source-only

### BR-ONL-052 CBFEECAL average daily balance and ZERODIVIDE (RC 8)

**Rule**: FEE_AVG_DAILY = FEE_CYCLE_BAL / FEE_CYCLE_DAYS is assigned to DECIMAL FIXED(12,2) (truncating). With 0 cycle days ZERODIVIDE is raised: the ON-unit writes 'CBFEECAL: ZERODIVIDE FOR ACCOUNT ' + FEE_ACCT_ID to SYSPRINT (PUT SKIP LIST), sets FEE_AMT = 0, FEE_AVG_DAILY = 0, FEE_RC = 8 and leaves through FEE_EXIT.

**Source**: `app/pli/CBFEECAL.pli:42-48`; `app/pli/CBFEECAL.pli:68-69`; `docs/LEGACY-LANGUAGES.md:77-81`

**Fields**: `FEE_CYCLE_DAYS`, `FEE_AVG_DAILY`, `FEE_AMT`, `FEE_RC`, `FEE_ACCT_ID`

**Category**: sysout

**Test**: Given Worked example 5: 'STANDARD', CYCLE_BAL 1000.00, LATE_DAYS 10, CYCLE_DAYS 0, ACCT_ID '00000000005'. When CBFEECAL is called. Then SYSPRINT gets a list-directed line with the items 'CBFEECAL: ZERODIVIDE FOR ACCOUNT ' and '00000000005' (item spacing follows PL/I list-directed tab stops); FEE_AMT 0.00, FEE_AVG_DAILY 0.00, FEE_RC 8.

**Proven by**: source-only

### BR-ONL-053 CBFEECAL nothing overdue (late days <= 0)

**Rule**: If FEE_LATE_DAYS <= 0 the routine exits with FEE_AMT 0 and FEE_RC 0, before the GOLD waiver and the fee formula. The average daily division runs before this check, so zero cycle days still gives ZERODIVIDE RC 8 even when nothing is overdue.

**Source**: `app/pli/CBFEECAL.pli:63-72`; `docs/LEGACY-LANGUAGES.md:78-78`

**Fields**: `FEE_LATE_DAYS`, `FEE_AMT`, `FEE_RC`, `FEE_AVG_DAILY`

**Category**: edge-case

**Test**: Given Worked example 6: GROUP_ID 'GOLD', CYCLE_BAL 0.00, LATE_DAYS 0, CYCLE_DAYS 30. When CBFEECAL is called. Then FEE_AMT 0.00, FEE_AVG_DAILY 0.00, FEE_RC 0 (not 4, because the late-days exit comes before the GOLD test); with CYCLE_DAYS 0 the same request gives RC 8.

**Proven by**: source-only

### BR-ONL-054 CBFEECAL SIZE and ERROR conditions (RC 8)

**Rule**: The calculation block runs with the SIZE condition enabled. ON SIZE writes 'CBFEECAL: SIZE CONDITION FOR ACCOUNT ' + id, sets FEE_AMT = 0 and FEE_RC = 8. ON ERROR writes 'CBFEECAL: UNEXPECTED ERROR, ACCOUNT ' + id and sets FEE_RC = 8 without resetting FEE_AMT or FEE_AVG_DAILY. Both leave through FEE_EXIT.

**Source**: `app/pli/CBFEECAL.pli:50-61`; `app/pli/CBFEECAL.pli:63-65`; `app/pli/CBFEECAL.pli:86-87`

**Fields**: `FEE_AMT`, `FEE_RC`, `FEE_AVG_DAILY`, `FEE_ACCT_ID`

**Category**: return-code

**Test**: Given A request whose FEE_CYCLE_BAL / FEE_CYCLE_DAYS result does not fit DECIMAL FIXED(12,2) (e.g. a corrupt packed field read from storage). When CBFEECAL is called. Then SYSPRINT 'CBFEECAL: SIZE CONDITION FOR ACCOUNT <id>', FEE_AMT 0, FEE_RC 8.

**Proven by**: source-only

**Finding**: ON ERROR keeps whatever FEE_AMT / FEE_AVG_DAILY held when the error was raised, unlike the other ON-units that zero them. Must be reproduced.

### BR-ONL-055 CBFEEDRV driver: fee request and FEEOUT line

**Rule**: For every ACCTFILE record CBFEEDRV sets FEE-ACCT-ID, FEE-GROUP-ID, FEE-CYCLE-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT, FEE-LATE-DAYS = 10 when the cycle balance > 0 else 0, FEE-CYCLE-DAYS = 30, clears FEE-AMT/FEE-AVG-DAILY/FEE-RC, calls CBFEECAL BY REFERENCE and writes an 80-byte line: account id X(11), space, group X(10), space, cycle balance -(10)9.99, space, fee -(7)9.99, space, RC 99, 28 spaces (job FEECALC STEP05, output AWS.M2.CARDDEMO.LATEFEES(+1)).

**Source**: `app/cbl/CBFEEDRV.cbl:54-73`; `app/cbl/CBFEEDRV.cbl:86-108`; `app/jcl/FEECALC.jcl:24-34`

**Fields**: `ACCT-CURR-CYC-CREDIT`, `ACCT-CURR-CYC-DEBIT`, `FEE-CYCLE-BAL`, `FEE-LATE-DAYS`, `FEE-CYCLE-DAYS`, `FEE-LINE`, `FL-RC`

**Category**: calculation

**Test**: Given Account 00000000001, group 'STANDARD', cycle credit 1500.00, cycle debit 265.44. When FEECALC runs. Then FEE-CYCLE-BAL 1234.56, LATE-DAYS 10, CYCLE-DAYS 30; FEEOUT line '00000000001 STANDARD          1234.56       30.86 00' padded with spaces to 80 bytes; an account with cycle debit > credit gets LATE-DAYS 0, fee 0.00, RC 00.

**Proven by**: source-only

**Finding**: The cycle balance is credit minus debit, so an account is treated as overdue when its credits exceed its debits, which looks inverted for a balance due. Must be reproduced as written.

## Machine-readable rules

```json
{
 "slice": "online",
 "rules": [
  {
   "id": "BR-ONL-001",
   "title": "Sign-on first display and screen header",
   "rule": "When COSGN00C is started with no COMMAREA (EIBCALEN = 0) it clears the output map, puts the cursor on the User ID field and sends the sign-on screen. Every screen send fills the header: titles, transaction CC00, program COSGN00C, date as MM/DD/YY and time as HH:MM:SS from FUNCTION CURRENT-DATE, plus APPLID/SYSID. The task always ends with RETURN TRANSID('CC00') passing CARDDEMO-COMMAREA.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 80,
     "endLine": 83
    },
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 98,
     "endLine": 102
    },
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 145,
     "endLine": 157
    },
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 177,
     "endLine": 204
    },
    {
     "file": "app/cpy/CSDAT01Y.cpy",
     "startLine": 31,
     "endLine": 41
    }
   ],
   "fields": [
    "EIBCALEN",
    "COSGN0AO",
    "USERIDL",
    "CURDATEO",
    "CURTIMEO",
    "TRNNAMEO",
    "PGMNAMEO",
    "WS-CURDATE-MM-DD-YY",
    "WS-CURTIME-HH-MM-SS"
   ],
   "given": "Clock frozen at 2022-07-05 10:00:00 and no COMMAREA (EIBCALEN = 0)",
   "when": "Transaction CC00 starts",
   "then": "The sign-on screen is sent with ERRMSG blank, CURDATE '07/05/22', CURTIME '10:00:00', TRNNAME 'CC00', PGMNAME 'COSGN00C', cursor on USERID; the task returns with TRANSID CC00",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-002",
   "title": "Sign-on PF3 and invalid keys",
   "rule": "On the sign-on screen PF3 sends the plain text 'Thank you for using CardDemo application...' (CCDA-MSG-THANK-YOU) with ERASE/FREEKB and ends the task without a next TRANSID. ENTER processes the sign-on. Any other AID re-sends the sign-on screen with 'Invalid key pressed. Please see below...' (CCDA-MSG-INVALID-KEY).",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 85,
     "endLine": 95
    },
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 162,
     "endLine": 172
    },
    {
     "file": "app/cpy/CSMSG01Y.cpy",
     "startLine": 17,
     "endLine": 21
    }
   ],
   "fields": [
    "EIBAID",
    "CCDA-MSG-THANK-YOU",
    "CCDA-MSG-INVALID-KEY",
    "WS-MESSAGE"
   ],
   "given": "The sign-on screen is displayed (EIBCALEN > 0)",
   "when": "The user presses PF3; in a second case the user presses PF7",
   "then": "PF3: the screen is erased and shows 'Thank you for using CardDemo application...' and the session ends (plain RETURN). PF7: the sign-on screen is shown again with ERRMSG 'Invalid key pressed. Please see below...'",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-003",
   "title": "Sign-on blank User ID / Password validation",
   "rule": "On ENTER the map is received and checked in order: User ID equal to SPACES or LOW-VALUES gives 'Please enter User ID ...' with the cursor on USERID; otherwise a blank Password gives 'Please enter Password ...' with the cursor on PASSWD. Either error sets WS-ERR-FLG = 'Y' and USRSEC is not read. Both input fields are 8 characters (BMS LENGTH=8, password is DRK/non-display).",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 108,
     "endLine": 140
    },
    {
     "file": "app/bms/COSGN00.bms",
     "startLine": 156,
     "endLine": 159
    },
    {
     "file": "app/bms/COSGN00.bms",
     "startLine": 175,
     "endLine": 178
    },
    {
     "file": "app/cpy-bms/COSGN00.CPY",
     "startLine": 72,
     "endLine": 78
    }
   ],
   "fields": [
    "USERIDI",
    "PASSWDI",
    "WS-ERR-FLG",
    "ERRMSGO"
   ],
   "given": "Sign-on screen with USERID = spaces and PASSWD = 'PASS0001'; second case USERID = 'USER0001' and PASSWD = spaces",
   "when": "The user presses ENTER",
   "then": "Case 1: ERRMSG 'Please enter User ID ...', cursor on USERID, no file read. Case 2: ERRMSG 'Please enter Password ...', cursor on PASSWD, no file read",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-004",
   "title": "Sign-on upper-cases User ID and Password",
   "rule": "Before the lookup, both the entered User ID and the Password are converted with FUNCTION UPPER-CASE (User ID also into CDEMO-USER-ID). The USRSEC key and the password comparison therefore are case-insensitive for the input.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 132,
     "endLine": 136
    },
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 223,
     "endLine": 223
    }
   ],
   "fields": [
    "USERIDI",
    "PASSWDI",
    "WS-USER-ID",
    "WS-USER-PWD",
    "CDEMO-USER-ID",
    "SEC-USR-PWD"
   ],
   "given": "USRSEC holds SEC-USR-ID 'USER0001' with SEC-USR-PWD 'PASSWORD'",
   "when": "The user signs on with User ID 'user0001' and Password 'password'",
   "then": "Lookup key is 'USER0001', the compared password is 'PASSWORD', sign-on succeeds",
   "fixtures": [],
   "finding": "Passwords are compared after upper-casing the input only: a password stored in lower case in USRSEC can never match, and passwords are effectively case-insensitive. Looks wrong (weak credential check) but must be reproduced."
  },
  {
   "id": "BR-ONL-005",
   "title": "Sign-on USRSEC lookup return codes",
   "rule": "USRSEC is read by the 8-byte upper-cased User ID (EXEC CICS READ DATASET('USRSEC') RIDFLD(WS-USER-ID)). RESP 0 proceeds to the password check; RESP 13 (NOTFND) gives 'User not found. Try again ...'; any other RESP gives 'Unable to verify the User ...'. Both errors put the cursor on USERID and re-send the screen.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 209,
     "endLine": 221
    },
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 247,
     "endLine": 257
    },
    {
     "file": "app/cpy/CSUSR01Y.cpy",
     "startLine": 17,
     "endLine": 23
    }
   ],
   "fields": [
    "WS-USRSEC-FILE",
    "WS-USER-ID",
    "WS-RESP-CD",
    "SEC-USER-DATA"
   ],
   "given": "USRSEC does not contain 'NOUSER01'",
   "when": "The user signs on with User ID 'NOUSER01' and any password",
   "then": "READ returns RESP 13 and the screen shows 'User not found. Try again ...' with cursor on USERID; an I/O error (e.g. RESP 12 file not open) shows 'Unable to verify the User ...'",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-006",
   "title": "Sign-on wrong password",
   "rule": "When the USRSEC record exists but SEC-USR-PWD differs from the upper-cased input password, the screen is re-sent with 'Wrong Password. Try again ...' and the cursor on PASSWD. No COMMAREA routing fields are set.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 223,
     "endLine": 223
    },
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 241,
     "endLine": 246
    }
   ],
   "fields": [
    "SEC-USR-PWD",
    "WS-USER-PWD",
    "PASSWDL",
    "WS-MESSAGE"
   ],
   "given": "USRSEC record 'USER0001' with password 'PASSWORD'",
   "when": "The user signs on with 'USER0001' / 'WRONGPW1'",
   "then": "ERRMSG 'Wrong Password. Try again ...', cursor on PASSWD, no XCTL",
   "fixtures": [],
   "finding": "This branch does not set WS-ERR-FLG (the blank-field and not-found branches do). It has no visible effect because nothing runs after it, but a port that relies on the flag must keep the same outcome."
  },
  {
   "id": "BR-ONL-007",
   "title": "Sign-on success: COMMAREA and admin vs regular routing",
   "rule": "On a password match COSGN00C fills CARDDEMO-COMMAREA: CDEMO-FROM-TRANID = 'CC00', CDEMO-FROM-PROGRAM = 'COSGN00C', CDEMO-USER-ID = upper-cased id, CDEMO-USER-TYPE = SEC-USR-TYPE, CDEMO-PGM-CONTEXT = 0 (enter). If the user type is 'A' (CDEMO-USRTYP-ADMIN) it XCTLs to COADM01C, otherwise (any other value, including 'U' or blank) to COMEN01C, passing the COMMAREA.",
   "category": "state-change",
   "sources": [
    {
     "file": "app/cbl/COSGN00C.cbl",
     "startLine": 222,
     "endLine": 240
    },
    {
     "file": "app/cpy/COCOM01Y.cpy",
     "startLine": 19,
     "endLine": 44
    }
   ],
   "fields": [
    "CDEMO-FROM-TRANID",
    "CDEMO-FROM-PROGRAM",
    "CDEMO-USER-ID",
    "CDEMO-USER-TYPE",
    "CDEMO-PGM-CONTEXT",
    "SEC-USR-TYPE"
   ],
   "given": "USRSEC records ADMIN001/PASSWORD type 'A' and USER0001/PASSWORD type 'U'",
   "when": "Each user signs on with the correct password",
   "then": "ADMIN001: XCTL COADM01C with CDEMO-USER-TYPE 'A', CDEMO-PGM-CONTEXT 0. USER0001: XCTL COMEN01C with CDEMO-USER-TYPE 'U', CDEMO-FROM-PROGRAM 'COSGN00C', CDEMO-FROM-TRANID 'CC00'",
   "fixtures": [],
   "finding": "Any SEC-USR-TYPE other than 'A' (including blank or an unexpected code) is routed to the regular-user menu instead of being rejected. Must be reproduced."
  },
  {
   "id": "BR-ONL-008",
   "title": "Menu entry, PF3 and invalid keys",
   "rule": "COMEN01C with no COMMAREA XCTLs to the sign-on program (CDEMO-TO-PROGRAM defaults to 'COSGN00C' when LOW-VALUES/SPACES). With a COMMAREA it is copied into CARDDEMO-COMMAREA; on first entry (CDEMO-PGM-CONTEXT 0) the context is set to 1 (re-enter) and the menu is sent. On re-entry ENTER processes the option, PF3 XCTLs to COSGN00C, any other key re-sends the menu with 'Invalid key pressed. Please see below...'. The task ends with RETURN TRANSID('CM00') COMMAREA(CARDDEMO-COMMAREA).",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/COMEN01C.cbl",
     "startLine": 82,
     "endLine": 110
    },
    {
     "file": "app/cbl/COMEN01C.cbl",
     "startLine": 196,
     "endLine": 203
    }
   ],
   "fields": [
    "EIBCALEN",
    "EIBAID",
    "CDEMO-PGM-CONTEXT",
    "CDEMO-TO-PROGRAM",
    "CDEMO-FROM-PROGRAM"
   ],
   "given": "A regular user arrives from COSGN00C with CDEMO-PGM-CONTEXT 0",
   "when": "The menu is shown, then the user presses PF3",
   "then": "First pass: menu sent and context becomes 1 (CDEMO-PGM-REENTER). PF3: XCTL to COSGN00C. With EIBCALEN = 0 the program XCTLs straight to COSGN00C",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-009",
   "title": "Menu options table and display",
   "rule": "The regular-user menu is driven by CARDDEMO-MAIN-MENU-OPTIONS: CDEMO-MENU-OPT-COUNT = 11 and entries (number, 35-char name, program, user type): 1 Account View COACTVWC, 2 Account Update COACTUPC, 3 Credit Card List COCRDLIC, 4 Credit Card View COCRDSLC, 5 Credit Card Update COCRDUPC, 6 Transaction List COTRN00C, 7 Transaction View COTRN01C, 8 Transaction Add COTRN02C, 9 Transaction Reports CORPT00C, 10 Bill Payment COBIL00C, 11 Pending Authorization View COPAUS0C; all with user type 'U'. Each screen line is STRING number '. ' name into a 40-char field, OPTN001..OPTN012.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cpy/COMEN02Y.cpy",
     "startLine": 19,
     "endLine": 98
    },
    {
     "file": "app/cbl/COMEN01C.cbl",
     "startLine": 208,
     "endLine": 220
    },
    {
     "file": "app/cbl/COMEN01C.cbl",
     "startLine": 262,
     "endLine": 303
    }
   ],
   "fields": [
    "CDEMO-MENU-OPT-COUNT",
    "CDEMO-MENU-OPT-NUM",
    "CDEMO-MENU-OPT-NAME",
    "CDEMO-MENU-OPT-PGMNAME",
    "CDEMO-MENU-OPT-USRTYPE",
    "OPTN001O"
   ],
   "given": "The menu is sent",
   "when": "BUILD-MENU-OPTIONS runs",
   "then": "OPTN001O = '01. Account View' (padded to 40), OPTN011O = '11. Pending Authorization View', OPTN012O stays LOW-VALUES",
   "fixtures": [],
   "finding": "Item 8 is labelled 'Transaction Add' with user type 'U'; the original '(Admin Only)' label is commented out, so regular users can reach COTRN02C. Must be reproduced."
  },
  {
   "id": "BR-ONL-010",
   "title": "Menu option parsing",
   "rule": "The 2-character OPTION input is scanned right-to-left for the last non-space character, that prefix is moved into a right-justified PIC X(2) (WS-OPTION-X JUST RIGHT), spaces are replaced by '0', and the result moved to PIC 9(2) WS-OPTION, which is echoed back to the OPTION field.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cbl/COMEN01C.cbl",
     "startLine": 45,
     "endLine": 46
    },
    {
     "file": "app/cbl/COMEN01C.cbl",
     "startLine": 117,
     "endLine": 125
    },
    {
     "file": "app/bms/COMEN01.bms",
     "startLine": 145,
     "endLine": 148
    },
    {
     "file": "app/cpy-bms/COMEN01.CPY",
     "startLine": 132,
     "endLine": 132
    }
   ],
   "fields": [
    "OPTIONI",
    "WS-OPTION-X",
    "WS-OPTION",
    "OPTIONO"
   ],
   "given": "Menu displayed",
   "when": "The user types '1 ' (1 then space), ' 1', or '10' and presses ENTER",
   "then": "'1 ' and ' 1' both become WS-OPTION 01; '10' stays 10",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-011",
   "title": "Menu invalid option number",
   "rule": "If WS-OPTION is not numeric, is zero, or is greater than CDEMO-MENU-OPT-COUNT (11), the menu is re-sent with 'Please enter a valid option number...' and no XCTL happens.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COMEN01C.cbl",
     "startLine": 127,
     "endLine": 134
    },
    {
     "file": "app/cpy/COMEN02Y.cpy",
     "startLine": 21,
     "endLine": 21
    }
   ],
   "fields": [
    "WS-OPTION",
    "CDEMO-MENU-OPT-COUNT",
    "WS-ERR-FLG"
   ],
   "given": "Menu displayed for a regular user",
   "when": "The user enters '00', '12' or blank and presses ENTER",
   "then": "ERRMSG 'Please enter a valid option number...', no program transfer",
   "fixtures": [],
   "finding": "After this error the code does not stop: it goes on to the admin check, which reads CDEMO-MENU-OPT-USRTYPE(WS-OPTION) with an out-of-range subscript (0 or 12-99), outside the 11 loaded entries. No transfer happens because the error flag is set, but a port must not raise an index error and must still show only this message. Looks wrong; reproduce the visible outcome."
  },
  {
   "id": "BR-ONL-012",
   "title": "Menu admin-only option authorization",
   "rule": "If the signed-on user is a regular user (CDEMO-USRTYP-USER, 'U') and the selected entry has CDEMO-MENU-OPT-USRTYPE = 'A', the menu is re-sent with 'No access - Admin Only option... ' and no XCTL. In the shipped table every entry is 'U', so this check never fires today.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COMEN01C.cbl",
     "startLine": 136,
     "endLine": 143
    },
    {
     "file": "app/cpy/COMEN02Y.cpy",
     "startLine": 25,
     "endLine": 90
    }
   ],
   "fields": [
    "CDEMO-USER-TYPE",
    "CDEMO-MENU-OPT-USRTYPE",
    "WS-MESSAGE"
   ],
   "given": "A table entry 3 with user type 'A' (configuration variant) and a regular user 'U'",
   "when": "The user selects option 3",
   "then": "ERRMSG 'No access - Admin Only option... ', no XCTL; with the shipped table (all 'U') the same selection XCTLs to COCRDLIC",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-013",
   "title": "Menu not-installed and coming-soon options",
   "rule": "For program COPAUS0C the menu first issues EXEC CICS INQUIRE PROGRAM NOHANDLE; if EIBRESP is not NORMAL it shows in red 'This option ' + the option name up to the first double space + ' is not installed...'. For any program whose name starts with 'DUMMY' it shows in green 'This option ' + the name up to the first single space + 'is coming soon ...'. In both cases the menu is re-sent and no XCTL occurs.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/COMEN01C.cbl",
     "startLine": 145,
     "endLine": 176
    },
    {
     "file": "app/cbl/COMEN01C.cbl",
     "startLine": 190,
     "endLine": 191
    }
   ],
   "fields": [
    "CDEMO-MENU-OPT-PGMNAME",
    "CDEMO-MENU-OPT-NAME",
    "EIBRESP",
    "ERRMSGC",
    "WS-MESSAGE"
   ],
   "given": "COPAUS0C is not defined in the CICS region; a hypothetical entry 'Card Rewards' points to 'DUMMY001'",
   "when": "The user selects option 11, then the DUMMY option",
   "then": "Option 11: red ERRMSG 'This option Pending Authorization View is not installed...'. DUMMY: green ERRMSG 'This option Cardis coming soon ...'",
   "fixtures": [],
   "finding": "The coming-soon text uses DELIMITED BY SPACE, so only the first word of the option name is kept and there is no space before 'is'. Looks wrong but must be reproduced."
  },
  {
   "id": "BR-ONL-014",
   "title": "Menu transfer to the selected program",
   "rule": "For any other valid option the menu sets CDEMO-FROM-TRANID = 'CM00', CDEMO-FROM-PROGRAM = 'COMEN01C', CDEMO-PGM-CONTEXT = 0 and XCTLs to CDEMO-MENU-OPT-PGMNAME(option) passing CARDDEMO-COMMAREA. User id and user type are passed through unchanged.",
   "category": "state-change",
   "sources": [
    {
     "file": "app/cbl/COMEN01C.cbl",
     "startLine": 145,
     "endLine": 146
    },
    {
     "file": "app/cbl/COMEN01C.cbl",
     "startLine": 177,
     "endLine": 191
    }
   ],
   "fields": [
    "CDEMO-FROM-TRANID",
    "CDEMO-FROM-PROGRAM",
    "CDEMO-PGM-CONTEXT",
    "CDEMO-MENU-OPT-PGMNAME"
   ],
   "given": "Regular user USER0001 on the menu",
   "when": "The user enters option 1 and presses ENTER",
   "then": "XCTL PROGRAM('COACTVWC') with CDEMO-FROM-PROGRAM 'COMEN01C', CDEMO-FROM-TRANID 'CM00', CDEMO-PGM-CONTEXT 0, CDEMO-USER-ID 'USER0001'",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-015",
   "title": "PF-key normalisation and valid keys on Account View",
   "rule": "YYYY-STORE-PFKEY maps EIBAID to CCARD-AID-*: ENTER, CLEAR, PA1, PA2, PF1-PF12, and PF13-PF24 fold onto PF1-PF12. In COACTVWC only ENTER and PF3 are valid; any other key is silently treated as ENTER.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cpy/CSSTRPFY.cpy",
     "startLine": 17,
     "endLine": 80
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 299,
     "endLine": 314
    }
   ],
   "fields": [
    "EIBAID",
    "CCARD-AID",
    "PFK-VALID",
    "PFK-INVALID"
   ],
   "given": "Account View screen displayed with account 00000000001 entered",
   "when": "The user presses PF15; in another case PF8",
   "then": "PF15 acts as PF3 (exit). PF8 is invalid and is processed exactly like ENTER (the account is looked up and shown again)",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-016",
   "title": "PF3 exit from Account View / Account Update",
   "rule": "PF3 transfers control back: CDEMO-TO-TRANID = CDEMO-FROM-TRANID, or 'CM00' if blank/LOW-VALUES; CDEMO-TO-PROGRAM = CDEMO-FROM-PROGRAM, or 'COMEN01C' if blank. CDEMO-FROM-* are set to this program (COACTVWC/CAVW or COACTUPC/CAUP), CDEMO-USER-TYPE is forced to 'U', CDEMO-PGM-CONTEXT to 0 and CDEMO-LAST-MAP/MAPSET to this screen, then XCTL with the COMMAREA. COACTUPC issues SYNCPOINT before the XCTL.",
   "category": "state-change",
   "sources": [
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 324,
     "endLine": 352
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 927,
     "endLine": 959
    }
   ],
   "fields": [
    "CDEMO-FROM-TRANID",
    "CDEMO-FROM-PROGRAM",
    "CDEMO-TO-TRANID",
    "CDEMO-TO-PROGRAM",
    "CDEMO-USER-TYPE",
    "CDEMO-PGM-CONTEXT",
    "CDEMO-LAST-MAP",
    "CDEMO-LAST-MAPSET"
   ],
   "given": "COMMAREA with CDEMO-FROM-PROGRAM 'COMEN01C', CDEMO-FROM-TRANID 'CM00', CDEMO-USER-TYPE 'A'",
   "when": "The user presses PF3 on Account View",
   "then": "XCTL to COMEN01C with CDEMO-TO-TRANID 'CM00', CDEMO-FROM-PROGRAM 'COACTVWC', CDEMO-FROM-TRANID 'CAVW', CDEMO-USER-TYPE 'U', CDEMO-PGM-CONTEXT 0, CDEMO-LAST-MAPSET 'COACTVW', CDEMO-LAST-MAP 'CACTVWA'",
   "fixtures": [],
   "finding": "PF3 always sets CDEMO-USRTYP-USER, so an administrator who returns through these screens loses the 'A' user type in the COMMAREA. Looks wrong; must be reproduced."
  },
  {
   "id": "BR-ONL-017",
   "title": "Account View blank account id",
   "rule": "On re-entry the account id input '*' or SPACES is treated as not supplied (CC-ACCT-ID = LOW-VALUES). The edit sets INPUT-ERROR and FLG-ACCTFILTER-BLANK and CDEMO-ACCT-ID = 0. The message 'Account number not provided' is then overwritten by 'No input received', which is what the user sees. The screen is re-sent with '*' in red in the account field and the cursor there.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 121,
     "endLine": 124
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 361,
     "endLine": 367
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 557,
     "endLine": 565
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 622,
     "endLine": 662
    }
   ],
   "fields": [
    "ACCTSIDI",
    "CC-ACCT-ID",
    "CDEMO-ACCT-ID",
    "WS-RETURN-MSG",
    "FLG-ACCTFILTER-BLANK"
   ],
   "given": "Account View re-entered (CDEMO-PGM-REENTER)",
   "when": "The user leaves the account field blank and presses ENTER",
   "then": "ERRMSG 'No input received', account field shows '*' in red, no file is read",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-018",
   "title": "Account View non-numeric or zero account id",
   "rule": "A supplied account id that is not numeric or equals zero sets INPUT-ERROR and FLG-ACCTFILTER-NOT-OK, CDEMO-ACCT-ID = 0, and the message 'Account Filter must  be a non-zero 11 digit number' (two spaces after 'must'); the field is shown in red. A valid id is moved to CDEMO-ACCT-ID.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 557,
     "endLine": 559
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 664,
     "endLine": 680
    },
    {
     "file": "app/bms/COACTVW.bms",
     "startLine": 84,
     "endLine": 87
    },
    {
     "file": "app/cpy-bms/COACTVW.CPY",
     "startLine": 60,
     "endLine": 60
    }
   ],
   "fields": [
    "ACCTSIDI",
    "CC-ACCT-ID",
    "CDEMO-ACCT-ID",
    "ACCTSIDC"
   ],
   "given": "Account View re-entered",
   "when": "The user enters '00000000000' (or 'ABC')",
   "then": "ERRMSG 'Account Filter must  be a non-zero 11 digit number', account field red, no file read",
   "fixtures": [],
   "finding": "The literal has a double space ('must  be') and differs from the unused 88-level text 'Account number must be a non zero 11 digit number'. Must be reproduced character for character."
  },
  {
   "id": "BR-ONL-019",
   "title": "Account lookup chain: card xref -> account -> customer",
   "rule": "9000-READ-ACCT reads, in order: the card cross-reference by account through the alternate index path CXACAIX (key = 11-char account id) giving XREF-CUST-ID -> CDEMO-CUST-ID and XREF-CARD-NUM -> CDEMO-CARD-NUM; the account master ACCTDAT by the same account id; and the customer master CUSTDAT by the 9-digit customer id taken from the xref. Only an xref failure stops the chain. COACTUPC uses the same chain and then stores the fetched values (9500-STORE-FETCHED-DATA).",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 687,
     "endLine": 740
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 774,
     "endLine": 788
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 825,
     "endLine": 838
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 3608,
     "endLine": 3645
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 3801,
     "endLine": 3885
    },
    {
     "file": "app/cpy/CVACT03Y.cpy",
     "startLine": 4,
     "endLine": 8
    }
   ],
   "fields": [
    "WS-CARD-RID-ACCT-ID-X",
    "WS-CARD-RID-CUST-ID-X",
    "XREF-CUST-ID",
    "XREF-CARD-NUM",
    "CDEMO-CUST-ID",
    "CDEMO-CARD-NUM",
    "ACCOUNT-RECORD",
    "CUSTOMER-RECORD"
   ],
   "given": "CXACAIX maps account 00000000001 to customer 000000001 and card 4111111111111111; ACCTDAT and CUSTDAT hold both records",
   "when": "The user views account 00000000001",
   "then": "Three READs in order CXACAIX, ACCTDAT, CUSTDAT; CDEMO-CUST-ID = 000000001, CDEMO-CARD-NUM = 4111111111111111; screen shows account and customer details",
   "fixtures": [],
   "finding": "The 'not found' checks after the account and customer reads test 88-levels (DID-NOT-FIND-ACCT-IN-ACCTDAT / -CUST-IN-CUSTDAT) whose SET statements are commented out, so they are never true. A missing account still triggers the customer read (and in COACTUPC the store of fetched data). Must be reproduced."
  },
  {
   "id": "BR-ONL-020",
   "title": "Account lookup NOTFND messages (truncated to 75 chars)",
   "rule": "RESP NOTFND on a read sets INPUT-ERROR and, only if no message is set yet, STRINGs (DELIMITED BY SIZE) into the 75-byte WS-RETURN-MSG: xref 'Account:' + id + ' not found in' + ' Cross ref file.  Resp:' + ERROR-RESP + ' Reas:' + ERROR-RESP2; account 'Account:' + id + ' not found in' + ' Acct Master file.Resp:' + ...; customer 'CustId:' + cust id + ' not found' + ' in customer master.Resp: ' + ERROR-RESP + ' REAS:' + ERROR-RESP2. ERROR-RESP/-RESP2 are X(10) copies of the binary RESP codes (9 digits plus a space). The text is cut at 75 characters.",
   "category": "sysout",
   "sources": [
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 86,
     "endLine": 105
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 117,
     "endLine": 117
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 741,
     "endLine": 758
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 789,
     "endLine": 807
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 839,
     "endLine": 857
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 3668,
     "endLine": 3685
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 3716,
     "endLine": 3734
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 3766,
     "endLine": 3784
    }
   ],
   "fields": [
    "WS-RETURN-MSG",
    "ERROR-RESP",
    "ERROR-RESP2",
    "WS-CARD-RID-ACCT-ID-X",
    "WS-CARD-RID-CUST-ID-X"
   ],
   "given": "Account 00000000099 is not in CXACAIX (RESP 13, RESP2 below 100)",
   "when": "The user views account 00000000099",
   "then": "ERRMSG 'Account:00000000099 not found in Cross ref file.  Resp:000000013  Reas:0000' (exactly 75 characters); for a missing customer 000000009: 'CustId:000000009 not found in customer master.Resp: 000000013  REAS:0000000'",
   "fixtures": [],
   "finding": "The STRING overflows WS-RETURN-MSG X(75), so the RESP2 value is cut to its first 4 (xref/account) or 7 (customer) characters. Looks wrong; must be reproduced."
  },
  {
   "id": "BR-ONL-021",
   "title": "Account lookup unexpected file errors",
   "rule": "Any RESP other than NORMAL or NOTFND sets INPUT-ERROR and overwrites WS-RETURN-MSG (no 'first message wins' guard) with WS-FILE-ERROR-MESSAGE: 'File Error: ' + 'READ' (X(8)) + ' on ' + file name (X(9): CXACAIX / ACCTDAT / CUSTDAT) + ' returned RESP ' + RESP (X(10)) + ',RESP2 ' + RESP2 (X(10)).",
   "category": "return-code",
   "sources": [
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 86,
     "endLine": 105
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 759,
     "endLine": 767
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 809,
     "endLine": 817
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 858,
     "endLine": 866
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 3686,
     "endLine": 3694
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 3736,
     "endLine": 3744
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 3785,
     "endLine": 3793
    }
   ],
   "fields": [
    "WS-FILE-ERROR-MESSAGE",
    "ERROR-OPNAME",
    "ERROR-FILE",
    "ERROR-RESP",
    "ERROR-RESP2"
   ],
   "given": "ACCTDAT is closed (READ returns RESP 19 NOTOPEN, RESP2 below 100)",
   "when": "The user views account 00000000001",
   "then": "ERRMSG 'File Error: READ     on ACCTDAT   returned RESP 000000019 ,RESP2 ' followed by the 9-digit RESP2 and a space (75 characters; the 5-byte trailing filler is cut)",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-022",
   "title": "Account View display mapping",
   "rule": "When the account or the customer was found, the account fields are moved to the screen (status, current balance, credit limit, cash credit limit, cycle credit, cycle debit, open/expiry/reissue dates, group id). When the customer was found, customer fields are moved, with the SSN formatted as 999-99-9999 (CUST-SSN(1:3) '-' (4:2) '-' (6:4)). The info line always shows 'Enter or update id of account to display'.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 460,
     "endLine": 534
    },
    {
     "file": "app/cpy/CVACT01Y.cpy",
     "startLine": 4,
     "endLine": 17
    },
    {
     "file": "app/cpy/CVCUS01Y.cpy",
     "startLine": 4,
     "endLine": 23
    }
   ],
   "fields": [
    "ACCT-ACTIVE-STATUS",
    "ACCT-CURR-BAL",
    "ACCT-CREDIT-LIMIT",
    "ACCT-CASH-CREDIT-LIMIT",
    "ACCT-CURR-CYC-CREDIT",
    "ACCT-CURR-CYC-DEBIT",
    "ACCT-OPEN-DATE",
    "ACCT-GROUP-ID",
    "CUST-SSN",
    "CUST-FICO-CREDIT-SCORE",
    "ACSTSSNO"
   ],
   "given": "Account 00000000001 with customer CUST-SSN 123456789",
   "when": "The account is displayed",
   "then": "ACSTSSNO = '123-45-6789'; account and customer fields filled; INFOMSG 'Enter or update id of account to display'",
   "fixtures": [],
   "finding": "If the account is missing but xref and customer exist, FOUND-CUST-IN-MASTER is still true, so the account fields are filled from an ACCOUNT-RECORD that was never read, next to the 'not found in Acct Master file' error. Looks wrong; must be reproduced."
  },
  {
   "id": "BR-ONL-023",
   "title": "Account Update state machine and info messages",
   "rule": "COACTUPC keeps its state in ACUP-CHANGE-ACTION inside its part of the COMMAREA: LOW-VALUES/SPACES = details not fetched, 'S' = show details, 'E' = changes not OK, 'N' = changes OK not confirmed, 'C' = changes done, 'L' = lock error, 'F' = update failed. The info line follows the state: search keys 'Enter or update id of account to update', S/E 'Update account details presented above.', N 'Changes validated.Press F5 to save', C 'Changes committed to database', L/F 'Changes unsuccessful. Please try again'.",
   "category": "state-change",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 464,
     "endLine": 477
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 655,
     "endLine": 668
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2955,
     "endLine": 2982
    }
   ],
   "fields": [
    "ACUP-CHANGE-ACTION",
    "WS-INFO-MSG",
    "INFOMSGO"
   ],
   "given": "Account Update with ACUP-CHANGE-ACTION = 'N'",
   "when": "The screen is sent",
   "then": "INFOMSG 'Changes validated.Press F5 to save'; with 'C' it reads 'Changes committed to database'",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-024",
   "title": "Account Update valid keys",
   "rule": "Valid AIDs are ENTER, PF3, PF5 only when ACUP-CHANGES-OK-NOT-CONFIRMED ('N'), and PF12 only when details have been fetched. Any other key, including PF5 before validation, is treated as ENTER.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 898,
     "endLine": 916
    },
    {
     "file": "app/cpy/CSSTRPFY.cpy",
     "startLine": 17,
     "endLine": 80
    }
   ],
   "fields": [
    "CCARD-AID",
    "ACUP-CHANGE-ACTION",
    "PFK-VALID"
   ],
   "given": "Account details shown (state 'S') with an edited credit limit",
   "when": "The user presses PF5 before pressing ENTER",
   "then": "PF5 is not valid in state 'S'; it is processed as ENTER, the edits run and the state becomes 'N' (no save happens)",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-025",
   "title": "Account Update search and fetch",
   "rule": "When no details are fetched, only the account id is edited: blank/'*' gives INPUT-ERROR and finally 'No input received' (overwriting 'Account number not provided'); not numeric or zero gives 'Account Number if supplied must be a 11 digit Non-Zero Number'. A valid id runs 9000-READ-ACCT; if the customer was found, the state becomes 'S' and original values are shown. First entry from the menu (or EIBCALEN = 0) only sends the empty search screen.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 880,
     "endLine": 893
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 964,
     "endLine": 973
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1429,
     "endLine": 1449
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1783,
     "endLine": 1817
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2568,
     "endLine": 2580
    }
   ],
   "fields": [
    "CC-ACCT-ID",
    "CC-ACCT-ID-N",
    "ACUP-NEW-ACCT-ID",
    "CDEMO-ACCT-ID",
    "WS-RETURN-MSG",
    "ACUP-CHANGE-ACTION"
   ],
   "given": "Account Update search screen (details not fetched)",
   "when": "The user enters 'ABCDEFGHIJK'; then '00000000001'",
   "then": "First: ERRMSG 'Account Number if supplied must be a 11 digit Non-Zero Number', no read. Second: xref/account/customer read, state 'S', INFOMSG 'Update account details presented above.'",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-026",
   "title": "Account Update input mapping and amount conversion",
   "rule": "On receive, any field equal to '*' or SPACES becomes LOW-VALUES (not supplied). Amount fields (credit limit, cash credit limit, current balance, cycle credit, cycle debit) are copied as text (X(15)) and, when FUNCTION TEST-NUMVAL-C returns 0, converted with FUNCTION NUMVAL-C into PIC S9(10)V99 by COMPUTE without ROUNDED, so extra decimals are truncated. Currency sign, commas and a trailing CR/DB or '-' are accepted.",
   "category": "rounding",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 411,
     "endLine": 416
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 757,
     "endLine": 770
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1047,
     "endLine": 1140
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1401,
     "endLine": 1424
    }
   ],
   "fields": [
    "ACRDLIMI",
    "ACUP-NEW-CREDIT-LIMIT-X",
    "ACUP-NEW-CREDIT-LIMIT-N",
    "ACUP-NEW-CASH-CREDIT-LIMIT-N",
    "ACUP-NEW-CURR-BAL-N",
    "ACUP-NEW-CURR-CYC-CREDIT-N",
    "ACUP-NEW-CURR-CYC-DEBIT-N"
   ],
   "given": "Account details shown",
   "when": "The user types credit limit '$1,234.567' and current balance '250.00CR'",
   "then": "ACUP-NEW-CREDIT-LIMIT-N = 1234.56 (third decimal truncated, not rounded); ACUP-NEW-CURR-BAL-N = -250.00; an input of '*' in any field counts as blank",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-027",
   "title": "Account Update change detection",
   "rule": "1205-COMPARE-OLD-NEW compares new and fetched values: account fields exactly (numbers/dates), status and group id case-insensitively (group id trimmed); customer text fields trimmed and case-insensitive, phone parts, SSN, DOB, EFT id and FICO exactly. If nothing differs, WS-RETURN-MSG = 'No change detected with respect to values fetched.', the field edits are skipped and the state stays 'S'.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 491,
     "endLine": 492
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1460,
     "endLine": 1468
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1681,
     "endLine": 1776
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2585,
     "endLine": 2591
    }
   ],
   "fields": [
    "ACUP-NEW-DETAILS",
    "ACUP-OLD-DETAILS",
    "NO-CHANGES-FOUND",
    "CHANGE-HAS-OCCURRED",
    "WS-NON-KEY-FLAGS"
   ],
   "given": "Account details shown with first name 'JOHN'",
   "when": "The user changes the first name to 'john ' (lower case, trailing space) and presses ENTER",
   "then": "No change is detected: ERRMSG 'No change detected with respect to values fetched.', no field edits, state stays 'S'",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-028",
   "title": "Account Update edit ladder (order and first-error-wins)",
   "rule": "When a change is detected the state is set to 'E' and all fields are edited in this fixed order: Account Status, Open Date, Credit Limit, Expiry Date, Cash Credit Limit, Reissue Date, Current Balance, Current Cycle Credit, Current Cycle Debit, SSN, Date of Birth, FICO Score, First Name, Middle Name, Last Name, Address Line 1, State, Zip, City, Country, Phone Number 1, Phone Number 2, EFT Account Id, Primary Card Holder, then the state/zip combination. Every failing field is flagged (and highlighted), but only the first error message is kept because each edit writes WS-RETURN-MSG only while it is still empty (WS-RETURN-MSG-OFF).",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1470,
     "endLine": 1675
    }
   ],
   "fields": [
    "WS-EDIT-VARIABLE-NAME",
    "WS-RETURN-MSG",
    "INPUT-ERROR",
    "ACUP-CHANGE-ACTION"
   ],
   "given": "Account details shown",
   "when": "The user blanks the Credit Limit and also types 'X' as Account Status",
   "then": "Both fields are flagged; ERRMSG is 'Account Status must be Y or N.' (the status edit runs first); state 'E'",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-029",
   "title": "Account Update Y/N fields (Account Status, Primary Card Holder)",
   "rule": "Account Status and Primary Card Holder go through 1220-EDIT-YESNO: LOW-VALUES, SPACES or ZEROS give '<name> must be supplied.'; a value other than 'Y' or 'N' gives '<name> must be Y or N.'. The check is case-sensitive.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 76,
     "endLine": 80
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1472,
     "endLine": 1476
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1657,
     "endLine": 1662
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1856,
     "endLine": 1893
    }
   ],
   "fields": [
    "ACUP-NEW-ACTIVE-STATUS",
    "ACUP-NEW-CUST-PRI-HOLDER-IND",
    "WS-EDIT-YES-NO"
   ],
   "given": "Account details shown with status 'Y'",
   "when": "The user changes Account Status to 'y' and the credit limit from 5000.00 to 6000.00",
   "then": "ERRMSG 'Account Status must be Y or N.'; changing only 'Y' to 'y' is not even edited, because the change check upper-cases status and reports 'No change detected with respect to values fetched.'",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-030",
   "title": "Account Update signed amount fields",
   "rule": "Credit Limit, Cash Credit Limit, Current Balance, Current Cycle Credit and Current Cycle Debit go through 1250-EDIT-SIGNED-9V2: blank gives '<name> must be supplied.'; TEST-NUMVAL-C not 0 gives '<name> is not valid' (no full stop). Negative values are allowed.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1484,
     "endLine": 1527
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2180,
     "endLine": 2223
    }
   ],
   "fields": [
    "ACUP-NEW-CREDIT-LIMIT-X",
    "ACUP-NEW-CURR-CYC-CREDIT-X",
    "ACUP-NEW-CURR-CYC-DEBIT-X",
    "WS-EDIT-SIGNED-NUMBER-9V2-X"
   ],
   "given": "Account details shown",
   "when": "The user enters Current Cycle Debit '12A.00'",
   "then": "ERRMSG 'Current Cycle Debit Limit is not valid'",
   "fixtures": [],
   "finding": "The cycle credit/debit amounts are labelled 'Current Cycle Credit Limit' / 'Current Cycle Debit Limit' in messages although they are not limits. Must be reproduced."
  },
  {
   "id": "BR-ONL-031",
   "title": "Account Update date fields (Open, Expiry, Reissue, DOB)",
   "rule": "Dates are edited by EDIT-DATE-CCYYMMDD (CSUTLDPY): year required and 4-digit numeric (' : Year must be supplied.', ' must be 4 digit number.'), century only 19 or 20 (' : Century is not valid.'), month required 1-12 (': Month must be a number between 1 and 12.'), day required 1-31 (':day must be a number between 1 and 31.'), day 31 in a 30-day month (':Cannot have 31 days in this month.'), 30 February (':Cannot have 30 days in this month.'), 29 February in a non-leap year (divide by 400 when YY = 00, else by 4: ':Not a leap year.Cannot have 29 days in this month.'), then a final check through CSUTLDTC (LE CEEDAYS) (' validation error Sev code: ... Message code: ...'). Messages are prefixed with the field name.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1478,
     "endLine": 1507
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1533,
     "endLine": 1538
    },
    {
     "file": "app/cpy/CSUTLDPY.cpy",
     "startLine": 18,
     "endLine": 329
    },
    {
     "file": "app/cpy/CSUTLDWY.cpy",
     "startLine": 9,
     "endLine": 24
    }
   ],
   "fields": [
    "ACUP-NEW-OPEN-DATE",
    "ACUP-NEW-EXPIRAION-DATE",
    "ACUP-NEW-REISSUE-DATE",
    "ACUP-NEW-CUST-DOB-YYYY-MM-DD",
    "WS-EDIT-DATE-CCYYMMDD"
   ],
   "given": "Account details shown",
   "when": "The user sets Expiry Date to 2023-02-29; in another case Open Date year 2100",
   "then": "ERRMSG 'Expiry Date:Not a leap year.Cannot have 29 days in this month.'; year 2100 gives 'Open Date : Century is not valid.'; 1900-02-29 is rejected (YY = 00, 1900 / 400 has a remainder), 2000-02-29 is accepted",
   "fixtures": [],
   "finding": "Only centuries 19 and 20 are accepted (the copybook says so explicitly), so any date from 2100 on is rejected. Must be reproduced."
  },
  {
   "id": "BR-ONL-032",
   "title": "Account Update date of birth must be in the past",
   "rule": "If the DOB passes the date edit, EDIT-DATE-OF-BIRTH compares FUNCTION INTEGER-OF-DATE of the DOB with today; unless today is strictly later, it gives 'Date of Birth:cannot be in the future ' (with a trailing space).",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1533,
     "endLine": 1543
    },
    {
     "file": "app/cpy/CSUTLDPY.cpy",
     "startLine": 341,
     "endLine": 370
    }
   ],
   "fields": [
    "ACUP-NEW-CUST-DOB-YYYY-MM-DD",
    "WS-EDIT-DATE-BINARY",
    "WS-CURRENT-DATE-BINARY"
   ],
   "given": "Clock 2022-07-05",
   "when": "The user enters DOB 2022-07-05 (today); in a second case 2022-07-04",
   "then": "Today: ERRMSG 'Date of Birth:cannot be in the future '; yesterday: accepted",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-033",
   "title": "Account Update numeric-required fields (FICO, Zip, EFT)",
   "rule": "FICO Score (3), Zip (5) and EFT Account Id (10) go through 1245-EDIT-NUM-REQD: blank '<name> must be supplied.', not all digits '<name> must be all numeric.', value 0 '<name> must not be zero.'. FICO must then be 300-850 ('FICO Score: should be between 300 and 850').",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 846,
     "endLine": 849
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1545,
     "endLine": 1556
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1605,
     "endLine": 1611
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1648,
     "endLine": 1655
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2109,
     "endLine": 2174
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2514,
     "endLine": 2523
    }
   ],
   "fields": [
    "ACUP-NEW-CUST-FICO-SCORE-X",
    "ACUP-NEW-CUST-ADDR-ZIP",
    "ACUP-NEW-CUST-EFT-ACCOUNT-ID",
    "FICO-RANGE-IS-VALID"
   ],
   "given": "Account details shown",
   "when": "The user sets FICO to 299; in a second case Zip to '1234A'",
   "then": "FICO: ERRMSG 'FICO Score: should be between 300 and 850'; Zip: 'Zip must be all numeric.'",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-034",
   "title": "Account Update name, address, city and country text edits",
   "rule": "First Name, Last Name (25), State (2), City (50) and Country (3) are required alphabetic: blank '<name> must be supplied.', anything other than A-Z, a-z and space '<name> can have alphabets only.'. Middle Name is optional but alphabetic if given. Address Line 1 (50) is mandatory ('Address Line 1 must be supplied.') with any characters. Address Line 2 and Government Issued Id are not edited.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1560,
     "endLine": 1597
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1615,
     "endLine": 1630
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1824,
     "endLine": 1850
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1898,
     "endLine": 1949
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2012,
     "endLine": 2055
    }
   ],
   "fields": [
    "ACUP-NEW-CUST-FIRST-NAME",
    "ACUP-NEW-CUST-MIDDLE-NAME",
    "ACUP-NEW-CUST-LAST-NAME",
    "ACUP-NEW-CUST-ADDR-LINE-1",
    "ACUP-NEW-CUST-ADDR-LINE-3",
    "ACUP-NEW-CUST-ADDR-COUNTRY-CD",
    "ACUP-NEW-CUST-GOVT-ISSUED-ID"
   ],
   "given": "Account details shown",
   "when": "The user sets Last Name to 'O'Brien' or City to 'St. Louis'",
   "then": "ERRMSG 'Last Name can have alphabets only.' / 'City can have alphabets only.'; a blank middle name is accepted",
   "fixtures": [],
   "finding": "Apostrophes, hyphens and full stops are rejected in names and city, and the City edit reads ADDR-LINE-3. Address Line 2 and Government Id get no validation at all. Must be reproduced."
  },
  {
   "id": "BR-ONL-035",
   "title": "Account Update state code and state/zip combination",
   "rule": "A valid alphabetic State must be in the US state table VALID-US-STATE-CODE ('State: is not a valid state code'). When State and Zip are both valid, state + first two zip digits must be in VALID-US-STATE-ZIP-CD2-COMBO, else 'Invalid zip code for state' (both fields flagged).",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1592,
     "endLine": 1602
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1665,
     "endLine": 1669
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2493,
     "endLine": 2513
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2536,
     "endLine": 2560
    },
    {
     "file": "app/cpy/CSLKPCDY.cpy",
     "startLine": 1013,
     "endLine": 1013
    },
    {
     "file": "app/cpy/CSLKPCDY.cpy",
     "startLine": 1073,
     "endLine": 1073
    }
   ],
   "fields": [
    "ACUP-NEW-CUST-ADDR-STATE-CD",
    "ACUP-NEW-CUST-ADDR-ZIP",
    "US-STATE-AND-FIRST-ZIP2"
   ],
   "given": "Account details shown",
   "when": "The user sets State 'ZZ'; in a second case State 'NY' with Zip '90210'",
   "then": "'ZZ': ERRMSG 'State: is not a valid state code'; NY + 90: 'Invalid zip code for state'",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-036",
   "title": "Account Update US phone number edit",
   "rule": "Phone Number 1 and 2 are optional: when area code, prefix and line number are all blank the number is accepted. Otherwise area code must be supplied, 3 digits, non-zero and in the North American general-purpose area code list; prefix 3 digits non-zero; line number 4 digits non-zero. Messages are '<name>: Area code must be supplied.', ': Area code must be A 3 digit number.', ': Area code cannot be zero', ': Not valid North America general purpose area code', and the same for 'Prefix code' and 'Line number code'.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1632,
     "endLine": 1646
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2225,
     "endLine": 2430
    },
    {
     "file": "app/cpy/CSLKPCDY.cpy",
     "startLine": 521,
     "endLine": 521
    }
   ],
   "fields": [
    "ACUP-NEW-CUST-PHONE-NUM-1A",
    "ACUP-NEW-CUST-PHONE-NUM-1B",
    "ACUP-NEW-CUST-PHONE-NUM-1C",
    "WS-EDIT-US-PHONE-NUM",
    "VALID-GENERAL-PURP-CODE"
   ],
   "given": "Account details shown",
   "when": "The user enters Phone Number 1 as area '000', prefix '555', line '0100'",
   "then": "ERRMSG 'Phone Number 1: Area code cannot be zero'",
   "fixtures": [],
   "finding": "The 'all blank' test checks area code twice and never checks that the line number is blank (line 2238 tests NUMA instead of NUMC). Area and prefix blank with line '1234' is accepted as 'no phone' and saved as '(   )   -1234'. Must be reproduced."
  },
  {
   "id": "BR-ONL-037",
   "title": "Account Update SSN edit",
   "rule": "The SSN is entered in three parts: part 1 (3 digits) must be numeric, non-zero and not 000, 666 or 900-999 ('SSN: First 3 chars: should not be 000, 666, or between 900 and 999'); part 2 (2 digits) and part 3 (4 digits) must be numeric and non-zero ('SSN 4th & 5th chars must not be zero.', 'SSN Last 4 chars must be all numeric.').",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 119,
     "endLine": 123
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1529,
     "endLine": 1530
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2431,
     "endLine": 2492
    }
   ],
   "fields": [
    "ACUP-NEW-CUST-SSN-1",
    "ACUP-NEW-CUST-SSN-2",
    "ACUP-NEW-CUST-SSN-3",
    "INVALID-SSN-PART1"
   ],
   "given": "Account details shown",
   "when": "The user sets the SSN to 666-12-3456",
   "then": "ERRMSG 'SSN: First 3 chars: should not be 000, 666, or between 900 and 999'",
   "fixtures": [],
   "finding": "Part 1 value 000 is caught first by the non-zero check ('SSN: First 3 chars must not be zero.'), so the 000 case of the range message is unreachable. Must be reproduced."
  },
  {
   "id": "BR-ONL-038",
   "title": "Account Update validated changes await PF5 confirmation",
   "rule": "If no edit failed, the state becomes 'N' (changes OK, not confirmed), the screen shows the new values with the fields protected and the info 'Changes validated.Press F5 to save'. ENTER in state 'N' skips the edits and keeps waiting; only PF5 saves.",
   "category": "state-change",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1463,
     "endLine": 1468
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1671,
     "endLine": 1675
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2585,
     "endLine": 2591
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2620,
     "endLine": 2621
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2966,
     "endLine": 2967
    }
   ],
   "fields": [
    "ACUP-CHANGE-ACTION",
    "INPUT-ERROR",
    "WS-INFO-MSG"
   ],
   "given": "Account details shown; the user changed the credit limit from 5000.00 to 6000.00",
   "when": "The user presses ENTER, then ENTER again",
   "then": "State 'N', INFOMSG 'Changes validated.Press F5 to save'; the second ENTER changes nothing",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-039",
   "title": "PF5 save: READ UPDATE locks on account and customer",
   "rule": "PF5 in state 'N' runs 9600-WRITE-PROCESSING: EXEC CICS READ UPDATE on ACCTDAT by account id; on failure INPUT-ERROR and 'Could not lock account record for update', state 'L' (info 'Changes unsuccessful. Please try again'). Then READ UPDATE on CUSTDAT by CDEMO-CUST-ID; on failure 'Could not lock customer record for update'.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 517,
     "endLine": 520
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2602,
     "endLine": 2615
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 3888,
     "endLine": 3942
    }
   ],
   "fields": [
    "WS-CARD-RID-ACCT-ID-X",
    "WS-CARD-RID-CUST-ID-X",
    "WS-RESP-CD",
    "ACUP-CHANGE-ACTION"
   ],
   "given": "State 'N' and ACCTDAT record 00000000001 held by another task",
   "when": "The user presses PF5",
   "then": "ERRMSG 'Could not lock account record for update', state 'L', INFOMSG 'Changes unsuccessful. Please try again'",
   "fixtures": [],
   "finding": "A customer lock failure is not in the 2000-DECIDE-ACTION list, so it falls to WHEN OTHER and sets 'C': the screen shows 'Changes committed to database' together with 'Could not lock customer record for update', although nothing was written. Looks wrong; must be reproduced."
  },
  {
   "id": "BR-ONL-040",
   "title": "PF5 optimistic concurrency check",
   "rule": "After both locks, 9700-CHECK-CHANGE-IN-REC compares the records just read for update with the snapshot taken when they were displayed: account status, balances, limits, cycle amounts, open/expiry/reissue date parts, group id (lower-cased) and customer names/addresses/state/country/gov id (upper-cased), zip, phones, SSN, DOB parts, EFT id, primary holder, FICO. Any difference gives 'Record changed by some one else. Please review', no rewrite, and the state goes back to 'S'.",
   "category": "validation",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 521,
     "endLine": 522
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2611,
     "endLine": 2612
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 3943,
     "endLine": 3952
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 4109,
     "endLine": 4195
    }
   ],
   "fields": [
    "ACUP-OLD-DETAILS",
    "ACCOUNT-RECORD",
    "CUSTOMER-RECORD",
    "DATA-WAS-CHANGED-BEFORE-UPDATE"
   ],
   "given": "User A fetched account 00000000001 with balance 100.00; user B then saved balance 150.00",
   "when": "User A presses PF5 on their own validated changes",
   "then": "ERRMSG 'Record changed by some one else. Please review', nothing rewritten, state 'S'",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-041",
   "title": "PF5 record build, REWRITE and rollback",
   "rule": "The account update record is built from the new values (amounts from the NUMVAL-C fields, dates rebuilt as YYYY-MM-DD, group id) and the customer record from the new customer values (phones rebuilt as '(aaa)bbb-cccc', DOB as YYYY-MM-DD, SSN, FICO). ACCTDAT is rewritten first; a failure gives 'Update of record failed' (state 'F'). CUSTDAT is rewritten next; a failure gives 'Update of record failed' and SYNCPOINT ROLLBACK, which also undoes the account rewrite. Success sets state 'C' (info 'Changes committed to database').",
   "category": "state-change",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 523,
     "endLine": 524
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2606,
     "endLine": 2615
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 3956,
     "endLine": 4106
    }
   ],
   "fields": [
    "ACCT-UPDATE-RECORD",
    "CUST-UPDATE-RECORD",
    "ACCT-UPDATE-CREDIT-LIMIT",
    "ACCT-UPDATE-OPEN-DATE",
    "CUST-UPDATE-PHONE-NUM-1",
    "CUST-UPDATE-DOB-YYYY-MM-DD"
   ],
   "given": "State 'N' with new credit limit 6000.00 and phone parts 212/555/0100",
   "when": "The user presses PF5 and both rewrites succeed",
   "then": "ACCTDAT ACCT-CREDIT-LIMIT = 6000.00; CUSTDAT CUST-PHONE-NUM-1 = '(212)555-0100'; INFOMSG 'Changes committed to database'; if the CUSTDAT rewrite fails both records keep their old values",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-042",
   "title": "After save / PF12 cancel",
   "rule": "In state 'C', 'L' or 'F' the next interaction resets the program area and CDEMO-ACCT-ID and shows the empty search screen. When the save succeeded and the program was not entered from another transaction (CDEMO-FROM-TRANID blank), CDEMO-ACCT-ID and CDEMO-CARD-NUM are zeroed and CDEMO-ACCT-STATUS set to LOW-VALUES. PF12 (valid once details were fetched) re-reads the account and shows the stored values, discarding the edits.",
   "category": "control-flow",
   "sources": [
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 979,
     "endLine": 989
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2572,
     "endLine": 2580
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 2625,
     "endLine": 2632
    }
   ],
   "fields": [
    "ACUP-CHANGE-ACTION",
    "CDEMO-ACCT-ID",
    "CDEMO-CARD-NUM",
    "CDEMO-ACCT-STATUS"
   ],
   "given": "State 'E' after the user typed an invalid FICO 200",
   "when": "The user presses PF12",
   "then": "Account 00000000001 is read again, original values are shown, state 'S', the FICO error is gone",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-043",
   "title": "COMMAREA layout and pseudo-conversational return",
   "rule": "Account View and Update keep the shared CARDDEMO-COMMAREA (general info, customer, account, card, last map) followed by their own WS-THIS-PROGCOMMAREA, packed into a 2000-byte WS-COMMAREA and returned with EXEC CICS RETURN TRANSID('CAVW' / 'CAUP'). On entry with EIBCALEN = 0, or from the menu with context 0, both parts are initialised; otherwise they are restored from DFHCOMMAREA.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/cpy/COCOM01Y.cpy",
     "startLine": 19,
     "endLine": 44
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 282,
     "endLine": 293
    },
    {
     "file": "app/cbl/COACTVWC.cbl",
     "startLine": 394,
     "endLine": 407
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 850,
     "endLine": 850
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 880,
     "endLine": 893
    },
    {
     "file": "app/cbl/COACTUPC.cbl",
     "startLine": 1007,
     "endLine": 1020
    }
   ],
   "fields": [
    "CARDDEMO-COMMAREA",
    "WS-THIS-PROGCOMMAREA",
    "WS-COMMAREA",
    "CDEMO-PGM-CONTEXT",
    "EIBCALEN"
   ],
   "given": "Account Update shows account 00000000001 (state 'S')",
   "when": "The task ends",
   "then": "RETURN TRANSID('CAUP') with a 2000-byte COMMAREA: bytes 1-160 = CARDDEMO-COMMAREA (CDEMO-ACCT-ID 00000000001), then the COACTUPC area with ACUP-CHANGE-ACTION 'S' and the old/new values",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-044",
   "title": "COBDATFT linkage, registers and return code",
   "rule": "COBDATFT uses standard OS linkage: STM R14,R12,12(R13); its own 18-word SAVE area chained both ways (SAVE+4 / 8(,R13)); R13 -> SAVE; R12 base; R1 -> parameter list; R2 -> the single parameter (COREC: COINTYPE CL1, COINPDT CL20, COOUTYPE CL1, COOUTDT CL20, COERMSG CL38, matching CODATECN-REC). On exit it restores R13 and R14-R12 and does SR R15,R15, so the return code is always 0; errors are only signalled in COERMSG. Caller: CBACT01C (CALL 'COBDATFT' USING CODATECN-REC).",
   "category": "return-code",
   "sources": [
    {
     "file": "app/asm/COBDATFT.asm",
     "startLine": 17,
     "endLine": 29
    },
    {
     "file": "app/asm/COBDATFT.asm",
     "startLine": 57,
     "endLine": 64
    },
    {
     "file": "app/maclib/COCDATFT.mac",
     "startLine": 17,
     "endLine": 23
    },
    {
     "file": "app/cpy/CODATECN.cpy",
     "startLine": 17,
     "endLine": 52
    },
    {
     "file": "app/cbl/CBACT01C.cbl",
     "startLine": 223,
     "endLine": 233
    }
   ],
   "fields": [
    "CODATECN-REC",
    "COINTYPE",
    "COINPDT",
    "COOUTYPE",
    "COOUTDT",
    "COERMSG",
    "R15"
   ],
   "given": "CODATECN-REC with type '2', date '2022-07-05', out type '2'",
   "when": "CBACT01C calls COBDATFT",
   "then": "RETURN-CODE (R15) = 0 and the caller's registers R2-R12 are restored",
   "fixtures": [],
   "finding": "docs/LEGACY-LANGUAGES.md says R15 is not set, but the source zeroes R15 (SR R15,R15) on every path. The source wins: the converted routine must return 0 even on INVALID INPUT."
  },
  {
   "id": "BR-ONL-045",
   "title": "COBDATFT type 1: YYYYMMDD to YYYY-MM-DD",
   "rule": "When COINTYPE = '1' and COOUTYPE is not '2', COBDATFT builds COOUTDT as COINPDT(1:4) '-' COINPDT(5:2) '-' COINPDT(7:2) (10 bytes). The remaining 10 bytes of COOUTDT and COERMSG are not touched. There is no check that the digits are numeric or a real date.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/asm/COBDATFT.asm",
     "startLine": 30,
     "endLine": 31
    },
    {
     "file": "app/asm/COBDATFT.asm",
     "startLine": 35,
     "endLine": 45
    }
   ],
   "fields": [
    "COINTYPE",
    "COINPDT",
    "COOUTYPE",
    "COOUTDT"
   ],
   "given": "COINTYPE '1', COINPDT '20220705', COOUTYPE '1'",
   "when": "COBDATFT is called",
   "then": "COOUTDT(1:10) = '2022-07-05', COERMSG unchanged, R15 = 0",
   "fixtures": [],
   "finding": "The type-1 hyphen guard CLC COINPDT+4,=C'-' takes its implicit length from COINPDT (20 bytes), so it compares 20 bytes against a 1-byte literal plus whatever follows in the literal pool and in practice never matches; a type-1 input already in YYYY-MM-DD form is reformatted instead of rejected. Must be reproduced (from reading the code; HLASM cannot be run off-host)."
  },
  {
   "id": "BR-ONL-046",
   "title": "COBDATFT type 2: YYYY-MM-DD to YYYYMMDD (separator not checked)",
   "rule": "When COINTYPE = '2' and COOUTYPE is not '1', COOUTDT(1:8) = COINPDT(1:4) + COINPDT(6:2) + COINPDT(9:2). The separator check is commented out, so any character in positions 5 and 8 is accepted. CBACT01C uses this path for ACCT-REISSUE-DATE with type '2' and out type '2'.",
   "category": "data-mapping",
   "sources": [
    {
     "file": "app/asm/COBDATFT.asm",
     "startLine": 32,
     "endLine": 33
    },
    {
     "file": "app/asm/COBDATFT.asm",
     "startLine": 46,
     "endLine": 54
    },
    {
     "file": "app/cbl/CBACT01C.cbl",
     "startLine": 223,
     "endLine": 233
    }
   ],
   "fields": [
    "COINTYPE",
    "COINPDT",
    "COOUTYPE",
    "COOUTDT",
    "ACCT-REISSUE-DATE"
   ],
   "given": "COINTYPE '2', COINPDT '2022/07/05', COOUTYPE '2'",
   "when": "COBDATFT is called",
   "then": "COOUTDT(1:8) = '20220705' (the '/' separators are accepted), bytes 9-20 of COOUTDT unchanged, R15 = 0",
   "fixtures": [],
   "finding": "Because the hyphen check (CLC/BNE at lines 47-48) is commented out, malformed dates are reformatted instead of rejected. Must be reproduced."
  },
  {
   "id": "BR-ONL-047",
   "title": "COBDATFT INVALID INPUT",
   "rule": "COBDATFT moves 'INVALID INPUT' to COERMSG and leaves COOUTDT unchanged when COINTYPE is neither '1' nor '2', when type '1' is asked for output type '2', or when type '2' is asked for output type '1'. R15 is still 0. COERMSG is never cleared on success, so the caller must clear it.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/asm/COBDATFT.asm",
     "startLine": 30,
     "endLine": 34
    },
    {
     "file": "app/asm/COBDATFT.asm",
     "startLine": 38,
     "endLine": 39
    },
    {
     "file": "app/asm/COBDATFT.asm",
     "startLine": 49,
     "endLine": 50
    },
    {
     "file": "app/asm/COBDATFT.asm",
     "startLine": 55,
     "endLine": 57
    }
   ],
   "fields": [
    "COINTYPE",
    "COOUTYPE",
    "COERMSG",
    "CODATECN-ERROR-MSG"
   ],
   "given": "COINTYPE '3', COINPDT '20220705'",
   "when": "COBDATFT is called",
   "then": "COERMSG starts with 'INVALID INPUT', COOUTDT unchanged, R15 = 0",
   "fixtures": [],
   "finding": "MVC COERMSG,=C'INVALID INPUT' copies L'COERMSG = 38 bytes from a 13-byte literal, so bytes 14-38 come from whatever follows in the literal pool. On-host content is undefined; a port should keep 'INVALID INPUT' in bytes 1-13 and record bytes 14-38 as unspecified (proposed: spaces). Looks wrong; flagged for review."
  },
  {
   "id": "BR-ONL-048",
   "title": "MVSWAIT timed wait and COBSWAIT driver",
   "rule": "COBSWAIT reads an 8-character SYSIN value, moves it to a PIC 9(8) COMP fullword (MVSWAIT-TIME) and calls MVSWAIT. MVSWAIT (USING *,15) saves registers in the caller's save area (no save area of its own), loads the address of the fullword (L 5,0(1)) and its value (L 1,0(5)), stores it in BINLBL, waits that many hundredths of a second via the ASMWAIT (STIMER WAIT) macro, restores registers, sets the caller save-area byte 12(13) to X'FF', zeroes R15 (RC 0) and returns. There is no functional output.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/asm/MVSWAIT.asm",
     "startLine": 17,
     "endLine": 30
    },
    {
     "file": "app/cbl/COBSWAIT.cbl",
     "startLine": 30,
     "endLine": 40
    },
    {
     "file": "app/jcl/WAITSTEP.jcl",
     "startLine": 22,
     "endLine": 26
    }
   ],
   "fields": [
    "MVSWAIT-TIME",
    "PARM-VALUE",
    "BINLBL",
    "R15"
   ],
   "given": "WAITSTEP.jcl SYSIN card '00003600      VALUE IN CENTISECONDS'",
   "when": "Job WAITSTEP runs COBSWAIT",
   "then": "PARM-VALUE = '00003600', MVSWAIT-TIME = 3600, the task waits 36.00 seconds, RC 0; the off-host port treats the wait as a no-op with RC 0",
   "fixtures": [],
   "finding": "COBSWAIT moves the SYSIN text straight into a binary field with no NUMERIC check; a non-numeric card gives an unpredictable delay. Also MVI 12(13),X'FF' writes into the caller's save area (the source asks whether it can be removed). Must be reproduced, not fixed."
  },
  {
   "id": "BR-ONL-049",
   "title": "CBFEECAL late fee is truncated to cents",
   "rule": "WORK_FEE = FEE_CYCLE_BAL * FEE_PCT (2.500) / 100 in DECIMAL FIXED(15,5) and is assigned to FEE_AMT DECIMAL FIXED(9,2); PL/I assignment truncates the extra decimals (no rounding). RC 0.",
   "category": "rounding",
   "sources": [
    {
     "file": "app/pli/CBFEECAL.pli",
     "startLine": 36,
     "endLine": 40
    },
    {
     "file": "app/pli/CBFEECAL.pli",
     "startLine": 79,
     "endLine": 80
    },
    {
     "file": "docs/LEGACY-LANGUAGES.md",
     "startLine": 71,
     "endLine": 73
    }
   ],
   "fields": [
    "FEE_CYCLE_BAL",
    "FEE_PCT",
    "WORK_FEE",
    "FEE_AMT",
    "FEE-AMT"
   ],
   "given": "Worked example 1: GROUP_ID 'STANDARD', CYCLE_BAL 1234.56, LATE_DAYS 10, CYCLE_DAYS 30",
   "when": "CBFEECAL is called",
   "then": "FEE_AMT = 30.86 (30.864 truncated, not 30.87), FEE_AVG_DAILY = 41.15, FEE_RC = 0",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-050",
   "title": "CBFEECAL floor 5.00 and cap 35.00",
   "rule": "After truncation, a fee below FEE_FLOOR (5.00) is raised to 5.00 and a fee above FEE_CAP (35.00) is lowered to 35.00. RC 0.",
   "category": "calculation",
   "sources": [
    {
     "file": "app/pli/CBFEECAL.pli",
     "startLine": 37,
     "endLine": 38
    },
    {
     "file": "app/pli/CBFEECAL.pli",
     "startLine": 82,
     "endLine": 83
    },
    {
     "file": "docs/LEGACY-LANGUAGES.md",
     "startLine": 74,
     "endLine": 75
    }
   ],
   "fields": [
    "FEE_AMT",
    "FEE_FLOOR",
    "FEE_CAP"
   ],
   "given": "Worked examples 2 and 3: 'STANDARD' with CYCLE_BAL 100.00, and with 5000.00 (LATE_DAYS 10, CYCLE_DAYS 30)",
   "when": "CBFEECAL is called",
   "then": "100.00: 2.50 raised to FEE_AMT 5.00, FEE_AVG_DAILY 3.33, RC 0. 5000.00: 125.00 capped to 35.00, FEE_AVG_DAILY 166.66, RC 0",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-051",
   "title": "CBFEECAL GOLD waiver (RC 4)",
   "rule": "If FEE_GROUP_ID = 'GOLD' (CHAR(10), compared blank-padded) and FEE_CYCLE_BAL < 100.00 (WAIVE_BELOW), the fee is waived: FEE_AMT stays 0, FEE_RC = 4. The average daily balance has already been computed.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/pli/CBFEECAL.pli",
     "startLine": 39,
     "endLine": 39
    },
    {
     "file": "app/pli/CBFEECAL.pli",
     "startLine": 63,
     "endLine": 77
    },
    {
     "file": "docs/LEGACY-LANGUAGES.md",
     "startLine": 76,
     "endLine": 76
    }
   ],
   "fields": [
    "FEE_GROUP_ID",
    "FEE_CYCLE_BAL",
    "WAIVE_BELOW",
    "FEE_RC",
    "FEE_AVG_DAILY"
   ],
   "given": "Worked example 4: GROUP_ID 'GOLD', CYCLE_BAL 80.00, LATE_DAYS 10, CYCLE_DAYS 30",
   "when": "CBFEECAL is called",
   "then": "FEE_AMT = 0.00, FEE_AVG_DAILY = 2.66 (80/30 truncated), FEE_RC = 4",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-052",
   "title": "CBFEECAL average daily balance and ZERODIVIDE (RC 8)",
   "rule": "FEE_AVG_DAILY = FEE_CYCLE_BAL / FEE_CYCLE_DAYS is assigned to DECIMAL FIXED(12,2) (truncating). With 0 cycle days ZERODIVIDE is raised: the ON-unit writes 'CBFEECAL: ZERODIVIDE FOR ACCOUNT ' + FEE_ACCT_ID to SYSPRINT (PUT SKIP LIST), sets FEE_AMT = 0, FEE_AVG_DAILY = 0, FEE_RC = 8 and leaves through FEE_EXIT.",
   "category": "sysout",
   "sources": [
    {
     "file": "app/pli/CBFEECAL.pli",
     "startLine": 42,
     "endLine": 48
    },
    {
     "file": "app/pli/CBFEECAL.pli",
     "startLine": 68,
     "endLine": 69
    },
    {
     "file": "docs/LEGACY-LANGUAGES.md",
     "startLine": 77,
     "endLine": 81
    }
   ],
   "fields": [
    "FEE_CYCLE_DAYS",
    "FEE_AVG_DAILY",
    "FEE_AMT",
    "FEE_RC",
    "FEE_ACCT_ID"
   ],
   "given": "Worked example 5: 'STANDARD', CYCLE_BAL 1000.00, LATE_DAYS 10, CYCLE_DAYS 0, ACCT_ID '00000000005'",
   "when": "CBFEECAL is called",
   "then": "SYSPRINT gets a list-directed line with the items 'CBFEECAL: ZERODIVIDE FOR ACCOUNT ' and '00000000005' (item spacing follows PL/I list-directed tab stops); FEE_AMT 0.00, FEE_AVG_DAILY 0.00, FEE_RC 8",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-053",
   "title": "CBFEECAL nothing overdue (late days <= 0)",
   "rule": "If FEE_LATE_DAYS <= 0 the routine exits with FEE_AMT 0 and FEE_RC 0, before the GOLD waiver and the fee formula. The average daily division runs before this check, so zero cycle days still gives ZERODIVIDE RC 8 even when nothing is overdue.",
   "category": "edge-case",
   "sources": [
    {
     "file": "app/pli/CBFEECAL.pli",
     "startLine": 63,
     "endLine": 72
    },
    {
     "file": "docs/LEGACY-LANGUAGES.md",
     "startLine": 78,
     "endLine": 78
    }
   ],
   "fields": [
    "FEE_LATE_DAYS",
    "FEE_AMT",
    "FEE_RC",
    "FEE_AVG_DAILY"
   ],
   "given": "Worked example 6: GROUP_ID 'GOLD', CYCLE_BAL 0.00, LATE_DAYS 0, CYCLE_DAYS 30",
   "when": "CBFEECAL is called",
   "then": "FEE_AMT 0.00, FEE_AVG_DAILY 0.00, FEE_RC 0 (not 4, because the late-days exit comes before the GOLD test); with CYCLE_DAYS 0 the same request gives RC 8",
   "fixtures": [],
   "finding": null
  },
  {
   "id": "BR-ONL-054",
   "title": "CBFEECAL SIZE and ERROR conditions (RC 8)",
   "rule": "The calculation block runs with the SIZE condition enabled. ON SIZE writes 'CBFEECAL: SIZE CONDITION FOR ACCOUNT ' + id, sets FEE_AMT = 0 and FEE_RC = 8. ON ERROR writes 'CBFEECAL: UNEXPECTED ERROR, ACCOUNT ' + id and sets FEE_RC = 8 without resetting FEE_AMT or FEE_AVG_DAILY. Both leave through FEE_EXIT.",
   "category": "return-code",
   "sources": [
    {
     "file": "app/pli/CBFEECAL.pli",
     "startLine": 50,
     "endLine": 61
    },
    {
     "file": "app/pli/CBFEECAL.pli",
     "startLine": 63,
     "endLine": 65
    },
    {
     "file": "app/pli/CBFEECAL.pli",
     "startLine": 86,
     "endLine": 87
    }
   ],
   "fields": [
    "FEE_AMT",
    "FEE_RC",
    "FEE_AVG_DAILY",
    "FEE_ACCT_ID"
   ],
   "given": "A request whose FEE_CYCLE_BAL / FEE_CYCLE_DAYS result does not fit DECIMAL FIXED(12,2) (e.g. a corrupt packed field read from storage)",
   "when": "CBFEECAL is called",
   "then": "SYSPRINT 'CBFEECAL: SIZE CONDITION FOR ACCOUNT <id>', FEE_AMT 0, FEE_RC 8",
   "fixtures": [],
   "finding": "ON ERROR keeps whatever FEE_AMT / FEE_AVG_DAILY held when the error was raised, unlike the other ON-units that zero them. Must be reproduced."
  },
  {
   "id": "BR-ONL-055",
   "title": "CBFEEDRV driver: fee request and FEEOUT line",
   "rule": "For every ACCTFILE record CBFEEDRV sets FEE-ACCT-ID, FEE-GROUP-ID, FEE-CYCLE-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT, FEE-LATE-DAYS = 10 when the cycle balance > 0 else 0, FEE-CYCLE-DAYS = 30, clears FEE-AMT/FEE-AVG-DAILY/FEE-RC, calls CBFEECAL BY REFERENCE and writes an 80-byte line: account id X(11), space, group X(10), space, cycle balance -(10)9.99, space, fee -(7)9.99, space, RC 99, 28 spaces (job FEECALC STEP05, output AWS.M2.CARDDEMO.LATEFEES(+1)).",
   "category": "calculation",
   "sources": [
    {
     "file": "app/cbl/CBFEEDRV.cbl",
     "startLine": 54,
     "endLine": 73
    },
    {
     "file": "app/cbl/CBFEEDRV.cbl",
     "startLine": 86,
     "endLine": 108
    },
    {
     "file": "app/jcl/FEECALC.jcl",
     "startLine": 24,
     "endLine": 34
    }
   ],
   "fields": [
    "ACCT-CURR-CYC-CREDIT",
    "ACCT-CURR-CYC-DEBIT",
    "FEE-CYCLE-BAL",
    "FEE-LATE-DAYS",
    "FEE-CYCLE-DAYS",
    "FEE-LINE",
    "FL-RC"
   ],
   "given": "Account 00000000001, group 'STANDARD', cycle credit 1500.00, cycle debit 265.44",
   "when": "FEECALC runs",
   "then": "FEE-CYCLE-BAL 1234.56, LATE-DAYS 10, CYCLE-DAYS 30; FEEOUT line '00000000001 STANDARD          1234.56       30.86 00' padded with spaces to 80 bytes; an account with cycle debit > credit gets LATE-DAYS 0, fee 0.00, RC 00",
   "fixtures": [],
   "finding": "The cycle balance is credit minus debit, so an account is treated as overdue when its credits exceed its debits, which looks inverted for a balance due. Must be reproduced as written."
  }
 ]
}
```
