package bg.nbu.legalcaseflow.domain;

/** Application roles. ADMIN = full access, LAWYER = sees all / edits own, CLIENT = sees own. */
public enum Role {
    ADMIN,
    LAWYER,
    CLIENT
}
