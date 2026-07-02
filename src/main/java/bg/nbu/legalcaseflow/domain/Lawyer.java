package bg.nbu.legalcaseflow.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;

/** A lawyer (analog of "Доктор" in the medical reference project). */
@Entity
@Table(name = "lawyers")
@SQLRestriction("deleted_at is null")
@Getter
@Setter
@NoArgsConstructor
public class Lawyer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Bar registration number — unique identifier. */
    @Column(nullable = false, unique = true)
    private String registrationNumber;

    @Column(nullable = false)
    private String fullName;

    /** e.g. наказателно / гражданско / търговско право */
    private String specialty;

    private Instant deletedAt;
}
