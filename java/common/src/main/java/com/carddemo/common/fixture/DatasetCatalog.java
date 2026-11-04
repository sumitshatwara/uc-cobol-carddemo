package com.carddemo.common.fixture;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DatasetCatalog {
    private static final Map<String, Dataset> DATASETS = Map.ofEntries(
            entry("AWS.M2.CARDDEMO.ACCTDATA.PS", "account", "CVACT01Y", "acct_id"),
            entry("AWS.M2.CARDDEMO.CARDXREF.PS", "card_xref", "CVACT03Y", "xref_card_num"),
            entry("AWS.M2.CARDDEMO.TCATBALF.PS", "tran_cat_balance", "CVTRA01Y",
                    "trancat_acct_id", "trancat_type_cd", "trancat_cd"),
            entry("AWS.M2.CARDDEMO.DISCGRP.PS", "disclosure_group", "CVTRA02Y",
                    "dis_acct_group_id", "dis_tran_type_cd", "dis_tran_cat_cd"),
            entry("AWS.M2.CARDDEMO.TRANTYPE.PS", "tran_type", "CVTRA03Y", "tran_type"),
            entry("AWS.M2.CARDDEMO.TRANCATG.PS", "tran_category", "CVTRA04Y",
                    "tran_type_cd", "tran_cat_cd"),
            entry("AWS.M2.CARDDEMO.DALYTRAN.PS", "daily_transaction", "CVTRA06Y", "dalytran_id"),
            entry("AWS.M2.CARDDEMO.DALYTRAN.PS.INIT", "transaction", "CVTRA05Y", "tran_id"));

    private DatasetCatalog() {
    }

    public static Optional<Dataset> find(String dsn) {
        return Optional.ofNullable(DATASETS.get(dsn));
    }

    public static Map<String, Dataset> all() {
        return DATASETS;
    }

    private static Map.Entry<String, Dataset> entry(String dsn, String table, String layout, String... keys) {
        return Map.entry(dsn, new Dataset(dsn, table, layout, List.of(keys)));
    }

    public record Dataset(String dsn, String table, String copybook, List<String> keyColumns) {
        public Dataset {
            keyColumns = List.copyOf(keyColumns);
        }
    }
}
