package bg.nbu.legalcaseflow.domain;

/**
 * Who pays for a legal service.
 * NBPP = National Legal Aid Bureau (state pays when the client is eligible) — the analog of НЗОК.
 * CLIENT = the client pays out of pocket.
 */
public enum Payer {
    CLIENT,
    NBPP
}
