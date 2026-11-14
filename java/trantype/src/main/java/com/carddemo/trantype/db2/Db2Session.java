package com.carddemo.trantype.db2;

/**
 * The Db2 thread of one DSN attach: every statement runs in the same unit of work, and the
 * returned value is the SQLCODE the program finds in its SQLCA.  There is no autocommit and no
 * savepoint; the unit of work ends only with {@link #commitAtEndOfTask()}.
 *
 * @see "BR-TT-023, BR-TT-024, BR-TT-038"
 */
public interface Db2Session {

    /** Static SQL with host variables bound in order (character host variables as they are, unpadded/untrimmed). */
    int execute(String sql, String... hostVariables);

    /** {@code EXEC SQL PREPARE name FROM :text}. */
    int prepare(String statementName, String text);

    /** {@code EXEC SQL EXECUTE name}. */
    int executePrepared(String statementName);

    /** The commit the DSN command performs at normal end of task. */
    int commitAtEndOfTask();
}
