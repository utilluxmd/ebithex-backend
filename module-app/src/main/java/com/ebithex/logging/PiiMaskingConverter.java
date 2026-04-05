package com.ebithex.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Convertisseur Logback qui masque les données personnelles dans les messages de log.
 *
 * <p>Patterns masqués :
 * <ul>
 *   <li>Numéros de téléphone E.164 : {@code +22507XXXXXXXX} → {@code ***PHONE***}</li>
 *   <li>Adresses email : {@code user@domain.com} → {@code ***EMAIL***}</li>
 * </ul>
 *
 * <p>Enregistré dans {@code logback-spring.xml} via {@code <conversionRule>}.
 * Actif sur tous les profils (local, dev, prod).
 *
 * <p>Note : les stack traces ne sont PAS masquées (coût trop élevé, données rarement
 * exposées dans les traces). Si nécessaire, activer le masquage sur les throwables
 * via {@code ThrowableProxyConverter}.
 */
public class PiiMaskingConverter extends MessageConverter {

    private static final Pattern PHONE_PATTERN =
        Pattern.compile("\\+[1-9][0-9]{7,14}");

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null) return "";
        message = PHONE_PATTERN.matcher(message).replaceAll("***PHONE***");
        message = EMAIL_PATTERN.matcher(message).replaceAll("***EMAIL***");
        return message;
    }
}
