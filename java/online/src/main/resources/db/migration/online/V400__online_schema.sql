-- Online slice (CICS programs COSGN00C, COMEN01C, COACTVWC, COACTUPC).
-- Own schema: carddemo.* is truncated by every batch chain run.  CHAR(n) = PIC X(n), so values
-- come back blank-padded exactly like the VSAM record areas.
CREATE SCHEMA IF NOT EXISTS carddemo_online;

-- USRSEC, copybook CSUSR01Y
CREATE TABLE carddemo_online.user_security (
    sec_usr_id     CHAR(8)  COLLATE "C" PRIMARY KEY,
    sec_usr_fname  CHAR(20) NOT NULL,
    sec_usr_lname  CHAR(20) NOT NULL,
    sec_usr_pwd    CHAR(8)  NOT NULL,
    sec_usr_type   CHAR(1)  NOT NULL
);

-- CUSTDAT, copybook CVCUS01Y
CREATE TABLE carddemo_online.customer (
    cust_id                  BIGINT PRIMARY KEY,           -- 9(09)
    cust_first_name          CHAR(25) NOT NULL,
    cust_middle_name         CHAR(25) NOT NULL,
    cust_last_name           CHAR(25) NOT NULL,
    cust_addr_line_1         CHAR(50) NOT NULL,
    cust_addr_line_2         CHAR(50) NOT NULL,
    cust_addr_line_3         CHAR(50) NOT NULL,
    cust_addr_state_cd       CHAR(2)  NOT NULL,
    cust_addr_country_cd     CHAR(3)  NOT NULL,
    cust_addr_zip            CHAR(10) NOT NULL,
    cust_phone_num_1         CHAR(15) NOT NULL,
    cust_phone_num_2         CHAR(15) NOT NULL,
    cust_ssn                 BIGINT   NOT NULL,            -- 9(09)
    cust_govt_issued_id      CHAR(20) NOT NULL,
    cust_dob_yyyy_mm_dd      CHAR(10) NOT NULL,
    cust_eft_account_id      CHAR(10) NOT NULL,
    cust_pri_card_holder_ind CHAR(1)  NOT NULL,
    cust_fico_credit_score   INTEGER  NOT NULL             -- 9(03)
);

-- ACCTDAT, copybook CVACT01Y
CREATE TABLE carddemo_online.account (
    acct_id                BIGINT PRIMARY KEY,             -- 9(11)
    acct_active_status     CHAR(1)  NOT NULL,
    acct_curr_bal          NUMERIC(12,2) NOT NULL,         -- S9(10)V99
    acct_credit_limit      NUMERIC(12,2) NOT NULL,
    acct_cash_credit_limit NUMERIC(12,2) NOT NULL,
    acct_open_date         CHAR(10) NOT NULL,
    acct_expiraion_date    CHAR(10) NOT NULL,
    acct_reissue_date      CHAR(10) NOT NULL,
    acct_curr_cyc_credit   NUMERIC(12,2) NOT NULL,
    acct_curr_cyc_debit    NUMERIC(12,2) NOT NULL,
    acct_addr_zip          CHAR(10) NOT NULL,
    acct_group_id          CHAR(10) NOT NULL
);

-- CXACAIX (CARDXREF by account alternate index), copybook CVACT03Y
CREATE TABLE carddemo_online.card_xref (
    xref_card_num CHAR(16) COLLATE "C" PRIMARY KEY,
    xref_cust_id  BIGINT NOT NULL,                         -- 9(09)
    xref_acct_id  BIGINT NOT NULL                          -- 9(11)
);
CREATE INDEX card_xref_acct_aix ON carddemo_online.card_xref (xref_acct_id);
