CREATE SCHEMA IF NOT EXISTS carddemo;

CREATE TABLE carddemo.account (
    acct_id numeric(11,0) NOT NULL,
    acct_active_status varchar(1) NOT NULL,
    acct_curr_bal numeric(12,2) NOT NULL,
    acct_credit_limit numeric(12,2) NOT NULL,
    acct_cash_credit_limit numeric(12,2) NOT NULL,
    acct_open_date varchar(10) NOT NULL,
    acct_expiraion_date varchar(10) NOT NULL,
    acct_reissue_date varchar(10) NOT NULL,
    acct_curr_cyc_credit numeric(12,2) NOT NULL,
    acct_curr_cyc_debit numeric(12,2) NOT NULL,
    acct_addr_zip varchar(10) NOT NULL,
    acct_group_id varchar(10) NOT NULL,
    PRIMARY KEY (acct_id)
);

CREATE TABLE carddemo.card_xref (
    xref_card_num varchar(16) NOT NULL,
    xref_cust_id numeric(9,0) NOT NULL,
    xref_acct_id numeric(11,0) NOT NULL,
    PRIMARY KEY (xref_card_num)
);

CREATE TABLE carddemo.tran_cat_balance (
    trancat_acct_id numeric(11,0) NOT NULL,
    trancat_type_cd varchar(2) NOT NULL,
    trancat_cd numeric(4,0) NOT NULL,
    tran_cat_bal numeric(11,2) NOT NULL,
    PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)
);

CREATE TABLE carddemo.disclosure_group (
    dis_acct_group_id varchar(10) NOT NULL,
    dis_tran_type_cd varchar(2) NOT NULL,
    dis_tran_cat_cd numeric(4,0) NOT NULL,
    dis_int_rate numeric(6,2) NOT NULL,
    PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd)
);

CREATE TABLE carddemo.tran_type (
    tran_type varchar(2) NOT NULL,
    tran_type_desc varchar(50) NOT NULL,
    PRIMARY KEY (tran_type)
);

CREATE TABLE carddemo.tran_category (
    tran_type_cd varchar(2) NOT NULL,
    tran_cat_cd numeric(4,0) NOT NULL,
    tran_cat_type_desc varchar(50) NOT NULL,
    PRIMARY KEY (tran_type_cd, tran_cat_cd)
);

CREATE TABLE carddemo.daily_transaction (
    dalytran_id varchar(16) NOT NULL,
    dalytran_type_cd varchar(2) NOT NULL,
    dalytran_cat_cd numeric(4,0) NOT NULL,
    dalytran_source varchar(10) NOT NULL,
    dalytran_desc varchar(100) NOT NULL,
    dalytran_amt numeric(11,2) NOT NULL,
    dalytran_merchant_id numeric(9,0) NOT NULL,
    dalytran_merchant_name varchar(50) NOT NULL,
    dalytran_merchant_city varchar(50) NOT NULL,
    dalytran_merchant_zip varchar(10) NOT NULL,
    dalytran_card_num varchar(16) NOT NULL,
    dalytran_orig_ts varchar(26) NOT NULL,
    dalytran_proc_ts varchar(26) NOT NULL,
    PRIMARY KEY (dalytran_id)
);

CREATE TABLE carddemo."transaction" (
    tran_id varchar(16) NOT NULL,
    tran_type_cd varchar(2) NOT NULL,
    tran_cat_cd numeric(4,0) NOT NULL,
    tran_source varchar(10) NOT NULL,
    tran_desc varchar(100) NOT NULL,
    tran_amt numeric(11,2) NOT NULL,
    tran_merchant_id numeric(9,0) NOT NULL,
    tran_merchant_name varchar(50) NOT NULL,
    tran_merchant_city varchar(50) NOT NULL,
    tran_merchant_zip varchar(10) NOT NULL,
    tran_card_num varchar(16) NOT NULL,
    tran_orig_ts varchar(26) NOT NULL,
    tran_proc_ts varchar(26) NOT NULL,
    PRIMARY KEY (tran_id)
);
