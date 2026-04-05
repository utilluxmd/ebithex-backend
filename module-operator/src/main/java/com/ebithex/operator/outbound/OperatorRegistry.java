package com.ebithex.operator.outbound;

import com.ebithex.shared.domain.OperatorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry qui découvre tous les beans MobileMoneyOperator et route
 * les requêtes vers le bon adapter par OperatorType.
 *
 * Un adapter peut couvrir plusieurs OperatorType via getSupportedTypes()
 * (ex: WaveOperator gère WAVE_CI et WAVE_SN avec la même API).
 */
@Component
@Slf4j
public class OperatorRegistry {

    private final Map<OperatorType, MobileMoneyOperator> operators;

    public OperatorRegistry(List<MobileMoneyOperator> operatorList) {
        this.operators = new HashMap<>();
        for (MobileMoneyOperator adapter : operatorList) {
            for (OperatorType type : adapter.getSupportedTypes()) {
                operators.put(type, adapter);
            }
        }
        log.info("Registered {} operator adapters covering {} operator types: {}",
                operatorList.size(), operators.size(), operators.keySet());
    }

    public MobileMoneyOperator get(OperatorType type) {
        return Optional.ofNullable(operators.get(type))
                .orElseThrow(() -> new IllegalArgumentException(
                    "No adapter registered for operator: " + type));
    }

    public boolean supports(OperatorType type) {
        return operators.containsKey(type);
    }
}