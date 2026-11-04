CREATE SCHEMA IF NOT EXISTS carddemo;

CREATE TABLE carddemo.transaction_type
(   tr_type                        CHAR(2) NOT NULL,
    tr_description                 VARCHAR(50) NOT NULL,
    PRIMARY KEY (tr_type));

CREATE TABLE carddemo.transaction_type_categor
(   trc_type_code                  CHAR(2) NOT NULL,
    trc_type_category              CHAR(4) NOT NULL,
    trc_cat_data                   VARCHAR(50) NOT NULL,
    PRIMARY KEY (trc_type_code, trc_type_category),
    CONSTRAINT trc_type_code FOREIGN KEY (trc_type_code)
    REFERENCES carddemo.transaction_type (tr_type) ON DELETE RESTRICT);
