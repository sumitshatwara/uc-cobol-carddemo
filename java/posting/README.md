# Posting slice

The `posting` chain implements POSTTRAN (`CBTRN02C`) and TRANREPT
(`CBTRN03C`) using the copybook record layouts and fixture-backed PostgreSQL
tables. The handler reads the daily sequential input in file order, writes
rejects and report generations, and preserves the original batch return codes
and documented COBOL quirks.

`DailyPostingService` depends on `PostingStore`; `TransactionReportService`
depends on `ReportLookups`. JDBC implementations are used at runtime, while
the unit suite uses an in-memory store. The 47 business rules are covered by
`PostingBusinessRulesTest`; database fixture coverage and the UI flow are in
`PostingFixtureDbTest` and `ui/e2e/posting.spec.ts`.

Run the slice checks with `make parity-java CHAIN=posting`,
`make test-unit`, and `make test-functional SLICE=posting`. No posting
migration is required; if one becomes necessary, use V100–V199.
