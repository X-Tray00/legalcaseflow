package bg.nbu.legalcaseflow.report.dto;

/** Generic "label -> count" row used by the statistics endpoints. */
public record LabeledCount(String label, Long count) {
}
