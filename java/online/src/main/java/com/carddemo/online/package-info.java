/**
 * Slice module {@code online}: the CICS sign-on / menu / account view / account update flow
 * (COSGN00C, COMEN01C, COACTVWC, COACTUPC) as Spring services and REST endpoints, plus ports of
 * the HLASM routines COBDATFT and MVSWAIT and the PL/I routine CBFEECAL.  The legacy forms are
 * analysis-only off-host (no CICS region, assembler or PL/I compiler), so this slice has no
 * parity chain; it is verified by unit tests per business rule (BR-ONL-001..074).
 * See java/online/README.md.
 */
package com.carddemo.online;
