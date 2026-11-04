CREATE TABLE IF NOT EXISTS carddemo.user_security (
    sec_usr_id varchar(8) PRIMARY KEY,
    sec_usr_fname varchar(20) NOT NULL,
    sec_usr_lname varchar(20) NOT NULL,
    sec_usr_pwd varchar(8) NOT NULL,
    sec_usr_type varchar(1) NOT NULL,
    sec_usr_filler varchar(23) NOT NULL DEFAULT repeat(' ', 23)
);

CREATE TABLE IF NOT EXISTS carddemo.card (
    card_num varchar(16) PRIMARY KEY,
    card_acct_id numeric(11,0) NOT NULL,
    card_cvv_cd numeric(3,0) NOT NULL,
    card_embossed_name varchar(50) NOT NULL,
    card_expiraion_date varchar(10) NOT NULL,
    card_active_status varchar(1) NOT NULL
);

CREATE TABLE IF NOT EXISTS carddemo.customer (
    cust_id numeric(9,0) PRIMARY KEY,
    cust_first_name varchar(25) NOT NULL,
    cust_middle_name varchar(25) NOT NULL,
    cust_last_name varchar(25) NOT NULL,
    cust_addr_line_1 varchar(50) NOT NULL,
    cust_addr_line_2 varchar(50) NOT NULL,
    cust_addr_line_3 varchar(50) NOT NULL,
    cust_addr_state_cd varchar(2) NOT NULL,
    cust_addr_country_cd varchar(3) NOT NULL,
    cust_addr_zip varchar(10) NOT NULL,
    cust_phone_num_1 varchar(15) NOT NULL,
    cust_phone_num_2 varchar(15) NOT NULL,
    cust_ssn numeric(9,0) NOT NULL,
    cust_govt_issued_id varchar(20) NOT NULL,
    cust_dob_yyyy_mm_dd varchar(10) NOT NULL,
    cust_eft_account_id varchar(10) NOT NULL,
    cust_pri_card_holder_ind varchar(1) NOT NULL,
    cust_fico_credit_score numeric(3,0) NOT NULL
);
