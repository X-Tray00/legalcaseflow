package bg.nbu.legalcaseflow.report.dto;

import java.math.BigDecimal;

/** Generic "label -> amount" row used by the revenue endpoints. */
public record LabeledAmount(String label, BigDecimal amount) {
}
