## interest / i02-tie-half-cent — naive half-even reference: **FAIL (2 differences)**

Checked 2 datasets, 4 records, 50 decoded fields, 1 SYSOUT files, 0 table snapshots, return codes.

| Where | Field | Expected (legacy) | Actual (candidate) |
|---|---|---|---|
| AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS rec 1 (ACCT-ID=2003) | `ACCT-CURR-BAL` | 1247.85 | 1247.86 |
| AWS.M2.CARDDEMO.SYSTRAN.G0001V00 rec 1 (TRAN-ID=2022071800000001) | `TRAN-AMT` | 12.35 | 12.36 |

Fields differing: `ACCT-CURR-BAL`, `TRAN-AMT`

