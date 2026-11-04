INSERT INTO carddemo.user_security
    (sec_usr_id, sec_usr_fname, sec_usr_lname, sec_usr_pwd, sec_usr_type, sec_usr_filler)
VALUES
    ('ADMIN001', 'ADMIN', 'USER', 'PASSWORD', 'A', repeat(' ', 23)),
    ('USER0001', 'JOHN', 'SAMPLE', 'PASSWORD', 'U', repeat(' ', 23))
ON CONFLICT (sec_usr_id) DO UPDATE SET
    sec_usr_fname=EXCLUDED.sec_usr_fname, sec_usr_lname=EXCLUDED.sec_usr_lname,
    sec_usr_pwd=EXCLUDED.sec_usr_pwd, sec_usr_type=EXCLUDED.sec_usr_type,
    sec_usr_filler=EXCLUDED.sec_usr_filler;

INSERT INTO carddemo.customer
    (cust_id, cust_first_name, cust_middle_name, cust_last_name, cust_addr_line_1,
     cust_addr_line_2, cust_addr_line_3, cust_addr_state_cd, cust_addr_country_cd,
     cust_addr_zip, cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id,
     cust_dob_yyyy_mm_dd, cust_eft_account_id, cust_pri_card_holder_ind, cust_fico_credit_score)
VALUES
    (1, 'JOHN', 'Q', 'SAMPLE', '100 MAIN STREET', 'APT 1', 'NEW YORK', 'NY', 'USA',
     '10001', '(212)555-0100', '(212)555-0101', 123456789, 'DL0000001',
     '1980-01-15', '0000000001', 'Y', 720),
    (2, 'JANE', 'R', 'SAMPLE', '200 OAK STREET', 'APT 2', 'NEW YORK', 'NY', 'USA',
     '10002', '(212)555-0102', '(212)555-0103', 234567890, 'DL0000002',
     '1985-02-16', '0000000002', 'Y', 700),
    (3, 'ALEX', 'T', 'SAMPLE', '300 PINE STREET', 'APT 3', 'NEW YORK', 'NY', 'USA',
     '10003', '(212)555-0104', '(212)555-0105', 345678901, 'DL0000003',
     '1990-03-17', '0000000003', 'Y', 680)
ON CONFLICT (cust_id) DO UPDATE SET
    cust_first_name=EXCLUDED.cust_first_name, cust_middle_name=EXCLUDED.cust_middle_name,
    cust_last_name=EXCLUDED.cust_last_name, cust_addr_line_1=EXCLUDED.cust_addr_line_1,
    cust_addr_line_2=EXCLUDED.cust_addr_line_2, cust_addr_line_3=EXCLUDED.cust_addr_line_3,
    cust_addr_state_cd=EXCLUDED.cust_addr_state_cd, cust_addr_country_cd=EXCLUDED.cust_addr_country_cd,
    cust_addr_zip=EXCLUDED.cust_addr_zip, cust_phone_num_1=EXCLUDED.cust_phone_num_1,
    cust_phone_num_2=EXCLUDED.cust_phone_num_2, cust_ssn=EXCLUDED.cust_ssn,
    cust_govt_issued_id=EXCLUDED.cust_govt_issued_id, cust_dob_yyyy_mm_dd=EXCLUDED.cust_dob_yyyy_mm_dd,
    cust_eft_account_id=EXCLUDED.cust_eft_account_id, cust_pri_card_holder_ind=EXCLUDED.cust_pri_card_holder_ind,
    cust_fico_credit_score=EXCLUDED.cust_fico_credit_score;

INSERT INTO carddemo.card
    (card_num, card_acct_id, card_cvv_cd, card_embossed_name, card_expiraion_date, card_active_status)
VALUES
    ('4111111111111111', 1, 111, 'JOHN Q SAMPLE', '2025-05-20', 'Y'),
    ('4111111111111112', 2, 222, 'JANE R SAMPLE', '2025-06-20', 'Y'),
    ('4111111111111113', 3, 333, 'ALEX T SAMPLE', '2025-07-20', 'Y')
ON CONFLICT (card_num) DO UPDATE SET
    card_acct_id=EXCLUDED.card_acct_id, card_cvv_cd=EXCLUDED.card_cvv_cd,
    card_embossed_name=EXCLUDED.card_embossed_name, card_expiraion_date=EXCLUDED.card_expiraion_date,
    card_active_status=EXCLUDED.card_active_status;
