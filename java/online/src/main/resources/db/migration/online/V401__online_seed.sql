-- Synthetic seed data only: no real people, card numbers or customer references.
-- Passwords are the CardDemo sample convention (upper case, 8 chars); sign-on upper-cases input.
INSERT INTO carddemo_online.user_security VALUES
  ('ADMIN001', 'DEMO',  'ADMIN',     'PASSWORD', 'A'),
  ('USER0001', 'DEMO',  'OPERATOR',  'PASSWORD', 'U'),
  ('USER0002', 'TEST',  'OPERATOR',  'PASSW0RD', 'U');

INSERT INTO carddemo_online.customer VALUES
  (99,  'AVERY',  'Q',  'SAMPLE',  '100 TEST STREET', 'UNIT 1', 'SPRINGFIELD', 'IL', 'USA', '62701',
        '(217)555-0142', '(217)555-0143', 123456789, 'TESTID-0099', '1985-06-15', '0000099001', 'Y', 720),
  (100, 'JORDAN', '',   'EXAMPLE', '200 DEMO AVENUE', '',       'ALBANY',      'NY', 'USA', '12207',
        '(518)555-0100', '', 234567890, 'TESTID-0100', '1990-01-31', '0000100001', 'Y', 680),
  (101, 'CASEY',  'R',  'PLACEHOLDER', '300 MOCK ROAD', '',     'AUSTIN',      'TX', 'USA', '73301',
        '(512)555-0199', '', 345678901, 'TESTID-0101', '1978-11-02', '0000101001', 'N', 805);

INSERT INTO carddemo_online.account VALUES
  (99,  'Y',  150.00, 2000.00,  500.00, '2019-03-15', '2027-03-31', '2023-03-15', 1500.00, 265.44, '62701', 'STANDARD'),
  (100, 'Y', 4210.75, 5000.00, 1000.00, '2020-08-01', '2028-08-31', '2024-08-01',  100.00,   0.00, '12207', 'GOLD'),
  (101, 'N',    0.00, 1500.00,  300.00, '2018-01-10', '2026-01-31', '2022-01-10',    0.00,   0.00, '73301', 'STANDARD');

INSERT INTO carddemo_online.card_xref VALUES
  ('4000000000000099', 99,  99),
  ('4000000000000100', 100, 100),
  ('4000000000000101', 101, 101);
